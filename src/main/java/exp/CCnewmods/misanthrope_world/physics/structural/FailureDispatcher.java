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

import java.util.List;
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
 *   <dd>Minecollapse has been removed from the project. CAVE_IN now shares
 *       {@link #executeFragment} with FRAGMENT_VS2 — the failed group is
 *       split into its actual connected components (see below), each
 *       surviving component is assembled into a VS2 ship and given a
 *       downward/outward impulse, and VS2 drives the resulting physics,
 *       debris, and (via its built-in {@code ImpactFractureHandler}) terrain
 *       damage on landing. The two modes are kept as separate enum values
 *       since they still express different authoring intent (a caving
 *       ceiling vs. a sheared-off fragment) even though they now execute
 *       identically; that also means a future divergence (e.g. different
 *       impulse tuning per mode) doesn't require touching data files.</dd>
 *
 *   <dt>{@code FRAGMENT_VS2}</dt>
 *   <dd>Splits the failed block group into its connected components
 *       (26-connectivity — see {@link FragmentSplitter}), culls components
 *       smaller than {@code fragmentMinSize} to plain crumble, and assembles
 *       each surviving component as its own VS2 physics ship with its own
 *       initial velocity impulse in its own failure direction. Used for
 *       boulders, cliff sheets, stalactites, and (as of the Minecollapse
 *       removal) cave-ins as well.</dd>
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
 * failed together as a single event — except for FRAGMENT_VS2/CAVE_IN, which
 * now split that group into its real connected components first (see
 * {@link #executeFragment}) rather than assuming it's already one contiguous
 * mass. A connectedFailureBFS result IS already one contiguous mass by
 * construction, but other callers (crater excavation shells, future
 * ship-vs-ship fracture) may hand over groups that aren't — this split is
 * what makes those safe to route through the same path.
 */
public final class FailureDispatcher {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/FailureDispatcher");

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
            case CAVE_IN, FRAGMENT_VS2 -> executeFragment(level, Set.of(pos.immutable()), state, sd);
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
            case CAVE_IN, FRAGMENT_VS2 -> executeFragment(level, group, representativeState, representativeSd);
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

    /**
     * Entry point for FRAGMENT_VS2 / CAVE_IN. Splits {@code group} into its
     * actual connected components (26-connectivity, {@link FragmentSplitter})
     * before doing anything else — a "failing group" is not guaranteed to
     * already be one physically contiguous mass (see class doc). Each
     * surviving component (at or above {@code fragmentMinSize}) becomes its
     * own VS2 ship with its own impulse; smaller components crumble
     * individually, matching vox3D's {@code tuning.minFragment} debris
     * cutoff exactly.
     */
    private static void executeFragment(ServerLevel level, Set<BlockPos> group,
                                        BlockState representativeState, StructuralData sd) {
        if (!VS2_LOADED) {
            group.forEach(p -> executeCrumble(level, p));
            return;
        }

        List<Set<BlockPos>> components = FragmentSplitter.splitComponents(group);

        for (Set<BlockPos> component : components) {
            if (component.size() < sd.fragmentMinSize()) {
                component.forEach(p -> executeCrumble(level, p));
                continue;
            }
            spawnFragmentShip(level, component, representativeState, sd);
        }
    }

    /**
     * Assembles one already-connected component into a VS2 ship and applies
     * its initial velocity impulse via {@link ShipFragmentLauncher}. This is
     * exactly the single-ship assembly path the old (pre-split)
     * {@code executeFragment} did directly on the whole group — now called
     * once per surviving component instead of once for the whole
     * (possibly-disconnected) input, and now sharing its VS2 assembly/impulse
     * plumbing with {@code ImpactHandler}'s crater fragment launches instead
     * of keeping its own private copy of that plumbing.
     */
    private static void spawnFragmentShip(ServerLevel level, Set<BlockPos> component,
                                          BlockState representativeState, StructuralData sd) {
        // Determine failure direction: for falling blocks, downward;
        // for lateral shear, find the direction with most air neighbours.
        // Computed per-component so e.g. one half of a split wall that's
        // still resting on the ground doesn't get the same "downward"
        // verdict as the half that's now floating.
        net.minecraft.core.Direction failureDir = computeFailureDirection(level, component);

        double speed = 2.0 * sd.fragmentInitialVelocityScale();
        double mass = component.size() * 2400.0 * 9.81e-3;
        org.joml.Vector3d impulse = new org.joml.Vector3d(
                failureDir.getStepX(), failureDir.getStepY(), failureDir.getStepZ()
        ).mul(speed).mul(mass * 0.5);

        if (!ShipFragmentLauncher.assembleAndLaunch(level, component, impulse)) {
            component.forEach(p -> executeCrumble(level, p));
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

}
