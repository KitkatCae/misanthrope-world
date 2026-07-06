package exp.CCnewmods.misanthrope_world.physics.sonicboom;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.events.PhysTickEvent;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Applies sonic-boom rattle forces (buffeting/shaking) to a ship after it
 * crosses Mach 1. Replaces MVSE's {@code ShipRattleAttachment} +
 * {@code RattlePhysicsListener} pair, which were registered but never
 * actually wired up to anything (see the class docs on those two — ported
 * faithfully as dead code in the earlier sonic-boom phase, now replaced
 * here with a working version).
 *
 * <h3>Why this replaces the VS2-attachment approach entirely</h3>
 * MVSE's design needed the physics thread to read live rattle state written
 * by the game thread, but {@code AttachmentHolder} (which is what
 * {@code getAttachment}/{@code setAttachment} come from) is only implemented
 * by {@code LoadedServerShip} — a game-thread-only interface. Neither
 * {@code Ship} nor {@code PhysShip} implement it (checked against this
 * project's actual VS2 jar). That's exactly why MVSE's
 * {@code PhysShipListenerHelper} resorted to reflecting into a private
 * {@code physicsListeners} field instead of using the attachment directly
 * from the physics thread.
 *
 * <p>There's a cleaner path that avoids needing physics-thread attachment
 * access at all: {@code VsCoreApi.getPhysTickEvent()} is a real, public,
 * verified event that fires once per physics tick per dimension, handing you
 * the {@link PhysLevel} directly — and {@code PhysLevel.getShipById(long)}
 * gives you the {@link PhysShip} for any ship ID you already know about. So
 * instead of a VS2 attachment, this class just keeps its own
 * {@code ConcurrentHashMap<Long, RattleState>} (same pattern already used by
 * {@code SoundBarrierHandler}'s own per-ship state map, and by
 * {@code TemporaryShipLifecycle}) — the game thread writes into it when a
 * boom fires, the physics-tick handler reads/advances it. No VS2 attachment
 * registration, no reflection, no separate listener object per ship.
 */
public final class RattlePhysicsController {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/RattlePhysicsController");

    private RattlePhysicsController() {
    }

    /** Per-ship rattle state. All fields read/written only through the map below. */
    private static final class RattleState {
        volatile int ticksRemaining;
        volatile int physTick;
        volatile int halfLifeTicks;
        volatile double amplitude;
        volatile double frequency;
        final Vector3d torqueAxis = new Vector3d(0, 0, 1);
        final Vector3d velDir = new Vector3d(0, -1, 0);
        volatile String dimensionId;
    }

    private static final Map<Long, RattleState> STATES = new ConcurrentHashMap<>();

    private static volatile boolean registered = false;

    /**
     * Registers the physics-tick listener. Safe to call multiple times —
     * only registers once. Call from mod setup.
     */
    public static void register() {
        if (registered) return;
        registered = true;
        try {
            ValkyrienSkiesMod.INSTANCE.getApi().getPhysTickEvent()
                    .on(RattlePhysicsController::onPhysTick);
            LOGGER.info("[RattlePhysicsController] Registered on VS2 PhysTickEvent");
        } catch (Exception e) {
            LOGGER.error("[RattlePhysicsController] Failed to register on VS2 phys tick event: {}", e.getMessage());
        }
    }

    /**
     * Starts (or restarts) a rattle event for the given ship. Call from the
     * game thread — this is what {@code SoundBarrierHandler.fireBoom} calls.
     *
     * @param shipId      the ship crossing Mach 1
     * @param dimensionId the dimension the ship is in (used to skip
     *                    irrelevant PhysLevels cheaply in {@link #onPhysTick})
     * @param speedMs     ship speed in m/s at the moment of crossing
     * @param machOneMs   Mach 1 speed in m/s (from config)
     * @param shipVelDir  normalised velocity direction at crossing
     * @param shipMass    ship mass in kg (from {@code ServerShip.getInertiaData().getShipMass()})
     * @param durationTicks how many physics ticks to rattle for
     */
    public static void trigger(long shipId, String dimensionId,
                                double speedMs, double machOneMs,
                                Vector3dc shipVelDir, double shipMass,
                                int durationTicks) {
        double mach = speedMs / machOneMs;

        // Same amplitude/frequency model as MVSE's ShipRattleAttachment.trigger —
        // preserving the original tuning exactly, just relocated.
        double massScale = Math.min(1.0, Math.log1p(shipMass / 10000.0) / Math.log1p(100.0));
        double amplitude = Math.min(mach * mach * 500.0 * massScale, 800000.0);
        double frequency = Math.min(0.5, 0.08 + mach * 0.04);

        Vector3d torqueAxis = new Vector3d();
        Vector3d worldUp = new Vector3d(0, 1, 0);
        new Vector3d(shipVelDir).cross(worldUp, torqueAxis);
        if (torqueAxis.lengthSquared() < 1e-6) {
            torqueAxis.set(1, 0, 0);
        } else {
            torqueAxis.normalize();
        }

        RattleState state = STATES.computeIfAbsent(shipId, k -> new RattleState());
        state.dimensionId = dimensionId;
        state.amplitude = amplitude;
        state.frequency = frequency;
        state.torqueAxis.set(torqueAxis);
        state.velDir.set(shipVelDir.x(), shipVelDir.y(), shipVelDir.z());
        state.ticksRemaining = durationTicks;
        state.halfLifeTicks = Math.max(10, durationTicks / 3);
        state.physTick = 0;

        register();
    }

    // ── Physics-thread tick handler ─────────────────────────────────────────────

    private static void onPhysTick(PhysTickEvent event) {
        if (STATES.isEmpty()) return;

        PhysLevel level = event.getWorld();
        String dimensionId = level.getDimension();

        for (Map.Entry<Long, RattleState> entry : STATES.entrySet()) {
            RattleState state = entry.getValue();
            if (!dimensionId.equals(state.dimensionId)) continue; // wrong dimension's PhysLevel this call

            int tick = state.physTick;
            if (tick >= state.ticksRemaining) {
                STATES.remove(entry.getKey(), state);
                continue;
            }

            PhysShip physShip = level.getShipById(entry.getKey());
            if (physShip == null) {
                // Ship not managed by this dimension (or gone) — stop tracking.
                STATES.remove(entry.getKey(), state);
                continue;
            }

            applyRattle(physShip, state, tick);
            state.physTick = tick + 1;
        }
    }

    private static void applyRattle(PhysShip physShip, RattleState state, int tick) {
        double phase = tick * state.frequency * Math.PI * 2.0;
        double decay = Math.exp(-(double) tick / Math.max(1, state.halfLifeTicks));
        double sinP = Math.sin(phase);
        double cosP = Math.cos(phase);
        double amp = state.amplitude * decay;

        // Torque (yaw/pitch oscillation)
        Vector3d torqueVec = new Vector3d(
                state.torqueAxis.x * sinP * amp,
                state.torqueAxis.y * sinP * amp,
                state.torqueAxis.z * sinP * amp);
        physShip.applyWorldTorque(torqueVec);

        // Linear force (fore-aft buffeting), 90 degrees out of phase with torque
        double linAmp = amp * 0.25 * cosP;
        Vector3d forceVec = new Vector3d(
                state.velDir.x * linAmp,
                state.velDir.y * linAmp,
                state.velDir.z * linAmp);

        Vector3dc comDc = physShip.getCenterOfMass();
        Vector3d com = new Vector3d(comDc.x(), comDc.y(), comDc.z());
        physShip.applyWorldForce(forceVec, com);
    }
}
