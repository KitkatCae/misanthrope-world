package exp.CCnewmods.misanthrope_world.physics.sonicboom;

/**
 * Per-ship mutable state for the sound barrier system.
 *
 * <p>Held in a {@code ConcurrentHashMap<Long, SoundBarrierState>} keyed by ship ID.
 * All fields are accessed on the server game thread only.</p>
 */
public final class SoundBarrierState {

    /** Whether this ship was supersonic on the previous tick. */
    public boolean wasSonic = false;

    /**
     * Ticks remaining on the post-boom cooldown.
     * Prevents repeated boom events when a ship hovers just above Mach 1.
     */
    public int cooldownTicks = 0;

    /**
     * Last recorded speed in m/s (= blocks/s in VS2 units).
     * Used to compute the crossing direction (rising or falling through Mach 1).
     */
    public double lastSpeedMs = 0.0;

    /**
     * Last known world-space position and dimension. Needed by the
     * vanish-while-supersonic effect (see SoundBarrierHandler.serverTick's
     * pruning pass) since by the time we notice a ship is gone, there's no
     * ship object left to ask for its position.
     */
    public net.minecraft.world.phys.Vec3 lastPos = net.minecraft.world.phys.Vec3.ZERO;
    public String lastDimensionId = null;
}
