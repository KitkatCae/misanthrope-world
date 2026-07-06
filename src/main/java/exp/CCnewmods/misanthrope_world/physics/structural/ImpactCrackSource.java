package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * One-shot {@link ICrackSourceProvider} for a ship-terrain impact.
 *
 * <p>Registered by {@link ImpactFractureBridge} when a VS2 collision exceeds
 * the impact speed threshold. Uses {@link CrackCause#IMPACT} — radiating,
 * spiderweb-pattern cracks, matching the cause's own doc comment describing
 * exactly this use case.
 *
 * <p>Fires its pressure exactly once: {@link #getCrackPressure} sets
 * {@code fired = true} on its first call, and {@link #isExpired()} returns
 * that flag — matching {@link ICrackSourceProvider}'s documented one-shot
 * pattern (checked BEFORE getCrackPressure each propagation cycle, so the
 * source is live for exactly one propagation tick then removed).
 */
public final class ImpactCrackSource implements ICrackSourceProvider {

    private final BlockPos pos;
    private final float pressure;
    private final double radius;
    private volatile boolean fired = false;

    /**
     * @param pos      impact point (block position)
     * @param pressure crack pressure for this impact (see ImpactFractureBridge
     *                 for how this is derived from impact energy and the
     *                 impacted block's fracture_toughness/compressive_strength)
     * @param radius   radius (blocks) of the zone cracks may radiate into
     */
    public ImpactCrackSource(BlockPos pos, float pressure, double radius) {
        this.pos = pos.immutable();
        this.pressure = pressure;
        this.radius = radius;
    }

    @Override
    public AABB getZone() {
        return new AABB(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius + 1, pos.getY() + radius + 1, pos.getZ() + radius + 1
        );
    }

    @Override
    public float getCrackPressure(ServerLevel level) {
        fired = true;
        return pressure;
    }

    @Override
    public CrackCause getCause() {
        return CrackCause.IMPACT;
    }

    @Override
    public boolean isExpired() {
        return fired;
    }

    @Override
    public String sourceId() {
        // No dedup needed — impacts are inherently one-shot and rare enough
        // at the same tick/position that stacking is fine.
        return null;
    }
}
