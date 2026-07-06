package exp.CCnewmods.misanthrope_world.physics.structural;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-block EMA-smoothed dynamic stress accumulator, plus sustained-overload
 * hysteresis — the shared core both world terrain and VS2 ships feed into.
 * <p>
 * ── Why this exists ──────────────────────────────────────────────────────────
 * {@link StructuralStressField}'s column-load/span analysis is a static
 * calculation — the same load every time a given block is evaluated, no
 * memory needed. Dynamic sources (shockwaves, kinetic/collision impulses,
 * eventually real-time contact forces) are the opposite: noisy, momentary,
 * and individually often too weak to matter, but capable of adding up. A
 * single shockwave tick that only stresses a wall to 40% of its failure
 * threshold shouldn't do anything by itself — three of those in quick
 * succession should.
 * <p>
 * This mirrors vox3D's {@code contactSmoothing} (EMA on contact forces) and
 * {@code overloadStreak}/{@code fractureHoldFrames} (a piece must stay
 * overloaded for N consecutive evaluations before it actually snaps, unless
 * it just took a genuine impact) — see the fracture handoff doc and
 * {@code b3F_stressPiece} for the reference model this is ported from.
 * <p>
 * ── Ship-aware key space ──────────────────────────────────────────────────────
 * A raw {@code BlockPos} isn't a safe key on its own once ships are involved:
 * a ship's blocks live at real, storage-space {@code BlockPos} coordinates
 * (see {@code ShipStressField}'s class doc for why that's true), and two
 * different ships — or a ship and unrelated world terrain — can each have a
 * live {@code CrackEntry}/tracked position at what numerically looks like
 * "the same" BlockPos from two completely different physical contexts.
 * Entries are keyed by {@link TrackedKey}, a {@code (shipId, posLong)} pair,
 * with {@code shipId == 0} reserved for plain world-space (no ship). All the
 * original world-only methods still exist as convenience overloads — nothing
 * that called this class before ships existed needs to change.
 * <p>
 * ── Lazy decay ────────────────────────────────────────────────────────────────
 * There is no per-tick sweep of tracked positions. Each entry remembers the
 * tick it was last touched; decay is applied on read, computed from elapsed
 * ticks since that touch.
 * <p>
 * ── Not persisted ─────────────────────────────────────────────────────────────
 * Unlike corrosion (a permanent one-way ratchet, NBT-backed via
 * {@code CorrosionStateMap}), dynamic stress is transient physics state that
 * dissipates on its own in seconds — there's nothing meaningful to save
 * across a server restart. Backed by a plain {@link ConcurrentHashMap} per
 * level, same pattern as {@link StructuralStressField}'s own
 * {@code EXTERNAL_SUPPORTS} registry.
 */
public final class DynamicStressTracker {

    /** Reserved shipId meaning "plain world space, not part of any ship." */
    public static final long WORLD_SPACE = 0L;

    /**
     * EMA blend factor applied to each new contribution, (0,1]. Higher =
     * more responsive to the latest hit, less smoothing of noise. Mirrors
     * vox3D's {@code contactSmoothing} tuning field.
     */
    private static final float SMOOTHING_ALPHA = 0.4f;

    /**
     * Per-tick multiplicative decay applied to the smoothed value between
     * touches. ~0.983 gives a roughly 40-tick (2 second) half-life — long
     * enough for a burst of repeated hits to meaningfully stack, short
     * enough that a single old hit doesn't linger forever.
     */
    private static final float DECAY_PER_TICK = 0.983f;

    /**
     * Below this, a decayed value is treated as zero and the entry is
     * dropped rather than kept around indefinitely at a negligible value.
     */
    private static final float MIN_TRACKED_VALUE = 0.01f;

    private static final Map<ServerLevel, DynamicStressTracker> TRACKERS = new ConcurrentHashMap<>();

    private final Map<TrackedKey, Entry> entries = new ConcurrentHashMap<>();
    private final ServerLevel level;

    private DynamicStressTracker(ServerLevel level) {
        this.level = level;
    }

    public static DynamicStressTracker get(ServerLevel level) {
        return TRACKERS.computeIfAbsent(level, DynamicStressTracker::new);
    }

    /** Compound key: which ship (or {@link #WORLD_SPACE}) a tracked position belongs to. */
    private record TrackedKey(long shipId, long posLong) {
    }

    /** Per-block tracked state. Mutable in place under the map's own concurrency. */
    private static final class Entry {
        volatile float smoothedStress;
        volatile long lastTouchedTick;
        volatile int overloadStreak;
        volatile boolean impactFlag;
    }

    // ── Writing contributions ────────────────────────────────────────────────

    /**
     * Blends a new dynamic stress contribution into {@code (shipId, pos)}'s
     * smoothed value via EMA, and marks the position dirty so
     * {@link StructuralStressField}'s reactive queue re-evaluates it with
     * this contribution folded in.
     *
     * @param shipId           {@link #WORLD_SPACE} for plain terrain, or the
     *                         owning ship's ID for a ship-local position
     * @param pos              affected block (ship-local storage coordinates
     *                         if {@code shipId != WORLD_SPACE})
     * @param rawStressFraction this contribution's stress fraction on its
     *                         own (same units as {@code StructuralData}'s
     *                         threshold fractions — 1.0 = at failure)
     * @param isImpact         true if this contribution is itself violent
     *                         enough to be treated as a fresh impact —
     *                         bypasses the sustained-overload hysteresis in
     *                         {@link StructuralStressField}/{@code ShipStressField}
     *                         the way a hard hit does in vox3D, rather than
     *                         needing several consecutive over-threshold
     *                         evaluations first. Sustained/ambient sources
     *                         should always pass false here.
     */
    public void addContribution(long shipId, BlockPos pos, float rawStressFraction, boolean isImpact) {
        long now = level.getGameTime();
        TrackedKey key = new TrackedKey(shipId, pos.asLong());
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            float decayed = decay(e.smoothedStress, e.lastTouchedTick, now);
            e.smoothedStress = decayed * (1f - SMOOTHING_ALPHA) + rawStressFraction * SMOOTHING_ALPHA;
            e.lastTouchedTick = now;
            if (isImpact) e.impactFlag = true;
        }
        if (shipId == WORLD_SPACE) {
            StructuralStressField.markDirty(level, pos.immutable());
        }
        // Ship-local positions have no reactive dirty-queue equivalent yet —
        // ShipStressField evaluates continuously (background scan), so a
        // fresh contribution is simply picked up on its next pass rather
        // than needing an explicit wake-up.
    }

    /** World-space convenience overload — see {@link #addContribution(long, BlockPos, float, boolean)}. */
    public void addContribution(BlockPos pos, float rawStressFraction, boolean isImpact) {
        addContribution(WORLD_SPACE, pos, rawStressFraction, isImpact);
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * Current smoothed dynamic stress at {@code (shipId, pos)}, decayed for
     * elapsed time since it was last touched. Zero if nothing has been
     * tracked here (or it has fully decayed away).
     */
    public float getSmoothedStress(long shipId, BlockPos pos) {
        TrackedKey key = new TrackedKey(shipId, pos.asLong());
        Entry e = entries.get(key);
        if (e == null) return 0f;
        float decayed = decay(e.smoothedStress, e.lastTouchedTick, level.getGameTime());
        if (decayed < MIN_TRACKED_VALUE) {
            entries.remove(key, e);
            return 0f;
        }
        return decayed;
    }

    /** World-space convenience overload. */
    public float getSmoothedStress(BlockPos pos) {
        return getSmoothedStress(WORLD_SPACE, pos);
    }

    /**
     * Advances and returns the sustained-overload streak for
     * {@code (shipId, pos)}: incremented if {@code overloaded} is true this
     * evaluation, reset to 0 otherwise. Call once per evaluation, after
     * deciding whether combined effective stress cleared the failure
     * threshold.
     */
    public int updateOverloadStreak(long shipId, BlockPos pos, boolean overloaded) {
        TrackedKey key = new TrackedKey(shipId, pos.asLong());
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            e.overloadStreak = overloaded ? e.overloadStreak + 1 : 0;
            return e.overloadStreak;
        }
    }

    /** World-space convenience overload. */
    public int updateOverloadStreak(BlockPos pos, boolean overloaded) {
        return updateOverloadStreak(WORLD_SPACE, pos, overloaded);
    }

    /**
     * Returns whether {@code (shipId, pos)} has a pending impact flag, and
     * clears it (consumed exactly once).
     */
    public boolean consumeImpactFlag(long shipId, BlockPos pos) {
        TrackedKey key = new TrackedKey(shipId, pos.asLong());
        Entry e = entries.get(key);
        if (e == null) return false;
        synchronized (e) {
            boolean flagged = e.impactFlag;
            e.impactFlag = false;
            return flagged;
        }
    }

    /** World-space convenience overload. */
    public boolean consumeImpactFlag(BlockPos pos) {
        return consumeImpactFlag(WORLD_SPACE, pos);
    }

    /**
     * Clears all tracked state for {@code (shipId, pos)} — call when the
     * block fails or is otherwise removed, so a fresh block placed here
     * later doesn't inherit a stale streak/smoothed value.
     */
    public void remove(long shipId, BlockPos pos) {
        entries.remove(new TrackedKey(shipId, pos.asLong()));
    }

    /** World-space convenience overload. */
    public void remove(BlockPos pos) {
        remove(WORLD_SPACE, pos);
    }

    /**
     * Clears every tracked entry belonging to {@code shipId} — call when a
     * ship is removed/disassembled so its entries don't linger forever (they
     * would eventually decay and self-evict anyway via {@link #MIN_TRACKED_VALUE},
     * but a ship that's gone for good has no reason to wait that out).
     * O(n) over currently-tracked entries; ships aren't removed often enough
     * for this to matter.
     */
    public void removeShip(long shipId) {
        entries.keySet().removeIf(k -> k.shipId() == shipId);
    }

    // ── Decay math ────────────────────────────────────────────────────────────

    private static float decay(float value, long lastTick, long now) {
        long elapsed = now - lastTick;
        if (elapsed <= 0) return value;
        if (elapsed > 400) return 0f; // long enough that pow() underflow is a waste of a call
        return (float) (value * Math.pow(DECAY_PER_TICK, elapsed));
    }
}
