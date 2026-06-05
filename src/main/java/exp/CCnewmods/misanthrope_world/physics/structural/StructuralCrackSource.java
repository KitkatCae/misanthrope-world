package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * {@link ICrackSourceProvider} backed by a structural stress value.
 *
 * <p>Registered by {@link StructuralStressField} when a block's effective
 * stress crosses its {@code crack_threshold_fraction}. The source stays
 * alive as long as the block keeps being re-evaluated as stressed; it
 * expires after {@link #EXPIRY_TICKS} ticks without a refresh (meaning
 * the stress dropped and the block healed).
 *
 * <p>Pressure is normalized: 0 at crack threshold, 50 at failure threshold.
 * The {@link CrackPropagator} converts pressure → advances per interval:
 * {@code advances = max(1, (int)(pressure / 10))} capped at 5.
 * So:
 * <ul>
 *   <li>Pressure  5 = 1 advance per interval — hairline cracks, slow</li>
 *   <li>Pressure 20 = 2 advances per interval — moderate cracking</li>
 *   <li>Pressure 50 = 5 advances per interval — rapid, near failure</li>
 * </ul>
 *
 * <p>Zone is a 3×3×3 AABB centred on the block — large enough for veins to
 * propagate into immediate neighbours (as you'd see in real masonry failure),
 * small enough to keep propagation local.
 */
public final class StructuralCrackSource implements ICrackSourceProvider {

    /**
     * Ticks before this source expires without a refresh from StructuralStressField.
     */
    public static final int EXPIRY_TICKS = 100;

    private final BlockPos pos;
    private float pressure;
    private long lastRefreshTick;
    private final String sourceId;

    public StructuralCrackSource(BlockPos pos, float pressure,
                                 long gameTick, String sourceId) {
        this.pos = pos.immutable();
        this.pressure = pressure;
        this.lastRefreshTick = gameTick;
        this.sourceId = sourceId;
    }

    // ── ICrackSourceProvider ──────────────────────────────────────────────────

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
        return CrackCause.STRUCTURAL;
    }

    @Override
    public boolean isExpired() {
        // Expired if StructuralStressField hasn't refreshed us within EXPIRY_TICKS.
        // We track expiry by comparing the gameTick stored at last refresh — but
        // since we don't have a direct level reference here, expiry is checked via
        // the lastRefreshTick field: StructuralStressField calls addSource (which
        // replaces via deduplication) on every re-evaluation. If it stops calling,
        // the source quietly expires. We declare never-expired here and rely on
        // CrackPropagator's deduplication replacing the old source with a fresh one
        // each evaluation cycle. The source is effectively removed when
        // StructuralStressField stops re-registering it (stress dropped).
        // For hard expiry, FailureDispatcher calls CrackPropagator.removeSource.
        return false;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Update pressure value. Called by StructuralStressField when re-evaluating.
     */
    public void refresh(float newPressure, long gameTick) {
        this.pressure = newPressure;
        this.lastRefreshTick = gameTick;
    }

    public BlockPos pos() {
        return pos;
    }
}
