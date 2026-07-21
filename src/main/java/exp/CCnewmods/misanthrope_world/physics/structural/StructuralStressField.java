package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.StructuralData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.structural.grid.CompressiveStressSimulator;
import exp.CCnewmods.misanthrope_world.physics.structural.grid.TensileStressSimulator;
import exp.CCnewmods.misanthrope_world.pos.WorldPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import exp.CCnewmods.misanthrope_world.physics.perf.PerfSampler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-level structural stress simulation.
 *
 * <p>Computes column load (compressive), ceiling span (tensile), and accumulated
 * stress for every tracked block. Feeds normalized stress into
 * {@link CrackPropagator} when stress exceeds the crack threshold, and hands
 * failures off to {@link FailureDispatcher} when the failure threshold is crossed.
 *
 * <h3>Two scan modes (Option A + B) — same evaluation, same failure path</h3>
 * <ul>
 *   <li><b>Option A — reactive:</b> Any block change ({@code BlockEvent.NeighborNotifyEvent}),
 *       thermal weakening notification, or dynamic stress contribution (see
 *       below) enqueues the changed block and its immediate neighbours.
 *       Processed every tick from a {@link ConcurrentLinkedDeque}.</li>
 *   <li><b>Option B — ambient:</b> A slow background walk through loaded chunk
 *       sections re-evaluates {@link #backgroundBlocksPerTick()} randomly
 *       selected blocks per tick. This lets naturally generated structures
 *       (stalactites, cliff overhangs) fail over time even when nothing nearby
 *       changes.</li>
 * </ul>
 * Both modes call the same {@link #evaluateBlock} and, on failure, both run
 * {@linkplain #connectedFailureBFS connected-failure BFS} the same way — a
 * stalactite falls whole and a shockwave-cracked wall section can shear off
 * together, regardless of which mode triggered the re-evaluation.
 *
 * <h3>Stress model</h3>
 * <pre>
 *   compressiveStress = columnLoad_kN / (compressiveStrength_kPa × face_area_m2)
 *   tensileStress     = bendingMoment / (tensileStrength_kPa × section_modulus)
 *   dynamicStress     = DynamicStressTracker.getSmoothedStress(pos)
 *   thermalFactor     = StructuralData.strengthFractionAt(blockTemp)
 *   effectiveStress   = max(compressiveStress, tensileStress, dynamicStress) / thermalFactor
 * </pre>
 * {@code dynamicStress} is an EMA-smoothed, decaying accumulator fed by
 * {@link DynamicStressTracker} — shockwaves, kinetic impacts, and (eventually)
 * real-time collision forces all funnel into the same value rather than each
 * being judged in isolation and forgotten. See {@link DynamicStressTracker}'s
 * class doc for why (repeated sub-threshold hits should be able to add up).
 * <p>
 * When {@code effectiveStress ≥ crackThresholdFraction}, an
 * {@link StructuralCrackSource} is registered with {@link CrackPropagator}.
 * When {@code effectiveStress ≥ failureThresholdFraction} for
 * {@link #OVERLOAD_HOLD_EVALUATIONS} consecutive evaluations — or immediately,
 * if {@link DynamicStressTracker} reports a fresh violent impact at this
 * position — {@link FailureDispatcher#dispatchGroup} is called on the
 * connected over-stressed group found by {@link #connectedFailureBFS}.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StructuralStressField {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/StructuralStress");

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Max column-load ray length (blocks downward).
     */
    public static final int MAX_COLUMN_DEPTH = 64;

    /**
     * Max air-span BFS width for tensile analysis.
     *
     * @deprecated No longer read internally — {@code computeSpan} now
     * delegates to {@code TensileStressSimulator}, which uses
     * {@code MisWorldConfig.stressGridTensileRadius()} instead. Left in
     * place (not deleted) since this is a {@code public} constant and
     * cross-mod code (MCT, Ousia, etc. — not verifiable from this codebase
     * alone) may reference it; safe to remove once confirmed nothing does.
     */
    @Deprecated
    public static final int MAX_SPAN_RADIUS = 16;

    /**
     * Background scan: blocks re-evaluated per tick across all loaded levels.
     * <p>
     * Previously hardcoded to 4, silently ignoring
     * {@code MisWorldConfig.structuralBackgroundBlocksPerTick()} (default 8,
     * range 1–64) even though that config option exists specifically for
     * this. Fixed to actually read it — this is also now the fastest lever
     * for tuning background-scan cost without a rebuild.
     */
    private static int backgroundBlocksPerTick() {
        return MisWorldConfig.structuralBackgroundBlocksPerTick();
    }

    /**
     * When a background failure fires, BFS radius for connected failure set.
     */
    public static final int MAX_CONNECTED_FAILURE_RADIUS = 8;

    /**
     * ── Per-tick BFS burst cap ─────────────────────────────────────────────────
     * connectedFailureBFS itself has no cap on how many times it can fire
     * within one tick — every one of this tick's (up to 32 dirty + N
     * background) evaluateBlock calls that finds shouldFail=true triggers
     * its own BFS synchronously, inline, with no throughput limit. In a mass
     * -failure burst (many positions crossing threshold in the same tick —
     * e.g. right after a chunk loads into a physics regime it wasn't
     * generated under) that's effectively unbounded work in one tick even
     * with the per-position budgets capping how many evaluations start.
     * <p>
     * The tick-scoped {@code computeColumnLoad} memo cache (see that
     * method's doc) should already collapse most of the *redundant* part of
     * this cost. These two caps are the backstop for what's left: once
     * either is hit, further shouldFail positions this tick are deferred —
     * re-queued via {@link #markDirty} for next tick — instead of run now.
     * Deferred failures aren't lost, just spread across a couple of ticks
     * instead of landing in one, which is the actual fix for "can't even
     * move" during a big collapse event.
     */
    private static final int MAX_BFS_INVOCATIONS_PER_TICK = 6;
    private static final int MAX_BFS_NODES_VISITED_PER_TICK = 400;

    private static final Map<ServerLevel, Integer> BFS_INVOCATIONS_THIS_TICK = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Integer> BFS_NODES_VISITED_THIS_TICK = new ConcurrentHashMap<>();

    /** True if this tick still has BFS budget left for another failure group. */
    private static boolean hasBfsBudget(ServerLevel level) {
        return BFS_INVOCATIONS_THIS_TICK.getOrDefault(level, 0) < MAX_BFS_INVOCATIONS_PER_TICK
                && BFS_NODES_VISITED_THIS_TICK.getOrDefault(level, 0) < MAX_BFS_NODES_VISITED_PER_TICK;
    }

    private static void recordBfsUsage(ServerLevel level, int nodesVisited) {
        BFS_INVOCATIONS_THIS_TICK.merge(level, 1, Integer::sum);
        BFS_NODES_VISITED_THIS_TICK.merge(level, nodesVisited, Integer::sum);
    }

    /**
     * Consecutive evaluations a block must be found at/above its failure
     * threshold before it actually fails, unless {@link DynamicStressTracker}
     * reports a fresh impact for this position (which bypasses this hold
     * entirely — see class doc and {@code DynamicStressTracker}'s doc for the
     * vox3D {@code overloadStreak}/{@code fractureHoldFrames} this mirrors).
     * Applies uniformly to every failure source (static load, shockwave,
     * kinetic) since they all funnel through the same {@code effectiveStress}
     * check now — a purely static overload (e.g. a stalactite already past
     * its limit) still fails within a handful of evaluations either way,
     * this just prevents a single noisy tick from being decisive.
     */
    private static final int OVERLOAD_HOLD_EVALUATIONS = 3;

    /**
     * kN per block of material at standard gravity (game-scaled).
     */
    private static final double G_GAME = 9.81e-3; // scaled so density_kg_m3 gives reasonable kN values

    /**
     * Face area in m² (1×1 block face).
     */
    private static final double FACE_AREA_M2 = 1.0;

    // ── Per-level state ───────────────────────────────────────────────────────

    /**
     * Level-keyed dirty queues for Option A reactive scanning.
     * Thread-safe: written by event handlers, drained by server tick.
     */
    private static final Map<ServerLevel, ConcurrentLinkedDeque<BlockPos>> DIRTY =
            new ConcurrentHashMap<>();

    /**
     * Level-keyed background scan cursors for Option B.
     * Each entry is a lazy iterator over the level's loaded chunk sections.
     */
    private static final Map<ServerLevel, BackgroundScanner> BACKGROUND =
            new ConcurrentHashMap<>();

    // ── External support registry ─────────────────────────────────────────────

    /**
     * External support members registered by other systems (e.g. hydraulic cylinders).
     *
     * <p>Key: block position of the support member (the cylinder block itself).<br>
     * Value: support capacity in kN — the maximum compressive load this member
     * can carry before the load passes through to the block below it.</p>
     *
     * <p>When {@link #computeColumnLoad} walks upward through a column and
     * encounters a position in this map, it subtracts the support capacity from
     * the running load total and stops the column walk at that point (the support
     * carries everything above it). If the actual column load exceeds the support
     * capacity, the excess propagates downward as normal.</p>
     *
     * <p>Thread-safe: written by game-thread BE ticks, read by the server-tick
     * stress evaluation. {@link ConcurrentHashMap} gives safe concurrent reads.</p>
     */
    private static final ConcurrentHashMap<BlockPos, Double> EXTERNAL_SUPPORTS =
            new ConcurrentHashMap<>();

    /**
     * Registers a block position as an external structural support with the given
     * load capacity in kN.
     *
     * <p>Call this when a hydraulic cylinder joint is created.
     * The capacity should be the cylinder's {@code maxForceN / 1000.0} (N → kN).</p>
     *
     * @param pos        world-space position of the support block
     * @param capacityKN maximum load this support can carry in kN
     */
    public static void registerExternalSupport(BlockPos pos, double capacityKN) {
        EXTERNAL_SUPPORTS.put(pos.immutable(), capacityKN);
    }

    /**
     * Removes a previously registered external support.
     *
     * <p>Call this when a hydraulic cylinder joint is removed (disassembled,
     * snapped, or block broken). Failure to call this leaves a ghost support
     * that will incorrectly cap column loads forever.</p>
     *
     * @param pos position of the support to remove
     */
    public static void unregisterExternalSupport(BlockPos pos) {
        EXTERNAL_SUPPORTS.remove(pos);
    }

    /**
     * Read-only accessor for {@link #G_GAME}. Added for
     * {@code CompressiveStressSimulator} (the new persistent stress-grid
     * write path) so it can match this file's exact gravity scaling instead
     * of duplicating the constant — nothing here changes existing behavior.
     */
    public static double gGame() {
        return G_GAME;
    }

    /**
     * Read-only accessor for {@link #EXTERNAL_SUPPORTS}. Same rationale as
     * {@link #gGame()} — {@code CompressiveStressSimulator} needs to check
     * the same support registry this file's own column walk already
     * consults, without a second, possibly-diverging copy of it.
     */
    @Nullable
    public static Double getExternalSupportCapacity(BlockPos pos) {
        return EXTERNAL_SUPPORTS.get(pos);
    }

    /**
     * Returns true if a block position is currently registered as an external support.
     * Useful for goggles display or structural analysis tools.
     */
    public static boolean isExternalSupport(BlockPos pos) {
        return EXTERNAL_SUPPORTS.containsKey(pos);
    }

    public static Set<net.minecraft.world.level.ChunkPos> getLoadedChunks(ServerLevel level) {
        return LOADED_CHUNKS.getOrDefault(level, Collections.emptySet());
    }

    // ── Option A — reactive dirty queue ──────────────────────────────────────

    /**
     * Enqueues {@code pos} and all 26 neighbours for re-evaluation.
     * Called by block-change events and by ThermalField when a block's
     * temperature crosses a strength-retention threshold.
     */
    public static void markDirty(ServerLevel level, BlockPos pos) {
        ConcurrentLinkedDeque<BlockPos> queue =
                DIRTY.computeIfAbsent(level, k -> new ConcurrentLinkedDeque<>());
        queue.add(pos.immutable());
        for (Direction d : Direction.values()) queue.add(pos.relative(d).immutable());
        // Full 3×3×3 minus the 6 face-neighbours already added
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) continue; // already added
                    queue.add(pos.offset(dx, dy, dz).immutable());
                }
            }
        }
    }

    /**
     * Called by {@code ThermalField} when a wall block's temperature rises
     * above the material's {@code strength_retention_curve} first threshold.
     * Only enqueues the single block — we don't need the full 26-neighbour
     * spread for a thermal weakening event.
     */
    public static void notifyThermalWeakening(ServerLevel level, BlockPos pos) {
        DIRTY.computeIfAbsent(level, k -> new ConcurrentLinkedDeque<>())
                .add(pos.immutable());
    }

    /**
     * Per-level set of loaded chunk positions, maintained by ChunkEvent.Load/Unload.
     * Used by BackgroundScanner instead of the protected ChunkMap.getChunks().
     */
    private static final Map<ServerLevel, Set<ChunkPos>> LOADED_CHUNKS =
            new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        LOADED_CHUNKS.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet())
                .add(event.getChunk().getPos());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Set<ChunkPos> set = LOADED_CHUNKS.get(level);
        if (set != null) set.remove(event.getChunk().getPos());
    }

    // ── Option B — background scanner ────────────────────────────────────────

    /**
     * Lazily walks loaded chunk sections in a shuffled order.
     * Rebuilt whenever all sections have been visited.
     */
    private static final class BackgroundScanner {
        private final ServerLevel level;
        private final Random rng;
        private final List<BlockPos> sectionOrigins = new ArrayList<>();
        private int idx = 0;

        BackgroundScanner(ServerLevel level) {
            this.level = level;
            this.rng = new Random(level.getSeed() ^ System.nanoTime());
            rebuildSections();
        }

        private void rebuildSections() {
            sectionOrigins.clear();
            Set<ChunkPos> loaded = LOADED_CHUNKS.getOrDefault(level, Collections.emptySet());
            for (ChunkPos cp : loaded) {
                for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                    sectionOrigins.add(new BlockPos(cp.getMinBlockX(), sy << 4, cp.getMinBlockZ()));
                }
            }
            Collections.shuffle(sectionOrigins, rng);
            idx = 0;
        }

        /**
         * Returns up to {@code count} candidate positions that have structural data.
         */
        List<BlockPos> next(int count) {
            if (sectionOrigins.isEmpty()) {
                rebuildSections();
                return List.of();
            }
            List<BlockPos> result = new ArrayList<>(count);
            int attempts = count * 8;
            while (result.size() < count && attempts-- > 0) {
                if (idx >= sectionOrigins.size()) {
                    rebuildSections();
                    break;
                }
                BlockPos origin = sectionOrigins.get(idx++);
                int x = origin.getX() + rng.nextInt(16);
                int y = origin.getY() + rng.nextInt(16);
                int z = origin.getZ() + rng.nextInt(16);
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.isLoaded(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                if (BlockPhysicsRegistry.get(state).structural == null) continue;
                result.add(pos);
            }
            return result;
        }
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        // Emergency off-switch: existing config toggles (crackSystem /
        // collapseSystem) were never actually wired to this tick before —
        // they gated crack propagation and collapse *dispatch*, but not the
        // stress evaluation that feeds them. Wired in now so either can be
        // flipped off in-config (no rebuild) if this ever needs to be killed
        // fast again.
        if (!MisWorldConfig.isCrackSystemEnabled() && !MisWorldConfig.isCollapseSystemEnabled()) return;

        PerfSampler.maybeLogAndReset(level.getGameTime());
        BFS_INVOCATIONS_THIS_TICK.put(level, 0);
        BFS_NODES_VISITED_THIS_TICK.put(level, 0);

        // Option A: drain dirty queue
        ConcurrentLinkedDeque<BlockPos> dirty = DIRTY.get(level);
        if (dirty != null) {
            // Process up to 32 per tick to avoid spikes; remainder stays queued
            Set<BlockPos> processed = new HashSet<>();
            int budget = 32;
            while (!dirty.isEmpty() && budget-- > 0) {
                BlockPos pos = dirty.poll();
                if (pos == null || !processed.add(pos)) continue;
                evaluateBlock(level, pos);
            }
        }

        // Option B: background scan
        BackgroundScanner scanner = BACKGROUND.computeIfAbsent(level, BackgroundScanner::new);
        List<BlockPos> candidates = scanner.next(backgroundBlocksPerTick());
        for (BlockPos pos : candidates) {
            evaluateBlock(level, pos);
        }

        // Ship-side sustained load — same tick, same event, its own per-ship
        // budget-limited background scan. See ShipStressField's class doc for
        // why this can't just reuse computeColumnLoad/BackgroundScanner as-is.
        ShipStressField.serverTick(level);
    }

    // ── Block-change hook (Option A trigger) ─────────────────────────────────

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Only care about blocks with structural data
        BlockState state = level.getBlockState(event.getPos());
        if (BlockPhysicsRegistry.get(state).structural != null) {
            markDirty(level, event.getPos());
        }
    }

    // ── Core evaluation ───────────────────────────────────────────────────────

    /**
     * Evaluates the structural stress at {@code pos} and either registers a
     * crack source, dispatches failure, or does nothing.
     * <p>
     * Called uniformly from both the Option A reactive drain and the Option B
     * background scan — there is no longer a distinction between them at
     * failure time. Previously only the background scan ran
     * {@link #connectedFailureBFS} before dispatching; reactive-triggered
     * failures (including, as of {@link DynamicStressTracker}, every
     * shockwave/kinetic hit) went through single-block {@code dispatch}
     * only. That meant a shockwave strong enough to genuinely bring down a
     * wall could only ever pop one block of it. Both paths now always BFS
     * first — a single unsupported block simply produces a one-element
     * group, which {@code dispatchGroup} already handles identically to
     * {@code dispatch}, so this isn't a behavior change for the common case,
     * only for the case that was actually missing group failure.
     */
    private static void evaluateBlock(ServerLevel level, BlockPos pos) {
        if (!PerfSampler.ENABLED) {
            evaluateBlockInner(level, pos);
            return;
        }
        long t0 = System.nanoTime();
        evaluateBlockInner(level, pos);
        PerfSampler.record("structural.evaluateBlock", System.nanoTime() - t0);
    }

    private static void evaluateBlockInner(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        StructuralData sd = data.structural;
        if (sd == null) return;

        // ── Compressive stress ────────────────────────────────────────────────
        double columnLoad = computeColumnLoad(level, pos);     // kN
        double compStress = columnLoad / (sd.compressiveStrengthKpa() * FACE_AREA_M2);

        // ── Tensile stress (ceiling/overhang analysis) ────────────────────────
        double tenStress = 0.0;
        if (sd.tensileStrengthKpa() > 0) {
            double span = computeSpan(level, pos);             // blocks
            // Simple beam bending: M = w*L²/8, section_modulus ∝ 1/tensileStrength
            double load = sd.densityKgM3() * G_GAME;          // kN/block
            double moment = load * span * span / 8.0;
            double sectionModulus = sd.tensileStrengthKpa() * 0.01; // game-scaled
            tenStress = moment / sectionModulus;
        }

        // ── Dynamic stress (shockwave, kinetic, future collision) ─────────────
        // EMA-smoothed and decayed by DynamicStressTracker — see its class doc.
        // Folded in as a third candidate for "worst stress this block sees",
        // same as compressive/tensile, rather than as a separate side-channel.
        DynamicStressTracker dynamicTracker = DynamicStressTracker.get(level);
        double dynStress = dynamicTracker.getSmoothedStress(pos);

        // ── Thermal weakening factor ──────────────────────────────────────────
        float blockTemp = exp.CCnewmods.mge.grid.EnvironmentGrid.getTemperature(level, pos);
        double ambientTemp = 20.0;
        double tempC = Float.isNaN(blockTemp) ? ambientTemp : blockTemp;
        double thermalFactor = Math.max(0.001, sd.strengthFractionAt(tempC));

        // ── pH corrosion factor ────────────────────────────────────────────────
        double corrosionFactor = 1.0;
        if (data.phReactivity != null) {
            corrosionFactor = updateAndGetCorrosionFactor(level, pos, data.phReactivity);
        }

        // ── Effective stress [0, ∞) — normalized to [0,1] at failure threshold ─
        double rawStress = Math.max(compStress, Math.max(tenStress, dynStress));
        double effectiveStress = rawStress / (thermalFactor * corrosionFactor);

        // ── Sustained-overload hysteresis ──────────────────────────────────────
        boolean overloaded = effectiveStress >= sd.failureThresholdFraction();
        int streak = dynamicTracker.updateOverloadStreak(pos, overloaded);
        boolean freshImpact = dynamicTracker.consumeImpactFlag(pos);
        boolean shouldFail = overloaded && (freshImpact || streak >= OVERLOAD_HOLD_EVALUATIONS);

        // ── Dispatch ─────────────────────────────────────────────────────────
        if (shouldFail) {
            if (!hasBfsBudget(level)) {
                // This tick's BFS budget is spent — defer to next tick rather
                // than run an unbounded-cost group failure right now. The
                // block is still overloaded; it'll be picked up again very
                // shortly (next tick's dirty drain), just not this instant.
                markDirty(level, pos);
                return;
            }
            Set<BlockPos> failSet = connectedFailureBFS(level, pos, sd.failureThresholdFraction());
            recordBfsUsage(level, failSet.size());
            for (BlockPos failPos : failSet) {
                exp.CCnewmods.misanthrope_world.physics.persist.CorrosionStateMap
                        .get(level).remove(failPos);
                dynamicTracker.remove(failPos);
            }
            FailureDispatcher.dispatchGroup(level, failSet, state, sd);
        } else if (effectiveStress >= sd.crackThresholdFraction()) {
            // Register or refresh a structural crack source
            float pressure = (float) ((effectiveStress - sd.crackThresholdFraction())
                    / (sd.failureThresholdFraction() - sd.crackThresholdFraction()) * 50f);
            pressure = Math.max(1f, pressure);
            String sourceId = "misanthrope_core:structural:" + pos.asLong();
            CrackPropagator.addSource(new StructuralCrackSource(pos, pressure, level.getGameTime(), sourceId));
        }
    }

    // ── pH corrosion ──────────────────────────────────────────────────────────

    /**
     * Reads the current ambient pH load at {@code pos}, advances the block's
     * stored corrosion accumulation by one evaluation's worth of exposure
     * (and self-repair, if any), persists the result to
     * {@link exp.CCnewmods.misanthrope_world.physics.persist.CorrosionStateMap},
     * and returns the resulting strength multiplier — the same shape as
     * {@link StructuralData#strengthFractionAt} for thermal weakening, so the
     * two factors combine the same way in {@link #evaluateBlock}.
     *
     * <p>Accumulation is a one-way ratchet by default
     * ({@code selfRepairFraction = 0.0}): once a block has corroded, it stays
     * corroded, mirroring how an existing structural crack only grows or fails
     * the block outright rather than healing on its own. Materials with a
     * non-zero {@code selfRepairFraction} (passivation layers, magical wards)
     * are the deliberate exception, not the rule.
     *
     * <p>Exposure load is read once per evaluation (every 32-per-tick dirty-queue
     * drain or background scan pass, same cadence as the rest of this class —
     * there is no separate corrosion tick loop), so accumulation rate is tied
     * to how often a block is actually re-evaluated, same as thermal weakening.
     */
    private static double updateAndGetCorrosionFactor(ServerLevel level, BlockPos pos,
                                                       BlockPhysicsData.PhReactivity ph) {
        float load = exp.CCnewmods.mge.grid.EnvironmentGrid.getPhLoad(level, pos);

        var tracker = exp.CCnewmods.misanthrope_world.physics.persist.CorrosionStateMap.get(level);
        float accumulation = tracker.get(pos);

        double delta;
        if (load > 0) {
            delta = load * ph.acidCorrosionRatePerLoad() * ph.resistanceFactor();
        } else if (load < 0) {
            delta = -load * ph.baseCorrosionRatePerLoad() * ph.resistanceFactor();
        } else {
            delta = 0.0;
        }

        accumulation += delta;
        if (ph.selfRepairFraction() > 0.0) {
            accumulation -= accumulation * ph.selfRepairFraction();
        }
        accumulation = (float) Math.max(0.0, Math.min(1.0, accumulation));

        if (accumulation != tracker.get(pos)) {
            tracker.set(pos, accumulation);
        }

        return Math.max(ph.minStrengthFraction(), ph.strengthFractionAt(accumulation));
    }

    // ── Column load ───────────────────────────────────────────────────────────

    /**
     * Sums the weight of all solid blocks in a downward column above {@code pos},
     * stopping at bedrock or a structural frame block (which re-routes the load).
     * <p>
     * Delegates to {@link CompressiveStressSimulator}, which persists results
     * in {@link exp.CCnewmods.misanthrope_world.physics.structural.grid.StressGrid}
     * instead of the tick-scoped memo cache this used before (see
     * {@code StressGrid_Design_v1.md}, Stage 2 → cutover). The tick-scoped
     * cache and its own walk implementation are gone — fully superseded, not
     * kept alongside this.
     *
     * @return load in kN (game-scaled)
     */
    static double computeColumnLoad(ServerLevel level, BlockPos pos) {
        if (!PerfSampler.ENABLED) {
            return CompressiveStressSimulator.effectiveLoad(level, WorldPos.fromBlockPos(pos));
        }
        long t0 = System.nanoTime();
        double result = CompressiveStressSimulator.effectiveLoad(level, WorldPos.fromBlockPos(pos));
        PerfSampler.record("structural.computeColumnLoad", System.nanoTime() - t0);
        return result;
    }

    // ── Span analysis (tensile) ───────────────────────────────────────────────

    /**
     * Air-cavity flood-fill span below {@code pos} — see
     * {@code TensileStressSimulator}'s doc for the exact shape (X/Z-radius
     * -bounded, Y-unbounded within a node cap, never explores upward).
     * Delegates to {@link TensileStressSimulator}, which persists results in
     * the stress grid (see {@code StressGrid_Design_v1.md}, Stage 3 →
     * cutover) instead of recomputing the full BFS on every call.
     */
    static double computeSpan(ServerLevel level, BlockPos pos) {
        if (!PerfSampler.ENABLED) {
            return TensileStressSimulator.effectiveSpan(level, WorldPos.fromBlockPos(pos));
        }
        long t0 = System.nanoTime();
        double result = TensileStressSimulator.effectiveSpan(level, WorldPos.fromBlockPos(pos));
        PerfSampler.record("structural.computeSpan", System.nanoTime() - t0);
        return result;
    }

    // ── Connected-failure BFS (Option B) ─────────────────────────────────────

    /**
     * BFS outward from {@code origin} collecting all contiguous blocks whose
     * effective stress exceeds {@code failThreshold}.
     * Stops at {@link #MAX_CONNECTED_FAILURE_RADIUS} blocks.
     *
     * <p>This gives the "collapse shape" — rather than one block popping, the
     * entire over-stressed mass fails as a unit. A stalactite falls whole; a
     * cliff sheet shears off together.
     */
    /**
     * BFS outward from {@code origin} collecting all contiguous blocks whose
     * effective stress exceeds {@code failThreshold}.
     * Stops at {@link #MAX_CONNECTED_FAILURE_RADIUS} blocks.
     *
     * <p>This gives the "collapse shape" — rather than one block popping, the
     * entire over-stressed mass fails as a unit. A stalactite falls whole; a
     * cliff sheet shears off together.
     *
     * <p>Terrain convenience overload — uses {@link #computeColumnLoad}, the
     * world-space (fixed +Y gravity) stress model. {@link ShipStressField}
     * uses the 4-arg overload with its own ship-local stress function
     * instead, since gravity isn't a fixed direction relative to a ship.
     */
    static Set<BlockPos> connectedFailureBFS(ServerLevel level, BlockPos origin,
                                             double failThreshold) {
        return connectedFailureBFS(level, origin, failThreshold, pos -> {
            StructuralData sd = BlockPhysicsRegistry.get(level.getBlockState(pos)).structural;
            if (sd == null) return 0.0;
            return computeColumnLoad(level, pos) / (sd.compressiveStrengthKpa() * FACE_AREA_M2);
        });
    }

    /**
     * Same BFS shape as the 3-arg overload, but with the compressive-stress
     * calculation supplied by the caller instead of hardcoded to
     * {@link #computeColumnLoad}. This is what actually makes the "shared
     * core, world and ship are thin callers" architecture work for group
     * failure specifically — the connectivity walk itself (visit neighbours,
     * respect the radius cap, collect the over-threshold set) doesn't care
     * whether "stress at this position" comes from a fixed-+Y column load or
     * a ship-local ray-march; only the stress source differs.
     *
     * @param stressFractionFn given a candidate position, returns its
     *                         compressive stress fraction (0 if it has no
     *                         structural data at all — the BFS already
     *                         filters those out before calling this, but a
     *                         defensive caller can still return 0)
     */
    static Set<BlockPos> connectedFailureBFS(ServerLevel level, BlockPos origin,
                                             double failThreshold,
                                             java.util.function.ToDoubleFunction<BlockPos> stressFractionFn) {
        if (!PerfSampler.ENABLED) {
            return connectedFailureBFSInner(level, origin, failThreshold, stressFractionFn);
        }
        long t0 = System.nanoTime();
        Set<BlockPos> result = connectedFailureBFSInner(level, origin, failThreshold, stressFractionFn);
        PerfSampler.record("structural.connectedFailureBFS", System.nanoTime() - t0);
        PerfSampler.record("structural.connectedFailureBFS.groupSize." + bucket(result.size()), 0);
        return result;
    }

    /** Buckets group size into a probe-name suffix so the log shows a rough
     *  histogram (1, 2-4, 5-16, 17+) instead of one line per distinct size. */
    private static String bucket(int size) {
        if (size <= 1) return "01";
        if (size <= 4) return "02-04";
        if (size <= 16) return "05-16";
        return "17+";
    }

    private static Set<BlockPos> connectedFailureBFSInner(ServerLevel level, BlockPos origin,
                                             double failThreshold,
                                             java.util.function.ToDoubleFunction<BlockPos> stressFractionFn) {
        Set<BlockPos> result = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        queue.add(origin);
        visited.add(origin.asLong());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (!level.isLoaded(pos)) continue;

            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            StructuralData sd = BlockPhysicsRegistry.get(state).structural;
            if (sd == null) continue;

            double compStress = stressFractionFn.applyAsDouble(pos);
            if (compStress >= failThreshold) {
                result.add(pos.immutable());
                // Propagate to neighbours within radius
                for (Direction d : Direction.values()) {
                    BlockPos next = pos.relative(d);
                    if (!visited.add(next.asLong())) continue;
                    int dist = (int) Math.sqrt(next.distSqr(origin));
                    if (dist <= MAX_CONNECTED_FAILURE_RADIUS) queue.add(next);
                }
            }
        }

        // Always include origin even if its stress somehow didn't pass
        result.add(origin.immutable());
        return result;
    }
}
