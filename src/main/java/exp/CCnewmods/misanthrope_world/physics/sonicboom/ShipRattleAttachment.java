package exp.CCnewmods.misanthrope_world.physics.sonicboom;

import org.joml.Vector3d;

/**
 * Per-ship transient attachment tracking sonic-boom rattle state.
 *
 * <p>Registered with VS2's attachment system in {@code commonSetup}:
 * <pre>
 *   VsCoreApi api = ...; // obtained via your VS2 setup
 *   api.registerAttachment(
 *       api.newAttachmentRegistrationBuilder(ShipRattleAttachment.class)
 *          .useTransientSerializer()
 *          .build());
 * </pre>
 *
 * <p>Written on the <b>game thread</b> by {@link SoundBarrierHandler} when
 * a boom fires. Read on the <b>physics thread</b> by
 * {@link RattlePhysicsListener} via the {@code physicsListeners} field
 * via a direct reference set at listener-construction time (see RattlePhysicsListener - though note that class is currently never instantiated anywhere, so this reflection path never actually runs in practice).
 *
 * <h3>Thread safety</h3>
 * All fields are {@code volatile}. The physics thread reads them; the game
 * thread writes them. VS2 guarantees that attachment reads/writes don't race
 * with physics ticks (VS2 uses a happens-before fence at tick boundaries).
 *
 * <h3>Rattle model</h3>
 * Rattle is a decaying sinusoidal torque + linear force applied each physics tick.
 * <pre>
 *   torque   = amplitude × sin(tick × frequency × 2π) × decay(tick)
 *   linForce = amplitude × 0.3 × sin(tick × frequency × 2π + π/2) × decay(tick)
 *   decay    = exp(−tick / halfLifeTicks)
 * </pre>
 * Both torque and linear force are in world space. The torque axis is
 * perpendicular to the velocity direction at crossing time, producing a
 * pitch/yaw oscillation. The linear force is along the velocity axis,
 * producing fore-aft buffeting.
 */
public final class ShipRattleAttachment {

    // ── Rattle state (written by game thread, read by physics thread) ─────────

    /** True while the rattle is active. Physics listener checks this first. */
    public volatile boolean active = false;

    /** Remaining physics ticks of rattle. Decremented by physics listener. */
    public volatile int ticksRemaining = 0;

    /** Peak torque magnitude in N·m (game-scaled). */
    public volatile double amplitude = 0.0;

    /**
     * Oscillation frequency in cycles per physics tick.
     * Mach 1 → ~0.12 Hz (slow shudder)
     * Mach 5 → ~0.40 Hz (rapid vibration)
     */
    public volatile double frequency = 0.15;

    /**
     * Torque axis (world space, unit vector).
     * Perpendicular to the ship's velocity at crossing time.
     * Used by the physics listener to orient the oscillating torque.
     */
    public final Vector3d torqueAxis = new Vector3d(0, 0, 1);

    /**
     * Velocity direction at crossing (world space, unit vector).
     * Used for the fore-aft linear buffeting component.
     */
    public final Vector3d velDir = new Vector3d(0, -1, 0);

    /** Half-life in physics ticks for exponential decay. */
    public volatile int halfLifeTicks = 40;

    // ── Internal physics-thread counter ──────────────────────────────────────

    /** Physics tick counter — incremented by the listener, not the game thread. */
    public volatile int physTick = 0;

    // ── Game-thread API ───────────────────────────────────────────────────────

    /**
     * Called by {@link SoundBarrierHandler} to trigger a new rattle event.
     * Safe to call from the game thread.
     *
     * @param speedMs     ship speed in m/s at the moment of crossing
     * @param machOneMs   Mach 1 speed in m/s (from config)
     * @param shipVelDir  normalised velocity direction at crossing
     * @param shipMass    ship mass in kg (from VS2 physics buffer)
     * @param durationTicks how many physics ticks to rattle for
     */
    public void trigger(double speedMs, double machOneMs,
                        Vector3d shipVelDir, double shipMass,
                        int durationTicks) {
        double mach = speedMs / machOneMs;

        // Amplitude: scales with mach number and ship mass.
        // At Mach 1: modest shudder. At Mach 5+: violent.
        // Capped so extremely massive ships still shake noticeably.
        double massScale = Math.min(1.0, Math.log1p(shipMass / 10000.0) / Math.log1p(100.0));
        this.amplitude = Math.min(mach * mach * 500.0 * massScale, 800000.0);

        // Frequency: higher Mach = higher frequency oscillation
        this.frequency = Math.min(0.5, 0.08 + mach * 0.04);

        // Torque axis: perpendicular to velocity in the horizontal plane
        // Use cross(velDir, world_up) for a yaw/pitch axis
        Vector3d worldUp = new Vector3d(0, 1, 0);
        shipVelDir.cross(worldUp, this.torqueAxis);
        if (this.torqueAxis.lengthSquared() < 1e-6) {
            // Velocity is straight up/down — use X as fallback axis
            this.torqueAxis.set(1, 0, 0);
        } else {
            this.torqueAxis.normalize();
        }

        this.velDir.set(shipVelDir);
        this.ticksRemaining = durationTicks;
        this.halfLifeTicks  = Math.max(10, durationTicks / 3);
        this.physTick       = 0;
        this.active         = true;
    }

    /** Deactivates the rattle. Called when ticksRemaining reaches 0. */
    public void deactivate() {
        active         = false;
        ticksRemaining = 0;
        physTick       = 0;
    }
}
