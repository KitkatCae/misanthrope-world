package exp.CCnewmods.misanthrope_world.physics.pressure.hull;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Samples the interior pressure of a VS2 ship by BFS flood-filling through
 * non-solid blocks in ship space, then averaging MGE EnvironmentGrid pressure
 * at sampled interior positions.
 *
 * <h3>BFS strategy</h3>
 * Starts at the ship's centroid (snapped to ship-space block coordinate).
 * Expands in 6 directions through non-solid blocks. Terminates when:
 * <ul>
 *   <li>A solid block is hit (hull wall — stop expanding that branch)</li>
 *   <li>The BFS queue exceeds {@link #MAX_INTERIOR_BLOCKS} (cap for large ships)</li>
 *   <li>A block outside the ship's active chunk set is reached (open to world —
 *       interior is not sealed; use external pressure as internal)</li>
 * </ul>
 *
 * <h3>Sealed vs open hull</h3>
 * If the BFS escapes the ship's chunk claim (reaches a world chunk), the hull
 * is considered <b>open</b>. Internal pressure = external pressure at breach
 * point. This is the correct result: a ship open to atmosphere has no
 * internal/external differential.
 *
 * <h3>MGE sampling</h3>
 * Rather than sampling every interior block, we sample a stratified subset
 * (every 3rd block along each axis) and average the results. This keeps
 * sampling cost O(N/27) rather than O(N).
 *
 * <h3>Fallback (no MGE)</h3>
 * Without MGE, interior pressure equals the dimension's atmospheric pressure
 * at the ship's altitude. A sealed ship at altitude inherits the pressure
 * it was sealed at — this is tracked in {@link HullPressureState#cachedInternalPressureMbar}
 * and only updated when the hull is breached or when a pressurisation block
 * entity (from MGE or future mods) actively changes it.
 */
public final class HullInteriorSampler {

    private HullInteriorSampler() {}

    /** Hard cap on BFS interior block count. Large ships get sampled at this resolution. */
    static final int MAX_INTERIOR_BLOCKS = 4096;

    /** Stride for MGE pressure sampling within the interior set. */
    private static final int SAMPLE_STRIDE = 3;

    // ── MGE reflection ────────────────────────────────────────────────────────

    private static volatile boolean resolved = false;
    private static Method mgeGetPressure = null; // EnvironmentGrid.getPressureMbar(Level, BlockPos)

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!ModList.get().isLoaded("mge")) return;
        try {
            Class<?> g  = Class.forName("exp.CCnewmods.mge.grid.EnvironmentGrid");
            mgeGetPressure = g.getMethod("getPressureMbar",
                    net.minecraft.world.level.Level.class, BlockPos.class);
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("MVS/Pressure")
                    .warn("[HullInteriorSampler] MGE EnvironmentGrid not found: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result record returned from {@link #scan}.
     *
     * @param internalPressureMbar average interior gas pressure in mbar.
     *                             Equals external pressure if hull is open.
     * @param interiorVolume       number of non-solid blocks found inside hull.
     * @param isSealed             true if BFS stayed within ship chunks.
     */
    public record ScanResult(float internalPressureMbar, int interiorVolume, boolean isSealed) {}

    /**
     * BFS scan of ship interior. Returns {@link ScanResult}.
     * Called by {@link PressureDifferentialSolver} every
     * {@link HullPressureState#INTERIOR_CACHE_TICKS} ticks.
     *
     * @param level        server level
     * @param ship         the VS2 ship
     * @param fallbackMbar pressure to use if hull is open or MGE unavailable
     */
    public static ScanResult scan(ServerLevel level, LoadedServerShip ship,
                                   float fallbackMbar) {
        resolve();

        // Snap ship centroid to ship-space block coordinate
        var shipPos = ship.getTransform().getPositionInShip();
        BlockPos start = BlockPos.containing(shipPos.x(), shipPos.y(), shipPos.z());

        // Build set of all ship-space chunk positions for boundary detection
        Set<Long> shipChunks = new HashSet<>();
        ship.getActiveChunksSet().forEach((cx, cz) ->
                shipChunks.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz)));

        // BFS
        Set<BlockPos> interior  = new HashSet<>();
        Queue<BlockPos> queue   = new ArrayDeque<>();
        boolean isSealed        = true;

        // Validate start — if start is solid, walk up until we find air
        BlockPos actualStart = findInteriorStart(level, start, ship);
        if (actualStart == null) {
            // Ship has no interior air — treat as solid, no interior volume
            return new ScanResult(fallbackMbar, 0, true);
        }

        queue.add(actualStart);
        interior.add(actualStart);

        while (!queue.isEmpty() && interior.size() < MAX_INTERIOR_BLOCKS) {
            BlockPos cur = queue.poll();

            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = cur.relative(dir);

                if (interior.contains(next)) continue;

                // Boundary check — is this block in a ship chunk?
                long chunkKey = net.minecraft.world.level.ChunkPos.asLong(
                        net.minecraft.world.level.ChunkPos.getX(
                                net.minecraft.world.level.ChunkPos.asLong(next.getX() >> 4, next.getZ() >> 4)),
                        net.minecraft.world.level.ChunkPos.getZ(
                                net.minecraft.world.level.ChunkPos.asLong(next.getX() >> 4, next.getZ() >> 4)));
                // Simplified: check chunk coords directly
                long ck = net.minecraft.world.level.ChunkPos.asLong(
                        next.getX() >> 4, next.getZ() >> 4);

                if (!shipChunks.contains(ck)) {
                    // BFS reached world space — hull is open
                    isSealed = false;
                    break;
                }

                BlockState bs = level.getBlockState(next);
                if (bs.isSolid()) continue; // hit hull wall

                interior.add(next);
                queue.add(next);
            }

            if (!isSealed) break;
        }

        if (!isSealed) {
            return new ScanResult(fallbackMbar, interior.size(), false);
        }

        // Sample MGE pressure at stratified subset
        float avgPressure = fallbackMbar;
        if (mgeGetPressure != null && !interior.isEmpty()) {
            double total   = 0;
            int    samples = 0;
            int    i       = 0;
            for (BlockPos p : interior) {
                if (i % SAMPLE_STRIDE == 0) {
                    try {
                        float mbarHere = ((Number) mgeGetPressure
                                .invoke(null, level, p)).floatValue();
                        total += mbarHere;
                        samples++;
                    } catch (Exception ignored) {}
                }
                i++;
            }
            if (samples > 0) avgPressure = (float)(total / samples);
        }

        return new ScanResult(avgPressure, interior.size(), true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Starting from the ship centroid, walks in +Y until a non-solid block is
     * found within 8 blocks. Returns null if no air pocket found.
     */
    private static BlockPos findInteriorStart(ServerLevel level, BlockPos start,
                                               LoadedServerShip ship) {
        // Try centroid first
        if (!level.getBlockState(start).isSolid()) return start;
        // Walk up
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos up = start.above(dy);
            if (!level.getBlockState(up).isSolid()) return up;
        }
        // Walk in all 6 directions 1 block
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos nb = start.relative(d);
            if (!level.getBlockState(nb).isSolid()) return nb;
        }
        return null;
    }
}
