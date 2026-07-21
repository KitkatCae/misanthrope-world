package exp.CCnewmods.misanthrope_world.physics.structural.grid;

import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import exp.CCnewmods.misanthrope_world.pos.WorldPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Stage 3 of {@code StressGrid_Design_v1.md}: the tensile channel.
 * <p>
 * ── This is NOT shaped like the compressive channel ──────────────────────────
 * {@code computeSpanInner} (verified against its real body, not reconstructed
 * from memory) isn't a running sum along an axis — it's a flood fill of the
 * connected air cavity below {@code pos}, bounded in X/Z by
 * {@link MisWorldConfig#stressGridTensileRadius()} (centered on the QUERYING
 * position, not on the cavity), unbounded in Y except by a 400-node budget,
 * and it never explores upward. The result is the cavity's X/Z bounding-box
 * size as seen from {@code pos}'s own radius window.
 * <p>
 * That per-query-position radius window means two different positions above
 * the very same physical air cavity can legitimately get different span
 * results — so unlike compressive load, there's no clean way to decompose
 * this into "reuse the neighbor's cached value." Each position's result is
 * cached independently, as a plain scalar, once computed. Real value still —
 * repeated reads of the same position (common: multiple evaluateBlock calls
 * checking the same neighborhood across ticks) become O(1) instead of
 * re-running a 400-node BFS every time — just not the same O(1)-from-neighbor
 * trick the compressive channel gets.
 * <p>
 * ── Invalidation is conservative on purpose ──────────────────────────────────
 * Precisely determining which cached positions a single block change could
 * affect would mean tracking which cavities currently reach through that
 * position — real reachability-index territory, well beyond what this stage
 * needs. Instead: a block change clears every cached tensile entry in a cube
 * of ±{@code stressGridTensileRadius} around it (in X, Y, and Z). This is a
 * conservative superset of what the original BFS shape could actually make
 * stale — the true affected set is X/Z-bounded by the radius but Y-unbounded
 * within the 400-node cap, so a change deep in a tall connected cavern could
 * in principle affect a cached span outside this cube. That's an accepted,
 * flagged gap (see design doc's "explicitly deferred" list) rather than a
 * silent one: the practical effect is a stale span value in a rare, deep,
 * already-unstable-looking cavern, self-correcting on that position's next
 * natural cache eviction/recompute — not a crash, not a systemic error.
 * <p>
 * Invalidation is batched by section (not by individual position) to avoid
 * paying a map lookup per position in what can be a ~30,000+ cell cube at the
 * default radius — see {@link #invalidateRegion}.
 */
public final class TensileStressSimulator {

    private TensileStressSimulator() {
    }

    private static final int MAX_BFS_NODES = 400; // matches computeSpanInner's own cap exactly

    // ── Public reads ─────────────────────────────────────────────────────────

    /** Cached tensile span read; computes and writes through on a miss. */
    public static float effectiveSpan(ServerLevel level, WorldPos pos) {
        float cached = readCached(level, pos);
        if (StressSection.isSet(cached)) return cached;
        return computeAndCache(level, pos);
    }

    // ── Computation ──────────────────────────────────────────────────────────

    private static float computeAndCache(ServerLevel level, WorldPos pos) {
        WorldPos below = pos.below();
        var belowBlockPos = below.toBlockPos();

        if (level.isLoaded(belowBlockPos) && !level.getBlockState(belowBlockPos).isAir()) {
            writeCache(level, pos, 0.0f);
            return 0.0f;
        }

        int radius = MisWorldConfig.stressGridTensileRadius();

        Queue<WorldPos> queue = new ArrayDeque<>();
        Set<WorldPos> visited = new HashSet<>();
        queue.add(below);
        visited.add(below);

        long minX = below.x(), maxX = below.x();
        long minZ = below.z(), maxZ = below.z();
        int count = 0;

        while (!queue.isEmpty() && count < MAX_BFS_NODES) {
            WorldPos cur = queue.poll();
            count++;
            minX = Math.min(minX, cur.x());
            maxX = Math.max(maxX, cur.x());
            minZ = Math.min(minZ, cur.z());
            maxZ = Math.max(maxZ, cur.z());

            for (Direction d : Direction.values()) {
                if (d == Direction.UP) continue; // never explore back upward — matches original
                WorldPos next = cur.relative(d);
                var nextBlockPos = next.toBlockPos();
                if (!level.isLoaded(nextBlockPos)) continue;
                if (!visited.add(next)) continue;
                // Radius bound centered on the QUERYING position, X/Z only — matches original exactly
                if (Math.abs(next.x() - pos.x()) > radius) continue;
                if (Math.abs(next.z() - pos.z()) > radius) continue;
                if (level.getBlockState(nextBlockPos).isAir()) queue.add(next);
            }
        }

        double spanX = maxX - minX + 1;
        double spanZ = maxZ - minZ + 1;
        float result = (float) Math.max(spanX, spanZ);
        writeCache(level, pos, result);
        return result;
    }

    // ── Invalidation (Stage 3: break/place only — see design doc staging) ──

    /**
     * Clears cached tensile entries in a ±{@code stressGridTensileRadius}
     * cube around {@code changedPos}. See class doc for why this is a
     * deliberately conservative approximation rather than precise
     * reachability tracking.
     */
    public static void invalidateRegion(ServerLevel level, WorldPos changedPos) {
        int radius = MisWorldConfig.stressGridTensileRadius();

        long minX = changedPos.x() - radius, maxX = changedPos.x() + radius;
        long minY = changedPos.y() - radius, maxY = changedPos.y() + radius;
        long minZ = changedPos.z() - radius, maxZ = changedPos.z() + radius;

        long minSectionX = Math.floorDiv(minX, 16), maxSectionX = Math.floorDiv(maxX, 16);
        long minSectionY = Math.floorDiv(minY, 16), maxSectionY = Math.floorDiv(maxY, 16);
        long minSectionZ = Math.floorDiv(minZ, 16), maxSectionZ = Math.floorDiv(maxZ, 16);

        for (long sx = minSectionX; sx <= maxSectionX; sx++) {
            for (long sy = minSectionY; sy <= maxSectionY; sy++) {
                for (long sz = minSectionZ; sz <= maxSectionZ; sz++) {
                    // A representative position inside this section — only used to
                    // resolve the section itself, not as a per-cell coordinate.
                    WorldPos sectionOrigin = new WorldPos(sx * 16, sy * 16, sz * 16);
                    StressSection section = StressGrid.getSection(level, sectionOrigin);
                    if (section == null) continue; // never allocated — nothing to clear, no need to allocate now

                    long sectionMinX = sx * 16, sectionMinY = sy * 16, sectionMinZ = sz * 16;
                    int loX = (int) Math.max(0, minX - sectionMinX);
                    int hiX = (int) Math.min(15, maxX - sectionMinX);
                    int loY = (int) Math.max(0, minY - sectionMinY);
                    int hiY = (int) Math.min(15, maxY - sectionMinY);
                    int loZ = (int) Math.max(0, minZ - sectionMinZ);
                    int hiZ = (int) Math.min(15, maxZ - sectionMinZ);

                    for (int lx = loX; lx <= hiX; lx++) {
                        for (int ly = loY; ly <= hiY; ly++) {
                            for (int lz = loZ; lz <= hiZ; lz++) {
                                section.clearTensile(lx, ly, lz);
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Grid read/write helpers ──────────────────────────────────────────────

    private static float readCached(ServerLevel level, WorldPos pos) {
        StressSection section = StressGrid.getSection(level, pos);
        if (section == null) return StressSection.UNSET;
        return readLocal(section, pos);
    }

    private static float readLocal(StressSection section, WorldPos pos) {
        int lx = (int) Math.floorMod(pos.x(), 16);
        int ly = (int) Math.floorMod(pos.y(), 16);
        int lz = (int) Math.floorMod(pos.z(), 16);
        return section.getTensile(lx, ly, lz);
    }

    private static void writeCache(ServerLevel level, WorldPos pos, float value) {
        StressSection section = StressGrid.getOrCreateSection(level, pos);
        int lx = (int) Math.floorMod(pos.x(), 16);
        int ly = (int) Math.floorMod(pos.y(), 16);
        int lz = (int) Math.floorMod(pos.z(), 16);
        section.setTensile(lx, ly, lz, value);
    }
}
