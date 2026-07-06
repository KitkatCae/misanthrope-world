package exp.CCnewmods.misanthrope_world.physics.sonicboom;

import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipPhysicsListener;
import org.valkyrienskies.core.api.world.PhysLevel;

import javax.annotation.Nonnull;

/**
 * Physics-thread listener that applies sonic-boom rattle forces to a ship.
 *
 * <p>Not currently instantiated or attached to any ship anywhere in this
 * codebase — ported faithfully as-is from MVSE, where it had the same gap
 * (see {@code ShipRattleAttachment}'s class doc for the full picture: the
 * attachment IS registered with VS2, {@code SoundBarrierHandler.fireBoom()}
 * never calls {@code .trigger()} on it, and nothing ever constructs a
 * {@code RattlePhysicsListener} or attaches it to a ship's physics
 * representation). Flagging clearly rather than silently leaving it, but not
 * wiring it up unasked since that would mean inventing an unverified
 * ship-physics-listener attachment mechanism — exactly the kind of guess
 * that caused the broken {@code ShipImpulseApplier} reflection earlier.
 *
 * <h3>Force model (per physics tick)</h3>
 * <pre>
 *   phase     = physTick × frequency × 2π
 *   decay     = exp(-physTick / halfLifeTicks)
 *   torque    = amplitude × sin(phase)          × decay  [world-space, along torqueAxis]
 *   linForce  = amplitude × 0.25 × cos(phase)   × decay  [world-space, along velDir]
 * </pre>
 *
 * Both components decay exponentially so the rattle dies out naturally.
 * The torque creates yaw/pitch oscillation visible to riders.
 * The linear force creates the fore-aft "buffeting" felt in the gut.
 *
 * <h3>Accessing the attachment</h3>
 * The {@link ShipRattleAttachment} lives on the game thread ({@code LoadedServerShip}).
 * We can't call {@code getAttachment()} from here. Instead we keep a direct
 * reference set when this listener is registered — the game thread writes to
 * the same object's volatile fields, which are safe to read here due to VS2's
 * tick-boundary memory ordering.
 */
public final class RattlePhysicsListener implements ShipPhysicsListener {

    /** Direct reference set at registration time. Game thread writes, physics thread reads. */
    private final ShipRattleAttachment attachment;

    // Reusable vectors to avoid allocation on the physics thread
    private final Vector3d torqueVec  = new Vector3d();
    private final Vector3d forceVec   = new Vector3d();

    public RattlePhysicsListener(ShipRattleAttachment attachment) {
        this.attachment = attachment;
    }

    @Override
    public void physTick(@Nonnull PhysShip physShip, @Nonnull PhysLevel physLevel) {
        if (!attachment.active) return;

        int tick = attachment.physTick;
        if (tick >= attachment.ticksRemaining) {
            attachment.deactivate();
            return;
        }

        // ── Compute phase and decay ───────────────────────────────────────────
        double phase = tick * attachment.frequency * Math.PI * 2.0;
        double decay = Math.exp(-(double) tick / Math.max(1, attachment.halfLifeTicks));
        double sinP  = Math.sin(phase);
        double cosP  = Math.cos(phase);

        double amp   = attachment.amplitude * decay;

        // ── Torque (yaw/pitch oscillation) ────────────────────────────────────
        // Applied about torqueAxis (perpendicular to velocity, horizontal plane)
        Vector3d axis = attachment.torqueAxis;
        torqueVec.set(axis.x * sinP * amp,
                      axis.y * sinP * amp,
                      axis.z * sinP * amp);
        physShip.applyWorldTorque(torqueVec);

        // ── Linear force (fore-aft buffeting) ─────────────────────────────────
        // Smaller magnitude, 90° out of phase with torque for a natural feel
        Vector3d vel = attachment.velDir;
        double linAmp = amp * 0.25 * cosP;
        forceVec.set(vel.x * linAmp,
                     vel.y * linAmp,
                     vel.z * linAmp);

        // Apply at the CoM (world position) for pure translation, no extra torque
        org.joml.Vector3dc comDc = physShip.getCenterOfMass();
        Vector3d com = new Vector3d(comDc.x(), comDc.y(), comDc.z());
        physShip.applyWorldForce(forceVec, com);

        // ── Advance tick ──────────────────────────────────────────────────────
        attachment.physTick = tick + 1;
    }
}
