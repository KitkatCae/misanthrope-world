package exp.CCnewmods.misanthrope_world.physics.structural.grid;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.StructuralData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import exp.CCnewmods.misanthrope_world.pos.WorldPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stage 2 of {@code StressGrid_Design_v1.md}: the compressive channel.
 * Replaces {@code StructuralStressField.computeColumnLoad}'s tick-scoped memo
 * cache with a genuinely persistent one, stored in {@link StressGrid}.
 * <p>
 * ── Faithfulness to the original ─────────────────────────────────────────────
 * {@link #rawVerticalLoad} replicates {@code computeColumnLoadInner}'s exact
 * vertical-walk semantics (same {@code MAX_COLUMN_DEPTH} bound, same
 * air/frame/external-support termination rules, same weight constants —
 * pulled directly from {@link StructuralStressField#gGame()} and
 * {@link StructuralStressField#getExternalSupportCapacity}, not duplicated).
 * The one addition is the fast path: if the position directly above already
 * has a cached, settled raw value, this reuses it instead of walking further —
 * see the class-level derivation below for why that's always safe to do.
 * <p>
 * The lateral structural-frame-neighbor adjustment from the original function
 * is deliberately NOT folded into the cached value — it's recomputed fresh on
 * every read via {@link #effectiveLoad}. It's cheap (4 neighbor checks, not a
 * column walk) and keeping it uncached means a frame being placed/broken
 * beside a column never needs to invalidate anything in the grid — only a
 * frame directly IN a column does. Simpler invalidation story for a small,
 * fixed cost paid on every read regardless.
 * <p>
 * ── Why the fast path is safe ─────────────────────────────────────────────────
 * {@code rawVerticalLoad(pos)} sums weight from {@code pos.above()} upward.
 * {@code rawVerticalLoad(pos.below())} starts its walk exactly one block
 * lower — at {@code pos} itself. So for a normal (non-terminating) block at
 * {@code pos}: {@code rawVerticalLoad(pos.below()) = weight(pos) + rawVerticalLoad(pos)}.
 * If {@code pos} terminates the walk itself (air, frame, external support),
 * the walk from {@code pos.below()} never reaches past {@code pos} anyway, so
 * {@code rawVerticalLoad(pos)}'s value is irrelevant to the result in those
 * cases — nothing unsafe about reading it, it's simply not used. Either way,
 * a cached value one cell up is always enough to finish the computation in
 * O(1) instead of re-walking the remaining column.
 * <p>
 * ── What does NOT get cached ──────────────────────────────────────────────────
 * A value produced because the walk hit an unloaded chunk section
 * (matching the original's {@code !level.isLoaded(cursor)} early-break) is
 * NOT written to the grid — it's a partial, potentially-wrong sum that would
 * otherwise persist incorrectly once that chunk actually loads. This mirrors
 * a gap already flagged in the design doc (chunk-load invalidation isn't
 * wired up yet) rather than solving it here; the practical effect is just
 * that such a position stays uncached and gets recomputed next time it's
 * actually needed, same cost the original tick-scoped cache already paid.
 */
public final class CompressiveStressSimulator {

    private CompressiveStressSimulator() {
    }

    private static final int MAX_COLUMN_DEPTH = StructuralStressField.MAX_COLUMN_DEPTH;

    private static final double STEEL_DENSITY_KG_M3 = 7800; // matches computeColumnLoadInner's support-member default
    private static final double DEFAULT_DENSITY_KG_M3 = 2400; // matches computeColumnLoadInner's no-structural-data default

    // ── Public reads ─────────────────────────────────────────────────────────

    /**
     * The frame-segmented raw vertical load at {@code pos}, in kN — same
     * value {@code computeColumnLoadInner} would return before the lateral
     * adjustment. Reads the grid cache first; computes and writes through on
     * a miss.
     */
    public static float rawVerticalLoad(ServerLevel level, WorldPos pos) {
        float cached = readCached(level, pos);
        if (StressSection.isSet(cached)) return cached;
        return computeAndCache(level, pos);
    }

    /**
     * Full compressive read matching {@code computeColumnLoad}'s complete
     * contract — raw vertical load plus the fresh (uncached) lateral
     * structural-frame-neighbor adjustment.
     */
    public static float effectiveLoad(ServerLevel level, WorldPos pos) {
        float raw = rawVerticalLoad(level, pos);
        return applyLateralAdjustment(level, pos, raw);
    }

    // ── Computation (grid-backed, with O(1) fast path) ──────────────────────

    private static float computeAndCache(ServerLevel level, WorldPos pos) {
        double totalKN = 0.0;
        WorldPos cursor = pos.above();
        double gGame = StructuralStressField.gGame();

        for (int i = 0; i < MAX_COLUMN_DEPTH; i++) {
            var blockPos = cursor.toBlockPos();
            if (!level.isLoaded(blockPos)) {
                // Partial sum — do NOT cache. See class doc.
                return (float) totalKN;
            }

            BlockState above = level.getBlockState(blockPos);
            if (above.isAir()) {
                writeCache(level, pos, (float) totalKN);
                return (float) totalKN;
            }

            Double supportCapacityKN = StructuralStressField.getExternalSupportCapacity(blockPos);
            if (supportCapacityKN != null) {
                totalKN += STEEL_DENSITY_KG_M3 * gGame;
                if (totalKN <= supportCapacityKN) {
                    writeCache(level, pos, 0.0f);
                    return 0.0f;
                } else {
                    totalKN -= supportCapacityKN;
                    writeCache(level, pos, (float) totalKN);
                    return (float) totalKN;
                }
            }

            BlockPhysicsData d = BlockPhysicsRegistry.get(above);
            StructuralData sd = d.structural;

            if (sd != null) {
                totalKN += sd.densityKgM3() * gGame;
                if (sd.isStructuralFrame()) {
                    writeCache(level, pos, (float) totalKN);
                    return (float) totalKN;
                }
            } else {
                totalKN += DEFAULT_DENSITY_KG_M3 * gGame;
            }

            // ── Fast path: cursor's own raw value, if already settled, finishes this in O(1). ──
            float cursorCached = readCached(level, cursor);
            if (StressSection.isSet(cursorCached)) {
                double result = totalKN + cursorCached;
                writeCache(level, pos, (float) result);
                return (float) result;
            }

            cursor = cursor.above();
        }

        // Hit MAX_COLUMN_DEPTH without terminating — matches the original's
        // behavior of just returning whatever accumulated so far. This IS
        // cached, same as the original treats it as a final answer.
        writeCache(level, pos, (float) totalKN);
        return (float) totalKN;
    }

    private static float applyLateralAdjustment(ServerLevel level, WorldPos pos, float rawKN) {
        double totalKN = rawKN;
        for (Direction horizontal : new Direction[]{Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
            var neighborBlockPos = pos.relative(horizontal).toBlockPos();
            if (!level.isLoaded(neighborBlockPos)) continue;
            BlockPhysicsData nd = BlockPhysicsRegistry.get(level.getBlockState(neighborBlockPos));
            if (nd.structural != null && nd.structural.isStructuralFrame()) {
                totalKN *= (1.0 - 0.25 * nd.structural.loadTransferRange());
                totalKN = Math.max(0, totalKN);
            }
        }
        return (float) totalKN;
    }

    // ── Invalidation (Stage 2: break/place only — see design doc staging) ──

    /**
     * Call when the block at {@code changedPos} has just changed (placed or
     * broken). Clears {@code changedPos}'s own cached value (it may no
     * longer be accurate for whatever's there now) and recomputes downward
     * from {@code changedPos.below()}, stopping as soon as a recomputed
     * value matches what was already cached there (propagation has settled —
     * nothing further down can differ) or {@link #MAX_COLUMN_DEPTH} is hit as
     * a safety cap, same bound the original column walk itself uses.
     */
    public static void invalidateAndPropagate(ServerLevel level, WorldPos changedPos) {
        clearCache(level, changedPos);

        WorldPos current = changedPos.below();
        for (int i = 0; i < MAX_COLUMN_DEPTH; i++) {
            float oldValue = readCached(level, current);
            float newValue = computeAndCache(level, current);
            if (StressSection.isSet(oldValue) && oldValue == newValue) {
                break; // settled — nothing further down changes
            }
            current = current.below();
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
        return section.getCompressive(lx, ly, lz);
    }

    private static void writeCache(ServerLevel level, WorldPos pos, float value) {
        StressSection section = StressGrid.getOrCreateSection(level, pos);
        int lx = (int) Math.floorMod(pos.x(), 16);
        int ly = (int) Math.floorMod(pos.y(), 16);
        int lz = (int) Math.floorMod(pos.z(), 16);
        section.setCompressive(lx, ly, lz, value);
    }

    private static void clearCache(ServerLevel level, WorldPos pos) {
        StressSection section = StressGrid.getSection(level, pos);
        if (section == null) return;
        int lx = (int) Math.floorMod(pos.x(), 16);
        int ly = (int) Math.floorMod(pos.y(), 16);
        int lz = (int) Math.floorMod(pos.z(), 16);
        section.clearCompressive(lx, ly, lz);
    }
}
