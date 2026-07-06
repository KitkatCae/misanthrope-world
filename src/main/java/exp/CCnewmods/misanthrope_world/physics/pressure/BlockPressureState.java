package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;

/**
 * Server-side pressure physics state for a single block boundary.
 *
 * <p>One instance exists in the owning handler's position-keyed map for every
 * boundary block that has been exposed to a non-trivial pressure differential.
 * Blocks that have never been stressed are absent from the map (saves memory).
 *
 * <p>This record is context-agnostic — it is used by both
 * {@link WorldSpacePressureHandler} (world-space blocks) and
 * {@code HullPressureState} in MVSE (VS2 ship hull blocks). The handler
 * provides the appropriate external/internal pressure readings; this class
 * only tracks the accumulated state.
 *
 * <h3>Deformation stages</h3>
 * <ol>
 *   <li>{@code deformationStage == 0} — undeformed (or elastic only)</li>
 *   <li>Stage advances when {@code stageTick} exceeds the block's
 *       {@code stage_pause_ticks} while ΔP is in the plastic region.</li>
 *   <li>An inter-stage tension pause ({@link #inStagePause}) separates each
 *       advance, building dread before the next crunch.</li>
 *   <li>When stage reaches {@code deformationStageCount}, the block breaches.</li>
 * </ol>
 */
public final class BlockPressureState {

    // ── Plastic deformation stage ─────────────────────────────────────────────

    /** Current deformation stage [0 = undeformed, N = stage N]. */
    public int deformationStage = 0;

    /**
     * Ticks accumulated in the current stage while ΔP is in the plastic region.
     * Stage advances when this reaches {@code PressureData.stagePauseTicks}.
     */
    public int stageTick = 0;

    /**
     * Whether we are currently in the inter-stage tension pause.
     * During the pause, {@link #pauseTicksRemaining} counts down but
     * {@link #deformationStage} does not change.
     */
    public boolean inStagePause = false;

    /** Ticks remaining in the current tension pause. */
    public int pauseTicksRemaining = 0;

    // ── Elastic deformation ───────────────────────────────────────────────────

    /**
     * Elastic deformation magnitude — signed, positive = outward (expansion
     * under internal > external), negative = inward (crush under external > internal).
     * Range: [−0.5, 0.5] blocks. Proportional to ΔP within the elastic region;
     * decays back toward 0 when ΔP drops below elastic yield.
     */
    public float elasticDeformAmount = 0f;

    // ── Inflation (inflatable blocks only) ────────────────────────────────────

    /**
     * Current inflation fraction [0, 1] for inflatable blocks.
     * 0 = original shape. 1 = fully inflated (nominally occupying the adjacent cell).
     * Server uses this to compute expanded volume contribution to the interior.
     * Inflation is reversible when ΔP drops.
     */
    public float inflationFraction = 0f;

    // ── Fatigue ───────────────────────────────────────────────────────────────

    /**
     * Accumulated fatigue stress in the elastic region [0, ∞).
     * Grows slowly when {@link #elasticDeformAmount} is non-zero, even below
     * plastic yield. When it crosses
     * {@code crackThresholdFraction × ultimateStrengthMbar}, a
     * {@link PressureCrackSource} is registered.
     */
    public float fatigueStress = 0f;

    /** True if a {@link PressureCrackSource} is currently active for this block. */
    public boolean crackSourceActive = false;

    // ── Network change-detection ──────────────────────────────────────────────

    /** Last ΔP value applied to this block (signed: external − internal). */
    public float lastDeltaMbar = 0f;

    /** Last visual deform amount sent to clients (NaN = never sent). */
    public float lastSentElasticDeform = Float.NaN;
    /** Last inflation fraction sent to clients. */
    public float lastSentInflation     = Float.NaN;
    /** Last stage index sent to clients (−1 = never sent). */
    public int   lastSentStage         = -1;

    // ── Dirty flag (for hybrid event/interval re-evaluation) ─────────────────

    /**
     * Set by {@link WorldSpacePressureHandler} when a neighbour block-update
     * event fires near this position. Causes the handler to re-evaluate
     * external/internal pressure on the next tick even if the block is in the
     * SAFE region. Cleared after evaluation.
     */
    public boolean pressureDirty = false;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the total visual deformation for the client — elastic component
     * plus a per-stage plastic offset. Positive = outward, negative = inward.
     * Units: fraction of one block.
     */
    public float totalVisualDeform(BlockPhysicsData.PressureData pd) {
        float plasticOffset = deformationStage
                * (0.12f / Math.max(1, pd.deformationStageCount()));
        return elasticDeformAmount
                + (elasticDeformAmount >= 0 ? plasticOffset : -plasticOffset);
    }

    /**
     * Returns {@code true} if this state is trivially idle — no deformation,
     * no fatigue, no crack source, and not dirty. Safe to remove from the
     * handler's map to reclaim memory.
     */
    public boolean isIdle() {
        return deformationStage == 0
                && Math.abs(elasticDeformAmount) < 0.001f
                && inflationFraction < 0.001f
                && fatigueStress < 0.001f
                && !crackSourceActive
                && !pressureDirty;
    }
}
