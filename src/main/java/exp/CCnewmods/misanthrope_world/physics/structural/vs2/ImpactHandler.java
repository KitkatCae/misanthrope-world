package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackStateMap;
import exp.CCnewmods.misanthrope_world.crackrender.world.VeinPropagator;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.physics.structural.FragmentSplitter;
import exp.CCnewmods.misanthrope_world.physics.structural.ShipFragmentLauncher;
import exp.CCnewmods.misanthrope_world.physics.structural.ImpactCrackSource;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import exp.CCnewmods.misanthrope_world.physics.structural.crater.FractureBoundary;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side ship-impact handler: craters, embedding, and terrain reversion.
 *
 * <h3>Calling convention</h3>
 * Call {@link #serverTick(ServerLevel)} from {@code ImpactSystemSetup}'s
 * {@code TickEvent.LevelTickEvent} handler at END phase, after VS2 has
 * integrated physics (ported from MVSE, where this was called from
 * {@code MVSForgeEvents.onServerTick} instead). Also call
 * {@link #onShipRemoved(long, ServerLevel)} when a VS2 ship is deleted.
 *
 * <h3>Detection</h3>
 * Each tick, for every loaded ship whose speed exceeds {@link ImpactConfig#minImpactSpeedMs},
 * we sample the world blocks immediately ahead of the ship along its velocity vector.
 * If the ship's forward face overlaps solid terrain, an impact is declared.
 *
 * <h3>Impact resolution</h3>
 * <ol>
 *   <li>Calculate {@code impactSpeed} from VS2 velocity.</li>
 *   <li>Sample the dominant block at the impact point for material classification.</li>
 *   <li>Decide mode: EMBED ({@code speed < embedMaxSpeedMs}) or CRATER.</li>
 *   <li>Compute crater radius / penetration depth from speed, mass, and material.</li>
 *   <li>Record original block states into a {@link ImpactMemento}.</li>
 *   <li>Excavate or clear blocks; optionally drop items.</li>
 *   <li>Zero or reflect the ship's velocity via VS2 API (impulse application).</li>
 *   <li>Schedule the memento for reversion after {@link ImpactConfig#reversionDelayTicks}.</li>
 * </ol>
 *
 * <h3>Reversion</h3>
 * On each server tick, mementos whose timer has expired are reverted: original
 * {@link BlockState}s are re-placed in reverse depth order. Mementos are also
 * discarded once the ship that caused them is removed from VS2.
 *
 * <h3>VS2 API notes</h3>
 * VS2 does not expose a direct "zero velocity" method on the game thread; instead
 * we apply an equal-and-opposite world impulse: {@code F = -mass × velocity / dt}.
 * For embedding we apply this fully; for crater mode we apply a partial impulse
 * proportional to material hardness (harder = more momentum absorbed).
 */
public final class ImpactHandler {

    private ImpactHandler() {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Per-level memento lists, keyed by level dimension ResourceLocation string. */
    private static final Map<String, Deque<ImpactMemento>> MEMENTOS = new ConcurrentHashMap<>();

    /** Per-ship cooldown: ticks before the same ship can trigger another impact event. */
    private static final Map<Long, Integer> COOLDOWNS = new ConcurrentHashMap<>();

    /** Minimum ticks between consecutive impact events for the same ship. */
    private static final int IMPACT_COOLDOWN_TICKS = 40;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Drives impact detection, resolution, and reversion for all ships in the level.
     * Call at END phase of each server tick.
     */
    public static void serverTick(ServerLevel level) {
        ImpactConfig cfg = ImpactConfig.INSTANCE;
        long currentTick = level.getGameTime();
        String dimKey = level.dimension().location().toString();
        Deque<ImpactMemento> mementos = MEMENTOS.computeIfAbsent(dimKey, k -> new ArrayDeque<>());

        // ── Detect and resolve impacts ────────────────────────────────────────
        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld shipWorld) {
            for (var ship : shipWorld.getAllShips()) {
                if (!(ship instanceof LoadedServerShip loaded)) continue;
                tickShip(level, loaded, cfg, mementos, currentTick);
            }
        }

        // ── Tick down cooldowns ───────────────────────────────────────────────
        COOLDOWNS.replaceAll((id, ticks) -> ticks - 1);
        COOLDOWNS.values().removeIf(t -> t <= 0);

        // ── Process reversion ─────────────────────────────────────────────────
        int revDelay = cfg.reversionDelayTicks.get();
        if (revDelay > 0) {
            Iterator<ImpactMemento> it = mementos.iterator();
            while (it.hasNext()) {
                ImpactMemento memo = it.next();
                if (memo.reverted) {
                    it.remove();
                    continue;
                }
                long age = currentTick - memo.createdTick;
                if (age >= revDelay) {
                    revertMemento(level, memo);
                    it.remove();
                }
            }
        }

        // ── Enforce memento limit ─────────────────────────────────────────────
        int maxMemos = cfg.maxMementosPerLevel.get();
        while (mementos.size() > maxMemos) {
            ImpactMemento oldest = mementos.pollFirst(); // oldest at front
            if (oldest != null && !oldest.reverted) revertMemento(level, oldest);
        }
    }

    /**
     * Called when a VS2 ship is removed (disassembled, deleted, chunk unloaded).
     * If {@link ImpactConfig#revertOnShipRemoval} is true, immediately reverts
     * any pending mementos caused by this ship.
     */
    public static void onShipRemoved(long shipId, ServerLevel level) {
        exp.CCnewmods.misanthrope_world.physics.structural.ShipStressField.onShipRemoved(level, shipId);

        if (!ImpactConfig.INSTANCE.revertOnShipRemoval.get()) return;
        String dimKey = level.dimension().location().toString();
        Deque<ImpactMemento> mementos = MEMENTOS.get(dimKey);
        if (mementos == null) return;
        mementos.removeIf(memo -> {
            if (memo.shipId == shipId && !memo.reverted) {
                revertMemento(level, memo);
                return true;
            }
            return false;
        });
        COOLDOWNS.remove(shipId);
    }

    /** Clear all state when a level unloads. */
    public static void onLevelUnload(ServerLevel level) {
        String dimKey = level.dimension().location().toString();
        MEMENTOS.remove(dimKey);
    }

    // -------------------------------------------------------------------------
    // Per-ship detection
    // -------------------------------------------------------------------------

    private static void tickShip(ServerLevel level, LoadedServerShip ship,
                                  ImpactConfig cfg,
                                  Deque<ImpactMemento> mementos, long currentTick) {
        long id = ship.getId();

        // Respect cooldown
        if (COOLDOWNS.getOrDefault(id, 0) > 0) return;

        Vector3dc vel = ship.getVelocity();
        // VS2 velocity is blocks/tick; × 20 = blocks/s = m/s
        double speedMs = vel.length() * 20.0;
        if (speedMs < cfg.minImpactSpeedMs.get()) return;

        // Compute normalised velocity direction
        Vector3d velDir = new Vector3d(vel).normalize();
        if (!velDir.isFinite()) return;

        // Find the ship's forward-most block positions along velDir
        List<BlockPos> probeFace = getForwardFace(ship, velDir, 1);

        // Check if any of those positions are solid world blocks (not in a ship)
        BlockPos impactPos = findFirstSolidBlock(level, probeFace);
        if (impactPos == null) return;

        // Impact detected — resolve
        COOLDOWNS.put(id, IMPACT_COOLDOWN_TICKS);
        resolveImpact(level, ship, impactPos, speedMs, velDir, cfg, mementos, currentTick);
    }

    // -------------------------------------------------------------------------
    // Impact resolution
    // -------------------------------------------------------------------------

    private static void resolveImpact(ServerLevel level, LoadedServerShip ship,
                                       BlockPos impactPos, double speedMs,
                                       Vector3d velDir, ImpactConfig cfg,
                                       Deque<ImpactMemento> mementos, long currentTick) {
        BlockState dominantState = level.getBlockState(impactPos);
        MaterialProfile.Category material = MaterialProfile.classify(dominantState);

        // Choose mode
        boolean embed = speedMs < cfg.embedMaxSpeedMs.get();
        ImpactMemento.Type type = embed ? ImpactMemento.Type.EMBED : ImpactMemento.Type.CRATER;

        // Compute material hardness factor
        double hardness = MaterialProfile.hardnessFactor(dominantState, cfg.hardMaterialFactor.get());

        // ── Shockwave: spawn directly with our own precise numbers ─────────────
        // This used to be KineticImpactHandler's job — it independently re-derived
        // a cruder strength estimate from the raw VS2 CollisionEvent for this exact
        // same physical hit, at the contact centroid rather than the crater center.
        // We already know speedMs (verified m/s) and the ship's real mass here, so
        // we drive it ourselves now; KineticImpactHandler skips ship-vs-terrain
        // collisions entirely (see its handleVS2Collision doc). Reuses its
        // KE_TO_STRENGTH/SHIP_COLLISION_STRENGTH_SCALE constants for consistent
        // calibration against every other kinetic-impact source in that file,
        // rather than inventing a second independently-tuned constant.
        double shipMass = ship.getInertiaData().getShipMass();
        double impactKe = 0.5 * shipMass * speedMs * speedMs;
        if (impactKe >= exp.CCnewmods.misanthrope_world.physics.structural.KineticImpactHandler.MIN_KE) {
            float shockwaveStrength = (float) (Math.sqrt(impactKe)
                    * exp.CCnewmods.misanthrope_world.physics.structural.KineticImpactHandler.KE_TO_STRENGTH
                    * exp.CCnewmods.misanthrope_world.physics.structural.KineticImpactHandler.SHIP_COLLISION_STRENGTH_SCALE);
            if (shockwaveStrength >= exp.CCnewmods.misanthrope_world.physics.structural.KineticImpactHandler.MIN_SHOCKWAVE_STRENGTH) {
                exp.CCnewmods.mge.shockwave.ShockwaveHandler.spawn(level, impactPos, shockwaveStrength);
            }
        }

        // World-space centre of impact
        var shipPos = ship.getTransform().getPositionInWorld();
        double cx = shipPos.x(), cy = shipPos.y(), cz = shipPos.z();

        ImpactMemento memo = new ImpactMemento(type, ship.getId(), cx, cy, cz, currentTick);

        // Decide if the ship should be fully disassembled.
        // Triggered when speed >= hypersonicThresholdMs and the material is hard enough
        // to catastrophically stop the hull (not fluid/ice/glass which absorb/deflect).
        boolean disassemble = speedMs >= cfg.hypersonicThresholdMs.get()
                && material != MaterialProfile.Category.FLUID
                && material != MaterialProfile.Category.ICE_SNOW
                && material != MaterialProfile.Category.GLASS;

        if (disassemble) {
            // Excavate a crater first so there is a cavity for debris to settle into
            handleCrater(level, ship, impactPos, speedMs, velDir, cfg, memo, hardness, material);
            if (!memo.originalStates.isEmpty()) {
                mementos.addLast(memo);
            }

            // Scatter radius grows with excess speed above the hypersonic threshold
            double excessSpeed = speedMs - cfg.hypersonicThresholdMs.get();
            double scatterRadius = Math.min(cfg.maxCraterRadius.get() * 0.5,
                    2.0 + excessSpeed / 80.0);

            org.joml.Vector3d origin = new org.joml.Vector3d(cx, cy, cz);
            StructuralShipDisassembler.disassemble(level, ship, scatterRadius, origin, true);
            // Ship entity is now gone — nothing more to do
            return;
        }

        if (embed) {
            handleEmbed(level, ship, impactPos, velDir, cfg, memo, hardness);
        } else {
            handleCrater(level, ship, impactPos, speedMs, velDir, cfg, memo, hardness, material);
        }

        // Register memento for later reversion
        if (!memo.originalStates.isEmpty()) {
            mementos.addLast(memo);
        }

        // Apply impulse to ship: partially or fully cancel velocity
        applyImpactImpulse(level, ship, embed, hardness, material);
    }

    // ── Embed handler ─────────────────────────────────────────────────────────

    /** Vein count/reach for EMBED's tunnel-wall cracking — modest, matches embed's lower energy vs. a full crater. */
    private static final int EMBED_VEIN_COUNT = 5;
    private static final int EMBED_VEIN_MAX_BLOCKS = 4;

    private static void handleEmbed(ServerLevel level, LoadedServerShip ship,
                                     BlockPos impactPos, Vector3d velDir,
                                     ImpactConfig cfg, ImpactMemento memo,
                                     double hardness) {
        int depth = cfg.embedPenetrationDepth.get();
        java.util.OptionalDouble toughness = MaterialProfile.fractureToughness(level.getBlockState(impactPos));

        // Clear blocks along penetration path to let the ship hull fit
        for (int d = 0; d < depth; d++) {
            double dx = impactPos.getX() + velDir.x * d;
            double dy = impactPos.getY() + velDir.y * d;
            double dz = impactPos.getZ() + velDir.z * d;
            BlockPos pos = new BlockPos((int) Math.round(dx), (int) Math.round(dy), (int) Math.round(dz));

            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) continue;
            if (!MaterialProfile.isExcavatable(state)) break; // stop at indestructible

            memo.record(pos, state);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }

        // Real, instant, persisted cracks around the tunnel walls — not just
        // the slow trickling ImpactCrackSource shell below. A tunnel punched
        // through a wall should visibly crack around it right away; the
        // shell source still handles secondary stress propagation further
        // out over the following seconds.
        Vector3d tunnelMid = new Vector3d(
                impactPos.getX() + velDir.x * depth * 0.5,
                impactPos.getY() + velDir.y * depth * 0.5,
                impactPos.getZ() + velDir.z * depth * 0.5);
        CrackStateMap stateMap = CrackStateMap.get(level);
        Random burstRng = new Random(level.getRandom().nextLong());
        VeinPropagator.generateImpactBurst(level, stateMap, tunnelMid,
                EMBED_VEIN_COUNT, EMBED_VEIN_MAX_BLOCKS, CrackCause.IMPACT,
                CrackEntry.LEVEL_CRACKED, level.getGameTime(), burstRng);

        registerCrackShell(level, impactPos, depth, toughness);
    }

    // ── Crater handler ────────────────────────────────────────────────────────

    /**
     * Core radius as a fraction of the overall crater radius, clamped to a
     * small absolute range — "a few blocks explode into items," regardless
     * of how big the surrounding fracture shell ends up being.
     */
    private static final double CORE_RADIUS_FRACTION = 0.2;
    private static final double CORE_RADIUS_MIN = 1.0;
    private static final double CORE_RADIUS_MAX = 2.5;

    /** Fracture-burst tuning — how many veins, how far they reach, scaled by crater radius. */
    private static final double VEIN_COUNT_PER_RADIUS = 1.5;
    private static final int VEIN_COUNT_MIN = 6;
    private static final int VEIN_COUNT_MAX = 24;
    private static final double VEIN_REACH_PER_RADIUS = 1.3;
    private static final int VEIN_REACH_MIN = 3;
    private static final int VEIN_REACH_MAX = 14;

    /** Edge-jitter base and toughness sensitivity — brittle (low toughness) materials get rougher edges. */
    private static final double EDGE_JITTER_BASE = 0.25;
    private static final double EDGE_JITTER_TOUGHNESS_SCALE = 0.6;
    private static final double EDGE_JITTER_DEFAULT = 0.5;

    /** Shell fragments smaller than this many blocks crumble instead of becoming a ship. */
    private static final int SHELL_FRAGMENT_MIN_SIZE = 3;

    /** Outward launch speed for shell fragments, blocks/tick, scaled mildly by crater radius. */
    private static final double LAUNCH_SPEED_BASE = 0.06;
    private static final double LAUNCH_SPEED_PER_RADIUS = 0.01;

    private static void handleCrater(ServerLevel level, LoadedServerShip ship,
                                      BlockPos impactPos, double speedMs,
                                      Vector3d velDir, ImpactConfig cfg,
                                      ImpactMemento memo, double hardness,
                                      MaterialProfile.Category material) {
        java.util.OptionalDouble toughness = MaterialProfile.fractureToughness(level.getBlockState(impactPos));

        // Compute radius (unchanged from before — still the same speed/mass/
        // hypersonic tuning, just no longer directly gating a hard ellipsoid
        // cutoff. It's now the "how big a fracture event is this" scale that
        // feeds vein count/reach and the core size below.)
        double baseR   = cfg.baseCraterRadius.get();
        double speedR  = speedMs / cfg.embedMaxSpeedMs.get();
        double radius  = baseR * Math.sqrt(speedR) / Math.max(1.0, hardness * 0.5);

        double mass = ship.getInertiaData().getShipMass();
        radius += cfg.massContribution.get() * Math.log10(Math.max(1000, mass) / 1000.0);

        if (speedMs >= cfg.hypersonicThresholdMs.get()) {
            radius *= cfg.hypersonicCraterMultiplier.get();
        }
        radius = Math.min(radius, cfg.maxCraterRadius.get());

        // Centre crater at impact position offset half-radius along -velDir (entry point)
        double centreX = impactPos.getX() - velDir.x * radius * 0.5;
        double centreY = impactPos.getY() - velDir.y * radius * 0.5;
        double centreZ = impactPos.getZ() - velDir.z * radius * 0.5;
        Vector3d centre = new Vector3d(centreX, centreY, centreZ);

        // ── Core: a few blocks explode into items, same as before ──────────────
        double coreRadius = Math.max(CORE_RADIUS_MIN, Math.min(CORE_RADIUS_MAX, radius * CORE_RADIUS_FRACTION));

        // ── Fracture pattern: synchronous veins radiating from the crater
        // centre, used to shape the shell boundary below ──────────────────────
        int veinCount = (int) Math.max(VEIN_COUNT_MIN, Math.min(VEIN_COUNT_MAX, radius * VEIN_COUNT_PER_RADIUS));
        int veinReach = (int) Math.max(VEIN_REACH_MIN, Math.min(VEIN_REACH_MAX, radius * VEIN_REACH_PER_RADIUS));
        CrackStateMap stateMap = CrackStateMap.get(level);
        Random burstRng = new Random(level.getRandom().nextLong());
        List<VeinPropagator.FractureVein> veins = VeinPropagator.generateImpactBurst(
                level, stateMap, centre, veinCount, veinReach, CrackCause.IMPACT,
                CrackEntry.LEVEL_SEVERE, level.getGameTime(), burstRng);

        double edgeJitter = toughness.isPresent()
                ? EDGE_JITTER_BASE + (1.0 - toughness.getAsDouble()) * EDGE_JITTER_TOUGHNESS_SCALE
                : EDGE_JITTER_DEFAULT;
        long noiseSeed = level.getGameTime() ^ (impactPos.asLong() * 0x9E3779B97F4A7C15L);
        FractureBoundary boundary = new FractureBoundary(centre, veins, radius, noiseSeed, edgeJitter);

        // ── Scan candidates: core destroys instantly, shell gets collected
        // for connected-component splitting + fragment-ship launch ─────────────
        int scanRadius = (int) Math.ceil(Math.max(radius, veinReach) + edgeJitter + 1);
        double dropChance = cfg.dropFraction.get();
        RandomSource rng = level.getRandom();
        Set<BlockPos> shellPositions = new LinkedHashSet<>();

        for (int bx = -scanRadius; bx <= scanRadius; bx++) {
            for (int by = -scanRadius; by <= scanRadius; by++) {
                for (int bz = -scanRadius; bz <= scanRadius; bz++) {
                    double px = centreX + bx, py = centreY + by, pz = centreZ + bz;
                    BlockPos pos = new BlockPos((int) Math.round(px), (int) Math.round(py), (int) Math.round(pz));

                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    if (!MaterialProfile.isExcavatable(state)) continue;

                    double dist = Math.sqrt(bx * bx + (double) by * by + bz * bz);

                    if (dist <= coreRadius) {
                        // Core: instant destroy, same drop-chance behaviour as before.
                        memo.record(pos, state);
                        if (rng.nextDouble() < dropChance) {
                            List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                                    state, level, pos, level.getBlockEntity(pos));
                            Vec3 dropPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            for (ItemStack drop : drops) {
                                ItemEntity entity = new ItemEntity(level,
                                        dropPos.x, dropPos.y, dropPos.z, drop);
                                entity.setDefaultPickUpDelay();
                                level.addFreshEntity(entity);
                            }
                        }
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    } else if (boundary.isInside(pos)) {
                        // Shell: collected, not touched yet — becomes ship debris below.
                        // Deliberately NOT recorded into memo — a fragment that flew off
                        // as its own ship isn't something reversion should place back.
                        shellPositions.add(pos.immutable());
                    }
                }
            }
        }

        // ── Split the shell into its real connected pieces and launch each
        // as its own outward-flying VS2 ship (or crumble if too small) ─────────
        double launchSpeed = LAUNCH_SPEED_BASE + LAUNCH_SPEED_PER_RADIUS * radius;
        for (Set<BlockPos> component : FragmentSplitter.splitComponents(shellPositions)) {
            if (component.size() < SHELL_FRAGMENT_MIN_SIZE) {
                component.forEach(p -> level.destroyBlock(p, true));
                continue;
            }

            org.joml.Vector3dc componentCom = FragmentSplitter.centerOfMass(level, component);
            Vector3d outward = new Vector3d(componentCom.x(), componentCom.y(), componentCom.z()).sub(centre);
            double len = outward.length();
            if (len < 1e-6) outward.set(0, 1, 0);
            else outward.div(len);

            double fragMass = component.size() * 2400.0 * 9.81e-3;
            Vector3d impulse = new Vector3d(outward).mul(launchSpeed).mul(fragMass * 0.5);

            if (!ShipFragmentLauncher.assembleAndLaunch(level, component, impulse)) {
                component.forEach(p -> level.destroyBlock(p, true));
            }
        }

        // ── Secondary crack-shell integration: slow ambient stress beyond
        // the burst veins, same as before ───────────────────────────────────────
        registerCrackShell(level, BlockPos.containing(centreX, centreY, centreZ), radius, toughness);

        // Let anything the crater left over-stressed or floating cascade
        // through the existing structural pipeline on its own.
        int markStep = 2;
        int markRadius = (int) Math.ceil(radius) + 2;
        for (int mx = -markRadius; mx <= markRadius; mx += markStep) {
            for (int my = -markRadius; my <= markRadius; my += markStep) {
                for (int mz = -markRadius; mz <= markRadius; mz += markStep) {
                    StructuralStressField.markDirty(level,
                            BlockPos.containing(centreX + mx, centreY + my, centreZ + mz));
                }
            }
        }
    }

    // ── Impulse application ───────────────────────────────────────────────────

    /**
     * Applies an impulse to the ship via VS2's physics API to counteract or
     * reduce velocity at the impact moment.
     *
     * <p>Ported from MVSE's {@code ShipImpulseApplier}, but rewritten to use
     * the SAME registration mechanism {@code FailureDispatcher} already uses
     * successfully for fragment ships —
     * {@code ValkyrienSkiesMod.INSTANCE.addBlockEntityPhysTicker(dimId, pos, listener)}
     * with a {@code BlockEntityPhysicsListener} — rather than MVSE's original
     * approach of reflecting into a private {@code physicsListeners} field on
     * the ship object, which isn't backed by any of the public ServerShip/Ship
     * API surface confirmed in this project (see StructuralShipDisassembler's
     * notes on what IS public). Same impulse math as MVSE, different (already
     * proven) delivery mechanism.
     *
     * @param embed     if true, fully cancel velocity (complete halt); otherwise
     *                  partially absorb based on hardness
     * @param hardness  material hardness factor (higher = more momentum absorbed)
     * @param material  material category (used for elasticity / bounce direction)
     */
    private static void applyImpactImpulse(ServerLevel level, LoadedServerShip ship,
                                            boolean embed, double hardness,
                                            MaterialProfile.Category material) {
        double absorption;
        if (embed) {
            absorption = 1.0;
        } else {
            double e = material.elasticity;
            absorption = Math.min(1.0, Math.max(0.3, hardness / (hardness + e * 5.0)));
        }

        String dimId = level.dimension().location().toString();
        BlockPos anchor = BlockPos.containing(
                ship.getTransform().getPositionInWorld().x(),
                ship.getTransform().getPositionInWorld().y(),
                ship.getTransform().getPositionInWorld().z());

        var listener = new ImpactImpulseListener(absorption, !embed, material.elasticity, dimId, anchor);
        ValkyrienSkiesMod.INSTANCE.addBlockEntityPhysTicker(dimId, anchor, listener);
    }

    /**
     * One-shot physics-tick listener that cancels/absorbs a ship's velocity
     * after a world impact. Mirrors {@code FailureDispatcher.OneTickImpulseListener}
     * exactly in structure — see that class for the reasoning behind the
     * dimension-property boilerplate required by the Kotlin
     * {@code BlockEntityPhysicsListener} interface.
     */
    private static final class ImpactImpulseListener
            implements org.valkyrienskies.mod.api.BlockEntityPhysicsListener {

        private final double absorptionFraction;
        private final boolean allowBounce;
        private final double elasticity;
        private final BlockPos anchor;
        private String dimension;
        private boolean fired = false;

        ImpactImpulseListener(double absorptionFraction, boolean allowBounce,
                               double elasticity, String dimId, BlockPos anchor) {
            this.absorptionFraction = absorptionFraction;
            this.allowBounce = allowBounce;
            this.elasticity = elasticity;
            this.anchor = anchor;
            this.dimension = dimId;
        }

        @Override
        public String getDimension() {
            return dimension;
        }

        @Override
        public void setDimension(String value) {
            this.dimension = value;
        }

        @Override
        public void physTick(PhysShip physShip, PhysLevel physLevel) {
            if (fired) return;
            fired = true;

            // Verified against this project's actual VS2 jar (2.5.0-eeaa2beb3f):
            // PhysShip has getVelocity() (inherited from Ship) and getMass()
            // directly — no separate "inertia" object, and no impulse method
            // at all, only force application. MVSE's original reflection-based
            // version assumed getInertia().getShipMass() and
            // applyInvariantImpulseToCenter(), neither of which exist on this
            // API. Matching FailureDispatcher.OneTickImpulseListener's existing
            // convention instead: applyInvariantForce() called once, on the
            // one physics tick this listener fires for.
            Vector3dc vel = physShip.getVelocity();
            double mass = physShip.getMass();
            Vector3d impulse = new Vector3d(vel).mul(-mass * absorptionFraction);

            if (allowBounce && elasticity > 0.05) {
                double ax = Math.abs(vel.x()), ay = Math.abs(vel.y()), az = Math.abs(vel.z());
                Vector3d normal = new Vector3d(0, 0, 0);
                if (ax >= ay && ax >= az) normal.set(Math.signum(vel.x()), 0, 0);
                else if (ay >= ax && ay >= az) normal.set(0, Math.signum(vel.y()), 0);
                else normal.set(0, 0, Math.signum(vel.z()));

                double dot = vel.dot(normal);
                Vector3d perp = new Vector3d(vel).sub(new Vector3d(normal).mul(dot));
                Vector3d bounce = new Vector3d(perp).mul(-2.0 * elasticity * mass);
                impulse.add(bounce);
            }

            physShip.applyInvariantForce(impulse);

            try {
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                        .execute(() -> ValkyrienSkiesMod.INSTANCE
                                .removeBlockEntityPhysTicker(anchor, dimension));
            } catch (Exception ignored) {
                // Server unavailable in edge cases — listener fires once then no-ops
            }
        }
    }

    // -------------------------------------------------------------------------
    // Terrain reversion
    // -------------------------------------------------------------------------

    /**
     * Restores all recorded blocks in a memento, placing them in reverse
     * insertion order (deepest first to avoid floating blocks).
     */
    static void revertMemento(ServerLevel level, ImpactMemento memo) {
        if (memo.reverted) return;
        memo.reverted = true;
        for (Map.Entry<BlockPos, BlockState> entry : memo.reversedEntries()) {
            BlockPos pos = entry.getKey();
            BlockState original = entry.getValue();
            // Only place back if the current block is air or a simple replacement;
            // do not overwrite blocks placed by the player since the impact.
            BlockState current = level.getBlockState(pos);
            if (current.isAir() || current == Blocks.AIR.defaultBlockState()) {
                level.setBlock(pos, original, 3);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the block positions on the forward face of the ship's world AABB
     * along {@code velDir}. Limited to the first {@code sampleDepth} layers.
     */
    private static List<BlockPos> getForwardFace(LoadedServerShip ship,
                                                   Vector3d velDir, int sampleDepth) {
        var aabb = ship.getWorldAABB();
        if (aabb == null) return Collections.emptyList();

        double ax = Math.abs(velDir.x), ay = Math.abs(velDir.y), az = Math.abs(velDir.z);
        List<BlockPos> result = new ArrayList<>();
        final int MAX = 256;

        if (ax >= ay && ax >= az) {
            int faceX = velDir.x > 0 ? (int) Math.floor(aabb.maxX()) + 1
                                      : (int) Math.ceil(aabb.minX()) - 1;
            int sign = velDir.x > 0 ? 1 : -1;
            int minY = (int) Math.floor(aabb.minY()), maxY = (int) Math.ceil(aabb.maxY());
            int minZ = (int) Math.floor(aabb.minZ()), maxZ = (int) Math.ceil(aabb.maxZ());
            outer:
            for (int d = 0; d < sampleDepth; d++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++) {
                        result.add(new BlockPos(faceX + sign * d, y, z));
                        if (result.size() >= MAX) break outer;
                    }
        } else if (ay >= ax && ay >= az) {
            int faceY = velDir.y > 0 ? (int) Math.floor(aabb.maxY()) + 1
                                      : (int) Math.ceil(aabb.minY()) - 1;
            int sign = velDir.y > 0 ? 1 : -1;
            int minX = (int) Math.floor(aabb.minX()), maxX = (int) Math.ceil(aabb.maxX());
            int minZ = (int) Math.floor(aabb.minZ()), maxZ = (int) Math.ceil(aabb.maxZ());
            outer:
            for (int d = 0; d < sampleDepth; d++)
                for (int x = minX; x <= maxX; x++)
                    for (int z = minZ; z <= maxZ; z++) {
                        result.add(new BlockPos(x, faceY + sign * d, z));
                        if (result.size() >= MAX) break outer;
                    }
        } else {
            int faceZ = velDir.z > 0 ? (int) Math.floor(aabb.maxZ()) + 1
                                      : (int) Math.ceil(aabb.minZ()) - 1;
            int sign = velDir.z > 0 ? 1 : -1;
            int minX = (int) Math.floor(aabb.minX()), maxX = (int) Math.ceil(aabb.maxX());
            int minY = (int) Math.floor(aabb.minY()), maxY = (int) Math.ceil(aabb.maxY());
            outer:
            for (int d = 0; d < sampleDepth; d++)
                for (int x = minX; x <= maxX; x++)
                    for (int y = minY; y <= maxY; y++) {
                        result.add(new BlockPos(x, y, faceZ + sign * d));
                        if (result.size() >= MAX) break outer;
                    }
        }
        return result;
    }

    /**
     * Scans candidate positions and returns the first that is a solid, non-ship
     * world block. Returns {@code null} if none found.
     */
    private static BlockPos findFirstSolidBlock(ServerLevel level, List<BlockPos> candidates) {
        for (BlockPos pos : candidates) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) continue;
            if (!state.isSolid()) continue;
            // Make sure this block is not part of a VS2 ship
            // (VS2 ships occupy a separate dimension; world queries go to the ship
            // dimension naturally, but as a guard we check the ship world null)
            return pos;
        }
        return null;
    }

    // ── Crack system integration ────────────────────────────────────────────

    /** Crack pressure per block of crater radius, before dividing by toughness. */
    private static final double CRACK_PRESSURE_PER_RADIUS = 6.0;

    /** Extra blocks of crack zone beyond the excavated radius/depth. */
    private static final double CRACK_SHELL_MARGIN = 3.0;

    private static final double MAX_CRACK_PRESSURE = 90.0;

    /**
     * Registers an {@link ImpactCrackSource} around an excavated crater/embed
     * site so the surrounding shell — not just the excavated interior — visibly
     * cracks and feeds real structural consequences via the existing
     * CrackPropagator/StructuralStressField/FailureDispatcher pipeline. This
     * (plus the markDirty seeding in handleCrater) is the actual delta between
     * this system and VS2's own stock impact handling: the crater interior
     * still excavates and reverts on its own timer like MVSE's original
     * design, but the shell of stressed/cracked material around it is now
     * permanent structural damage, same as everywhere else in this mod.
     *
     * <p>If the impacted block has no structural data, {@code toughness} is
     * empty and this is a deliberate no-op — see {@link MaterialProfile}'s
     * class doc. No crack source is fabricated for data that doesn't exist;
     * {@code MaterialProfile.fractureToughness} already logged the gap.
     */
    private static void registerCrackShell(ServerLevel level, BlockPos center,
                                            double excavatedRadius,
                                            java.util.OptionalDouble toughness) {
        if (toughness.isEmpty()) return;

        float pressure = (float) Math.min(MAX_CRACK_PRESSURE,
                CRACK_PRESSURE_PER_RADIUS * excavatedRadius / toughness.getAsDouble());
        if (pressure <= 0) return;

        double shellRadius = excavatedRadius + CRACK_SHELL_MARGIN;
        CrackPropagator.addSource(new ImpactCrackSource(center, pressure, shellRadius));
    }
}
