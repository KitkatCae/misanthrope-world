package exp.CCnewmods.misanthrope_world.crackrender.client;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of CrackEntry objects, populated by CrackSyncPacket.
 * <p>
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * Uses ConcurrentHashMap so chunk compilation threads can read safely while
 * the main thread handles incoming sync packets. Writes (put/remove) happen
 * only on the main thread via packet handling. Reads happen on both the main
 * thread and chunk compile worker threads.
 * <p>
 * No locks are needed for reads because ConcurrentHashMap guarantees memory
 * visibility of the last completed write for any key.
 * <p>
 * ── Usage ─────────────────────────────────────────────────────────────────────
 * ChunkCrackInjectorMixin reads from this cache during compile() to know
 * which blocks in the current section need crack geometry injected.
 * <p>
 * ModelBlockRendererCrackMixin reads hasCrackOnFace() per block/face to decide
 * whether to suppress the vanilla BakedQuad output for that face.
 */
public final class ClientCrackCache {

    private ClientCrackCache() {
    }

    // Long key = BlockPos.asLong() for O(1) lookup
    private static final ConcurrentHashMap<Long, CrackEntry> CACHE = new ConcurrentHashMap<>();

    // ── Write API (main thread only) ──────────────────────────────────────────

    public static void put(CrackEntry entry) {
        CACHE.put(entry.pos().asLong(), entry);
    }

    public static void remove(BlockPos pos) {
        CACHE.remove(pos.asLong());
    }

    public static void clear() {
        CACHE.clear();
    }

    // ── Read API (any thread) ─────────────────────────────────────────────────

    @Nullable
    public static CrackEntry get(BlockPos pos) {
        return CACHE.get(pos.asLong());
    }

    public static boolean hasCracks(BlockPos pos) {
        CrackEntry e = CACHE.get(pos.asLong());
        return e != null && e.hasCracks();
    }

    /**
     * Called by the face suppression mixin to determine whether the vanilla
     * BakedQuad output for a specific face should be suppressed.
     * <p>
     * Returns true only if:
     * - The block has a crack entry in the cache
     * - At least one VeinSegment in that entry crosses the given face
     * <p>
     * face == null means "unculled quads" — never suppress those, they are
     * interior model geometry (cross geometry, etc.) that has no face normal.
     */
    public static boolean hasCrackOnFace(BlockPos pos, @Nullable Direction face) {
        if (face == null) return false;
        CrackEntry e = CACHE.get(pos.asLong());
        if (e == null || !e.hasCracks()) return false;
        return e.hasCrackOnFace(face);
    }

    /**
     * Returns all entries whose block positions fall within the 16³ section
     * at (sectionX, sectionY, sectionZ) in section coordinates.
     * <p>
     * Called by ChunkCrackInjectorMixin to get the set of blocks to inject
     * in one compile pass without iterating the full cache.
     */
    public static Collection<CrackEntry> getEntriesInSection(int sectionX,
                                                             int sectionY,
                                                             int sectionZ) {
        int minX = sectionX << 4, maxX = minX + 15;
        int minY = sectionY << 4, maxY = minY + 15;
        int minZ = sectionZ << 4, maxZ = minZ + 15;

        // ConcurrentHashMap.values() snapshot is safe for iteration
        java.util.List<CrackEntry> result = new java.util.ArrayList<>();
        for (CrackEntry entry : CACHE.values()) {
            BlockPos p = entry.pos();
            if (p.getX() >= minX && p.getX() <= maxX
                    && p.getY() >= minY && p.getY() <= maxY
                    && p.getZ() >= minZ && p.getZ() <= maxZ) {
                result.add(entry);
            }
        }
        return result;
    }

    public static int size() {
        return CACHE.size();
    }
}
