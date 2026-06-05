package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * {@link ICrackSourceProvider} driven by thermal expansion stress.
 *
 * <p>Registered by {@link exp.CCnewmods.misanthrope_core.physics.field.ThermalField}
 * when a wall block's surface temperature exceeds its
 * {@link exp.CCnewmods.misanthrope_core.physics.BlockPhysicsData#thermalCrackThreshold}.
 * Uses {@link CrackCause#THERMAL} — narrow branching cracks with an orange glow
 * at level 3.
 *
 * <p>Pressure is normalized 0–50:
 * <ul>
 *   <li>At threshold: ~1 (hairline cracks, slow)</li>
 *   <li>At 2× threshold: ~25 (active cracking)</li>
 *   <li>At 3× threshold: 50 (rapid, approaching failure)</li>
 * </ul>
 *
 * <p>Zone is a 3×3×3 AABB centred on the block so veins can propagate into
 * immediately adjacent blocks — matching the branching pattern of real thermal
 * spalling in heated stone and ceramics.
 *
 * <p>The source is registered with a stable {@code sourceId} so ThermalField's
 * repeated {@code CrackPropagator.addSource()} calls replace the old entry
 * rather than stacking. The source naturally disappears when ThermalField
 * stops calling {@code addSource} (block cooled below threshold).
 */
public final class ThermalCrackSource implements ICrackSourceProvider {

    private final BlockPos pos;
    private final float pressure;
    private final long registeredTick;
    private final String sourceId;

    public ThermalCrackSource(BlockPos pos, float pressure,
                              long gameTick, String sourceId) {
        this.pos = pos.immutable();
        this.pressure = pressure;
        this.registeredTick = gameTick;
        this.sourceId = sourceId;
    }

    @Override
    public AABB getZone() {
        return new AABB(
                pos.getX() - 1.5, pos.getY() - 1.5, pos.getZ() - 1.5,
                pos.getX() + 2.5, pos.getY() + 2.5, pos.getZ() + 2.5
        );
    }

    @Override
    public float getCrackPressure(ServerLevel level) {
        return pressure;
    }

    @Override
    public CrackCause getCause() {
        return CrackCause.THERMAL;
    }

    @Override
    public boolean isExpired() {
        // Never expires on its own — ThermalField stops re-registering it when
        // the block cools below threshold, so the old entry is replaced by nothing
        // and the propagator naturally stops driving it.
        return false;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }
}
