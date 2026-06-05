package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.StructuralData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 * <h3>Two scan modes (Option A + B)</h3>
 * <ul>
 *   <li><b>Option A — reactive:</b> Any block change ({@code BlockEvent.NeighborNotifyEvent})
 *       or thermal weakening notification enqueues the changed block and its
 *       immediate neighbours. Processed every tick from a {@link ConcurrentLinkedDeque}.</li>
 *   <li><b>Option B — ambient:</b> A slow background walk through loaded chunk
 *       sections re-evaluates {@link #BACKGROUND_BLOCKS_PER_TICK} randomly
 *       selected blocks per tick. This lets naturally generated structures
 *       (stalactites, cliff overhangs) fail over time even when nothing nearby
 *       changes. When a background-selected block fails, a
 *       {@linkplain #connectedFailureBFS connected-failure BFS} propagates the
 *       collapse to all contiguous over-stressed neighbours within
 *       {@link #MAX_CONNECTED_FAILURE_RADIUS} blocks.</li>
 * </ul>
 *
 * <h3>Stress model</h3>
 * <pre>
 *   compressiveStress = columnLoad_kN / (compressiveStrength_kPa × face_area_m2)
 *   tensileStress     = bendingMoment / (tensileStrength_kPa × section_modulus)
 *   thermalFactor     = StructuralData.strengthFractionAt(blockTemp)
 *   effectiveStress   = max(compressiveStress, tensileStress) / thermalFactor
 * </pre>
 * When {@code effectiveStress ≥ crackThresholdFraction}, an
 * {@link StructuralCrackSource} is registered with {@link CrackPropagator}.
 * When {@code effectiveStress ≥ failureThresholdFraction},
 * {@link FailureDispatcher#dispatch} is called.
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
     */
    public static final int MAX_SPAN_RADIUS = 16;

    /**
     * Background scan: blocks re-evaluated per tick across all loaded levels.
     */
    private static final int BACKGROUND_BLOCKS_PER_TICK = 4;

    /**
     * When a background failure fires, BFS radius for connected failure set.
     */
    public static final int MAX_CONNECTED_FAILURE_RADIUS = 8;

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

        // Option A: drain dirty queue
        ConcurrentLinkedDeque<BlockPos> dirty = DIRTY.get(level);
        if (dirty != null) {
            // Process up to 32 per tick to avoid spikes; remainder stays queued
            Set<BlockPos> processed = new HashSet<>();
            int budget = 32;
            while (!dirty.isEmpty() && budget-- > 0) {
                BlockPos pos = dirty.poll();
                if (pos == null || !processed.add(pos)) continue;
                evaluateBlock(level, pos, false);
            }
        }

        // Option B: background scan
        BackgroundScanner scanner = BACKGROUND.computeIfAbsent(level, BackgroundScanner::new);
        List<BlockPos> candidates = scanner.next(BACKGROUND_BLOCKS_PER_TICK);
        for (BlockPos pos : candidates) {
            evaluateBlock(level, pos, true); // true = allow connected-failure BFS
        }
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
     *
     * @param isBackground true if called from the Option B background scan;
     *                     enables connected-failure BFS propagation on failure.
     */
    private static void evaluateBlock(ServerLevel level, BlockPos pos, boolean isBackground) {
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

        // ── Thermal weakening factor ──────────────────────────────────────────
        float blockTemp = exp.CCnewmods.mge.grid.EnvironmentGrid.getTemperature(level, pos);
        double ambientTemp = 20.0;
        double tempC = Float.isNaN(blockTemp) ? ambientTemp : blockTemp;
        double thermalFactor = Math.max(0.001, sd.strengthFractionAt(tempC));

        // ── Effective stress [0, ∞) — normalized to [0,1] at failure threshold ─
        double rawStress = Math.max(compStress, tenStress);
        double effectiveStress = rawStress / thermalFactor;

        // ── Dispatch ─────────────────────────────────────────────────────────
        if (effectiveStress >= sd.failureThresholdFraction()) {
            if (isBackground) {
                // Propagate: find all connected over-stressed blocks and fail them together
                Set<BlockPos> failSet = connectedFailureBFS(level, pos, sd.failureThresholdFraction());
                FailureDispatcher.dispatchGroup(level, failSet, state, sd);
            } else {
                FailureDispatcher.dispatch(level, pos, state, sd);
            }
        } else if (effectiveStress >= sd.crackThresholdFraction()) {
            // Register or refresh a structural crack source
            float pressure = (float) ((effectiveStress - sd.crackThresholdFraction())
                    / (sd.failureThresholdFraction() - sd.crackThresholdFraction()) * 50f);
            pressure = Math.max(1f, pressure);
            String sourceId = "misanthrope_core:structural:" + pos.asLong();
            CrackPropagator.addSource(new StructuralCrackSource(pos, pressure, level.getGameTime(), sourceId));
        }
    }

    // ── Column load ───────────────────────────────────────────────────────────

    /**
     * Sums the weight of all solid blocks in a downward column above {@code pos},
     * stopping at bedrock or a structural frame block (which re-routes the load).
     *
     * @return load in kN (game-scaled)
     */
    static double computeColumnLoad(ServerLevel level, BlockPos pos) {
        double totalKN = 0.0;
        BlockPos cursor = pos.above();

        for (int i = 0; i < MAX_COLUMN_DEPTH; i++) {
            if (!level.isLoaded(cursor)) break;
            BlockState above = level.getBlockState(cursor);
            if (above.isAir()) break; // open sky — no load above this

            // ── External support check ────────────────────────────────────────
            // If this position has a registered external support (e.g. a hydraulic
            // cylinder), it carries up to its capacity and terminates the column.
            Double supportCapacityKN = EXTERNAL_SUPPORTS.get(cursor);
            if (supportCapacityKN != null) {
                // Add the support member's own weight (approximate: treat as steel beam)
                totalKN += 7800 * G_GAME; // steel density default
                // The support carries the load above — cap total at the remaining
                // load or support capacity, whichever is less, then stop walking.
                // If support capacity > accumulated load, everything above is carried
                // and the blocks below this pos see zero load from above.
                // If support capacity < accumulated load, excess passes through.
                if (totalKN <= supportCapacityKN) {
                    return 0.0; // cylinder carries all load — blocks below are stress-free
                } else {
                    totalKN -= supportCapacityKN; // partial relief: remainder passes through
                    break;
                }
            }
            // ── End external support check ────────────────────────────────────

            BlockPhysicsData d = BlockPhysicsRegistry.get(above);
            StructuralData sd = d.structural;

            if (sd != null) {
                totalKN += sd.densityKgM3() * G_GAME;
                if (sd.isStructuralFrame()) {
                    // Frame redistributes load laterally — this column terminates here
                    // but we add the frame's own contribution
                    break;
                }
            } else {
                // No structural data: use density from core data (stone default ~2400)
                totalKN += 2400 * G_GAME;
            }
            cursor = cursor.above();
        }

        // Lateral load transfer from structural frame neighbours
        for (Direction horizontal : new Direction[]{Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
            BlockPos neighbour = pos.relative(horizontal);
            if (!level.isLoaded(neighbour)) continue;
            BlockPhysicsData nd = BlockPhysicsRegistry.get(level.getBlockState(neighbour));
            if (nd.structural != null && nd.structural.isStructuralFrame()) {
                // Frame neighbours share the load — reduce effective column load
                totalKN *= (1.0 - 0.25 * nd.structural.loadTransferRange());
                totalKN = Math.max(0, totalKN);
            }
        }

        return totalKN;
    }

    // ── Span analysis (tensile) ───────────────────────────────────────────────

    /**
     * Estimates the unsupported horizontal span of the block at {@code pos}.
     * BFS fills the contiguous air space below, measures its width.
     *
     * @return span in blocks (0 if block is directly supported)
     */
    static double computeSpan(ServerLevel level, BlockPos pos) {
        // Quick check: if block below is solid, span = 0
        BlockPos below = pos.below();
        if (level.isLoaded(below) && !level.getBlockState(below).isAir()) return 0.0;

        // BFS the air space below
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(below);
        visited.add(below.asLong());

        int minX = below.getX(), maxX = below.getX();
        int minZ = below.getZ(), maxZ = below.getZ();
        int count = 0;

        while (!queue.isEmpty() && count < 400) {
            BlockPos cur = queue.poll();
            count++;
            minX = Math.min(minX, cur.getX());
            maxX = Math.max(maxX, cur.getX());
            minZ = Math.min(minZ, cur.getZ());
            maxZ = Math.max(maxZ, cur.getZ());

            for (Direction d : Direction.values()) {
                if (d == Direction.UP) continue; // don't go back up
                BlockPos next = cur.relative(d);
                if (!level.isLoaded(next)) continue;
                if (!visited.add(next.asLong())) continue;
                // Stop at radius limit
                if (Math.abs(next.getX() - pos.getX()) > MAX_SPAN_RADIUS) continue;
                if (Math.abs(next.getZ() - pos.getZ()) > MAX_SPAN_RADIUS) continue;
                if (level.getBlockState(next).isAir()) queue.add(next);
            }
        }

        double spanX = maxX - minX + 1;
        double spanZ = maxZ - minZ + 1;
        return Math.max(spanX, spanZ);
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
    static Set<BlockPos> connectedFailureBFS(ServerLevel level, BlockPos origin,
                                             double failThreshold) {
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

            // Quick stress check (re-use column + span but skip thermal for speed)
            double colLoad = computeColumnLoad(level, pos);
            double compStress = colLoad / (sd.compressiveStrengthKpa() * FACE_AREA_M2);
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
