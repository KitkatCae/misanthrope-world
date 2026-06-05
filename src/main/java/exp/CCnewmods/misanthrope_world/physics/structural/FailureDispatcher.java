package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackStateMap;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.StructuralData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.FailureMode;
import exp.CCnewmods.misanthrope_world.physics.collapse.LatticeCollapseBlock;
import exp.CCnewmods.misanthrope_world.physics.collapse.LatticeCollapseBlockEntity;
import exp.CCnewmods.misanthrope_world.objects.MisWorldBlockEntityRegistry;
import exp.CCnewmods.misanthrope_world.objects.MisWorldBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;

/**
 * Routes structural block failures to the appropriate execution path.
 *
 * <h3>Failure modes</h3>
 * <dl>
 *   <dt>{@code CRUMBLE}</dt>
 *   <dd>Instant: {@link ServerLevel#destroyBlock} with drops. No chain reaction.
 *       Used for small-scale localized failures (a single weak block giving way).</dd>
 *
 *   <dt>{@code CAVE_IN}</dt>
 *   <dd>Triggers a Minecollapse chain reaction from the failed position(s).
 *       Minecollapse drives all propagation, debris physics, and sound from here.
 *       We bypass Minecollapse's natural trigger system by calling
 *       {@code WorldTracker.addCollapsePositions} directly — our data says
 *       when to cave, Minecollapse says how.</dd>
 *
 *   <dt>{@code FRAGMENT_VS2}</dt>
 *   <dd>Assembles the failed block group as a new VS2 physics ship, applies an
 *       initial velocity impulse in the failure direction, and lets VS2 handle
 *       all subsequent physics. Used for boulders, cliff sheets, stalactites.</dd>
 *
 *   <dt>{@code LATTICE_COLLAPSE}</dt>
 *   <dd>Atomic-lattice implosion. Replaces the block(s) with a
 *       {@code CollapseAnimatingBlockEntity} that plays the shrink animation,
 *       then places the result block (or spawns a black hole entity).
 *       Not implemented in this phase — stubs fire CRUMBLE instead and log a
 *       warning so you know it was hit.</dd>
 * </dl>
 *
 * <h3>Group dispatch</h3>
 * When called from the Option B connected-failure BFS, the {@code failureMode}
 * is determined by majority vote across the group. All blocks in the group are
 * failed together as a single event.
 */
public final class FailureDispatcher {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/FailureDispatcher");

    private static final boolean MINECOLLAPSE_LOADED =
            ModList.get().isLoaded("minecollapse");
    private static final boolean VS2_LOADED =
            ModList.get().isLoaded("valkyrienskies");

    private FailureDispatcher() {
    }

    private static void executeLatticeCollapse(ServerLevel level, BlockPos pos,
                                               BlockState originalState,
                                               StructuralData sd,
                                               net.minecraft.core.Direction failureDir) {
        if (!level.isLoaded(pos)) return;

        ResourceLocation originalId = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getKey(originalState.getBlock());

        // Drop items from original block before replacing
        Block.dropResources(originalState, level, pos, null);

        // Replace with LatticeCollapseBlock
        level.setBlock(pos,
                MisWorldBlocks.LATTICE_COLLAPSE_BLOCK
                        .get().defaultBlockState(), 3);

        // Initialize the block entity
        if (level.getBlockEntity(pos) instanceof LatticeCollapseBlockEntity lbe) {
            BlockPhysicsData.LatticeCollapseData lcd = sd.latticeCollapse();
            ResourceLocation resultId = lcd != null ? lcd.resultBlock() : null;
            int duration = lcd != null ? lcd.implodeDurationTicks() : 40;
            lbe.initialize(
                    originalId,
                    pos.asLong(),
                    failureDir,
                    duration,
                    resultId,
                    sd.fractureToughness()
            );
        } else {
            LOGGER.error("[FailureDispatcher] LatticeCollapseBlockEntity missing at {}", pos);
            executeCrumble(level, pos);
        }
    }
    // ── Single-block dispatch ─────────────────────────────────────────────────

    /**
     * Dispatches a failure for a single block. Called by {@link StructuralStressField}
     * when the Option A reactive scan finds a block at or above its failure threshold.
     */
    public static void dispatch(ServerLevel level, BlockPos pos,
                                BlockState state, StructuralData sd) {
        // Remove crack source — the block is done
        CrackPropagator.removeSource("misanthrope_world:structural:" + pos.asLong());
        CrackStateMap.get(level).remove(pos);

        switch (sd.failureMode()) {
            case CRUMBLE -> executeCrumble(level, pos);
            case CAVE_IN -> executeCaveIn(level, Set.of(pos.immutable()), sd);
            case FRAGMENT_VS2 -> executeFragment(level, Set.of(pos.immutable()), state, sd);
            case LATTICE_COLLAPSE -> executeLatticeCollapse(level, pos, state, sd,
                    computeFailureDirection(level, Set.of(pos)));
        }
    }

    // ── Group dispatch (Option B connected-failure BFS result) ────────────────

    /**
     * Dispatches a failure for a group of connected blocks.
     * The failure mode is the most common mode in the group (majority vote).
     */
    public static void dispatchGroup(ServerLevel level, Set<BlockPos> group,
                                     BlockState representativeState, StructuralData representativeSd) {
        if (group.isEmpty()) return;

        // Remove all crack sources for the group
        for (BlockPos pos : group) {
            CrackPropagator.removeSource("misanthrope_world:structural:" + pos.asLong());
            CrackStateMap.get(level).remove(pos);
        }

        // Majority vote on failure mode
        int[] votes = new int[FailureMode.values().length];
        for (BlockPos pos : group) {
            if (!level.isLoaded(pos)) continue;
            BlockState bs = level.getBlockState(pos);
            StructuralData sd = exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry.get(bs).structural;
            if (sd != null) votes[sd.failureMode().ordinal()]++;
        }
        FailureMode mode = FailureMode.CRUMBLE;
        int best = 0;
        for (FailureMode fm : FailureMode.values()) {
            if (votes[fm.ordinal()] > best) {
                best = votes[fm.ordinal()];
                mode = fm;
            }
        }

        switch (mode) {
            case CRUMBLE -> group.forEach(p -> executeCrumble(level, p));
            case CAVE_IN -> executeCaveIn(level, group, representativeSd);
            case FRAGMENT_VS2 -> executeFragment(level, group, representativeState, representativeSd);
            case LATTICE_COLLAPSE -> group.forEach(p -> {
                BlockState bs = level.getBlockState(p);
                StructuralData bsd = exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry
                        .get(bs).structural;
                if (bsd != null) executeLatticeCollapse(level, p, bs, bsd,
                        computeFailureDirection(level, group));
            });
        }
    }

    // ── Execution paths ───────────────────────────────────────────────────────

    private static void executeCrumble(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        level.destroyBlock(pos, true); // drops items
    }

    private static void executeCaveIn(ServerLevel level, Set<BlockPos> group,
                                      StructuralData sd) {
        if (!MINECOLLAPSE_LOADED) {
            group.forEach(p -> executeCrumble(level, p));
            return;
        }
        try {
            var capOpt = level.getCapability(
                    net.zerodind.minecollapse.util.WorldTrackerCapability.CAPABILITY).resolve();
            if (capOpt.isEmpty()) {
                group.forEach(p -> executeCrumble(level, p));
                return;
            }
            var tracker = capOpt.get();

            // Find centroid of the group as the collapse origin
            long sumX = 0, sumY = 0, sumZ = 0;
            for (BlockPos p : group) {
                sumX += p.getX();
                sumY += p.getY();
                sumZ += p.getZ();
            }
            BlockPos center = new BlockPos(
                    (int) (sumX / group.size()),
                    (int) (sumY / group.size()),
                    (int) (sumZ / group.size()));

            // Build position list for Minecollapse
            java.util.List<BlockPos> positions = new java.util.ArrayList<>(group);

            // Radius: use override if set, otherwise compute from group extents
            double radiusSq;
            if (sd.caveInRadiusOverride() >= 0) {
                double r = sd.caveInRadiusOverride();
                radiusSq = r * r;
            } else {
                double maxDist = 1.0;
                for (BlockPos p : group) maxDist = Math.max(maxDist, p.distSqr(center));
                radiusSq = Math.max(maxDist, 9.0); // minimum 3-block radius
            }

            var collapse = new net.zerodind.minecollapse.util.Collapse(
                    center, positions, radiusSq);
            tracker.addCollapseData(collapse);
        } catch (Exception e) {
            LOGGER.error("[FailureDispatcher] Minecollapse cave-in failed: {} — crumbling instead", e.getMessage());
            group.forEach(p -> executeCrumble(level, p));
        }
    }

    private static void executeFragment(ServerLevel level, Set<BlockPos> group,
                                        BlockState representativeState, StructuralData sd) {
        if (!VS2_LOADED || group.size() < sd.fragmentMinSize()) {
            group.forEach(p -> executeCrumble(level, p));
            return;
        }
        try {
            // Determine failure direction: for falling blocks, downward;
            // for lateral shear, find the direction with most air neighbours
            net.minecraft.core.Direction failureDir = computeFailureDirection(level, group);

            // Assemble the group as a VS2 ship
            var positions = group.stream()
                    .map(p -> new BlockPos(p.getX(), p.getY(), p.getZ()))
                    .collect(java.util.stream.Collectors.toSet());

            // assembleToShipFull returns the new ship; we then apply a velocity impulse
            var ship = org.valkyrienskies.mod.common.assembly.ShipAssembler.INSTANCE
                    .assembleToShipFull(level, positions, 1.0);

            if (ship != null) {
                // Apply initial velocity impulse in failure direction
                double speed = 2.0 * sd.fragmentInitialVelocityScale();
                org.joml.Vector3d impulse = new org.joml.Vector3d(
                        failureDir.getStepX() * speed,
                        failureDir.getStepY() * speed,
                        failureDir.getStepZ() * speed
                );
                // PhysShip force application happens on the physics thread via attachment
                // We use the safe applyInvariantForce path through PhysShip
                var loadedShip = org.valkyrienskies.mod.common.VSGameUtilsKt
                        .getLoadedShipManagingPos(level,
                                group.iterator().next());
                if (loadedShip instanceof org.valkyrienskies.core.api.ships.LoadedServerShip lss) {
                    applyFragmentImpulse(level, group, impulse, group.size());
                }
            } else {
                // Assembly failed — crumble
                group.forEach(p -> executeCrumble(level, p));
            }
        } catch (Exception e) {
            LOGGER.error("[FailureDispatcher] VS2 fragment assembly failed: {} — crumbling instead", e.getMessage());
            group.forEach(p -> executeCrumble(level, p));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Determines the primary failure direction for a group of blocks.
     * For mostly-vertical groups (stalactites, columns): downward.
     * For mostly-horizontal groups (cliff sheets, floors): horizontal toward most air.
     */
    private static net.minecraft.core.Direction computeFailureDirection(
            ServerLevel level, Set<BlockPos> group) {

        // Count air neighbours in each direction
        int[] airCounts = new int[6];
        net.minecraft.core.Direction[] dirs = net.minecraft.core.Direction.values();

        for (BlockPos pos : group) {
            for (int i = 0; i < dirs.length; i++) {
                BlockPos adj = pos.relative(dirs[i]);
                if (!group.contains(adj) && level.isLoaded(adj)
                        && level.getBlockState(adj).isAir()) {
                    airCounts[i]++;
                }
            }
        }

        // Strongly bias downward — gravity is the primary driver for most collapses
        airCounts[net.minecraft.core.Direction.DOWN.ordinal()] = (int)
                (airCounts[net.minecraft.core.Direction.DOWN.ordinal()] * 2.5);

        int best = 0;
        int bestDir = net.minecraft.core.Direction.DOWN.ordinal();
        for (int i = 0; i < airCounts.length; i++) {
            if (airCounts[i] > best) {
                best = airCounts[i];
                bestDir = i;
            }
        }
        return dirs[bestDir];
    }

    /**
     * Applies a one-tick impulse to a newly fragmented VS2 ship.
     * Uses VS2's {@code BlockEntityPhysicsListener} registered at one of the
     * group's block positions, fires once on the first physics tick, then
     * unregisters itself.
     */
    private static void applyFragmentImpulse(ServerLevel level,
                                             Set<BlockPos> group,
                                             org.joml.Vector3d impulseDir,
                                             int groupSize) {
        if (group.isEmpty()) return;
        BlockPos anchor = group.iterator().next().immutable();
        String dimId = level.dimension().location().toString();

        // Scale impulse by estimated mass so all fragment sizes feel similar
        double mass = groupSize * 2400.0 * 9.81e-3;
        org.joml.Vector3d scaledImpulse = new org.joml.Vector3d(impulseDir).mul(mass * 0.5);

        // Register a one-shot BlockEntityPhysicsListener at the anchor position.
        // VS2 will call physTick on the first physics tick after assembly.
        var listener = new OneTickImpulseListener(scaledImpulse, dimId, anchor);
        org.valkyrienskies.mod.common.ValkyrienSkiesMod.INSTANCE
                .addBlockEntityPhysTicker(dimId, anchor, listener);
    }

    /**
     * {@link org.valkyrienskies.mod.api.BlockEntityPhysicsListener} that fires
     * a single force impulse on the first physics tick and then removes itself.
     */
    public static final class OneTickImpulseListener
            implements org.valkyrienskies.mod.api.BlockEntityPhysicsListener {

        private final org.joml.Vector3d impulse;
        private final String dimId;
        private final BlockPos anchor;
        private boolean fired = false;

        // BlockEntityPhysicsListener is a Kotlin interface with a 'dimension'
        // property (getDimension / setDimension). We back it with a field.
        private String dimension;

        OneTickImpulseListener(org.joml.Vector3d impulse, String dimId, BlockPos anchor) {
            this.impulse = impulse;
            this.dimId = dimId;
            this.anchor = anchor;
            this.dimension = dimId;
        }

        // ── BlockEntityPhysicsListener property ────────────────────────────────

        @Override
        public String getDimension() {
            return dimension;
        }

        @Override
        public void setDimension(String value) {
            this.dimension = value;
        }

        // ── Physics tick ──────────────────────────────────────────────────────

        @Override
        public void physTick(org.valkyrienskies.core.api.ships.PhysShip physShip,
                             org.valkyrienskies.core.api.world.PhysLevel physLevel) {
            if (fired) return;
            fired = true;
            physShip.applyInvariantForce(impulse);
            // Schedule removal on the game thread (physTick is on the physics thread)
            try {
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                        .execute(() -> org.valkyrienskies.mod.common.ValkyrienSkiesMod.INSTANCE
                                .removeBlockEntityPhysTicker(anchor, dimId));
            } catch (Exception ignored) {
                // Server unavailable in edge cases — listener fires once then no-ops
            }
        }
    }
}