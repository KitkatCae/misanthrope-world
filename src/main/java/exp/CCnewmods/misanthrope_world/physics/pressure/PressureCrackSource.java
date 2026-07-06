package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * {@link ICrackSourceProvider} driven by a pressure differential on a hull block.
 *
 * <p>Registered by {@link HullPressureState} when a block's |ΔP| crosses
 * {@code crackThresholdFraction × ultimateStrengthMbar}. The source stays
 * alive as long as pressure is in the plastic/failure region and is replaced
 * (via deduplication on {@code sourceId}) each evaluation. When ΔP drops to
 * the safe/elastic region, {@link HullPressureState} stops re-registering the
 * source and {@code CrackPropagator} lets it expire based on the
 * PRESSURE cause's {@code healRate} (3000 ticks / 2.5 min per step).</p>
 *
 * <h3>Pressure mapping → crack pressure</h3>
 * {@code crackPressure = 50 × (|ΔP| - crackThreshold) / (ultimate - crackThreshold)}
 * giving a 0–50 range, consistent with {@code StructuralCrackSource}'s scale.
 */
public final class PressureCrackSource implements ICrackSourceProvider {

    /** Zone radius around the hull block — cracks propagate to immediate neighbours. */
    private static final float ZONE_RADIUS = 2.5f;

    private final BlockPos pos;
    private float crackPressure;
    private final String sourceId;

    /**
     * @param pos           ship-space block position of the stressed hull block
     * @param crackPressure normalised pressure [0, 50] — 0 at crack threshold,
     *                      50 at ultimate strength
     */
    public PressureCrackSource(BlockPos pos, float crackPressure) {
        this.pos          = pos.immutable();
        this.crackPressure = Math.max(0f, Math.min(50f, crackPressure));
        this.sourceId     = "misanthrope_world:pressure:" + pos.asLong();
    }

    // ── ICrackSourceProvider ──────────────────────────────────────────────────

    @Override
    public AABB getZone() {
        return new AABB(
                pos.getX() - ZONE_RADIUS, pos.getY() - ZONE_RADIUS, pos.getZ() - ZONE_RADIUS,
                pos.getX() + ZONE_RADIUS + 1, pos.getY() + ZONE_RADIUS + 1, pos.getZ() + ZONE_RADIUS + 1
        );
    }

    @Override
    public float getCrackPressure(ServerLevel level) {
        return crackPressure;
    }

    @Override
    public CrackCause getCause() {
        return CrackCause.PRESSURE;
    }

    @Override
    public boolean isExpired() {
        // Never self-expires; HullPressureState refreshes via deduplication
        // or stops re-registering when ΔP normalises.
        return false;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    public void refresh(float newCrackPressure) {
        this.crackPressure = Math.max(0f, Math.min(50f, newCrackPressure));
    }

    public BlockPos pos() { return pos; }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Computes normalised crack pressure from raw ΔP and pressure data.
     *
     * @param absDeltaMbar     absolute pressure differential in mbar
     * @param crackThreshold   mbar at which cracking begins
     * @param ultimateMbar     mbar at which block fails outright
     * @return normalised crack pressure [0, 50]
     */
    public static float computeCrackPressure(float absDeltaMbar,
                                              float crackThreshold,
                                              float ultimateMbar) {
        if (absDeltaMbar <= crackThreshold) return 0f;
        float range = Math.max(1f, ultimateMbar - crackThreshold);
        return 50f * Math.min(1f, (absDeltaMbar - crackThreshold) / range);
    }
}
