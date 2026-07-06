package exp.CCnewmods.misanthrope_world.physics.reentry;

import org.joml.Vector3d;

/**
 * Per-ship state for the kinetic heating system.
 *
 * <p>Tracks the current heating intensity and velocity direction so the client
 * can render the plasma trail correctly without recalculating on each packet.</p>
 */
public final class KineticHeatingState {

    // ── Heating ───────────────────────────────────────────────────────────────

    /**
     * Current heating intensity in [0, 1].
     * 0 = no heating, 1 = max Mach (full plasma disintegration tier).
     * Smoothed toward the target each tick to prevent instant jumps.
     */
    public float intensity = 0f;

    /**
     * Target intensity computed this tick from current speed.
     * {@link #intensity} lerps toward this.
     */
    public float targetIntensity = 0f;

    /**
     * Last known velocity direction (unit vector, world-space).
     * Used for leading-edge position computation and client trail direction.
     */
    public final Vector3d velDir = new Vector3d(0, -1, 0);

    /**
     * Current ship speed in m/s.  Cached for packet serialisation.
     */
    public double speedMs = 0.0;

    /**
     * Mach number this tick.
     */
    public double mach = 0.0;

    // ── Client sync throttle ──────────────────────────────────────────────────

    /** Ticks since last client sync packet was sent. */
    public int ticksSinceSync = 0;

    // ── Penetration depth (scales with intensity) ─────────────────────────────

    /**
     * How many block-layers deep the heating front currently reaches.
     * Increases with intensity, capped by config.
     */
    public int currentDepth = 1;

    // ── Lerp constant ─────────────────────────────────────────────────────────

    /** Fraction intensity moves toward target per tick. */
    public static final float LERP_RATE = 0.08f;
    /** Fraction intensity decays toward zero when below onset Mach. */
    public static final float DECAY_RATE = 0.12f;
}
