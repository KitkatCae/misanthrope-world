package exp.CCnewmods.misanthrope_world.altitude.compat;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Safe bridge to MGE and Project Atmosphere for the altitude system.
 * <p>
 * All calls are wrapped in try-catch so a missing or broken dependency
 * cannot crash the altitude tick. Every method has a safe no-MGE fallback.
 * <p>
 * Confirmed API signatures (read from bytecode):
 * <ul>
 *   <li>{@code WindProviderManager.getWind(LevelAccessor, BlockPos) → Vec3}
 *       — reads from PA if loaded, NullWindProvider (Vec3.ZERO) otherwise</li>
 *   <li>{@code GridAtmosphereCompat.getTotalPressure(Level, BlockPos) → float} (mbar)</li>
 *   <li>{@code EnvironmentGrid.getGas(Level, BlockPos, Gas) → float} (mbar)</li>
 *   <li>{@code EnvironmentGrid.getComposition(Level, BlockPos) → GasComposition}</li>
 *   <li>{@code GasComposition.totalPressure() → float}</li>
 *   <li>{@code GasComposition.get(Gas) → float}</li>
 *   <li>{@code DimensionAtmosphereProfile.basePressureMbar} — float field</li>
 *   <li>{@code EnvironmentChunkData.profile} — DimensionAtmosphereProfile field (nullable)</li>
 * </ul>
 */
public final class MgeAtmosphereReader {

    private static final Logger LOGGER = LogManager.getLogger(Misanthrope_world.MODID);

    /** Standard sea-level O2 partial pressure in mbar (1013 hPa × 0.2095). */
    public static final float STANDARD_O2_MBAR = 212.2f;

    /** Standard sea-level total pressure in mbar. */
    public static final float STANDARD_TOTAL_MBAR = 1013.25f;

    private static final MgeAtmosphereReader INSTANCE = new MgeAtmosphereReader();
    public static MgeAtmosphereReader getInstance() { return INSTANCE; }

    private boolean mgeLoaded   = false;
    private boolean checked     = false;

    private MgeAtmosphereReader() {}

    public void tryLoad() {
        if (checked) return;
        checked = true;
        mgeLoaded = ModList.get().isLoaded("mge");
        if (mgeLoaded) {
            LOGGER.info("[MisWorld Altitude] MGE detected — atmosphere thinning and wind integration active.");
        }
    }

    public boolean isMgeLoaded() { return mgeLoaded; }

    // ── Wind ──────────────────────────────────────────────────────────────────

    /**
     * Returns the wind speed in m/s at the player's position.
     * Uses MGE's {@code WindProviderManager} which automatically delegates to
     * Project Atmosphere if loaded, or returns Vec3.ZERO otherwise.
     * Returns 0.0 if MGE is not present.
     */
    public double getWindSpeedMps(Level level, BlockPos pos) {
        if (!mgeLoaded) return 0.0;
        try {
            Vec3 wind = exp.CCnewmods.mge.wind.WindProviderManager.getWind(level, pos);
            return wind.length(); // magnitude in m/s
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ── Atmosphere pressure ───────────────────────────────────────────────────

    /**
     * Returns the total gas pressure at this position in mbar.
     * Returns {@link #STANDARD_TOTAL_MBAR} as fallback when MGE is absent or unchunked.
     */
    public float getTotalPressureMbar(Level level, BlockPos pos) {
        if (!mgeLoaded) return STANDARD_TOTAL_MBAR;
        try {
            float p = exp.CCnewmods.mge.grid.compat.GridAtmosphereCompat.getTotalPressure(level, pos);
            // GridAtmosphereCompat returns 0 for untracked chunks — treat as standard
            return (p > 0f) ? p : STANDARD_TOTAL_MBAR;
        } catch (Exception e) {
            return STANDARD_TOTAL_MBAR;
        }
    }

    /**
     * Returns the O2 partial pressure at this position in mbar.
     * Returns {@link #STANDARD_O2_MBAR} as fallback.
     */
    public float getOxygenMbar(Level level, BlockPos pos) {
        if (!mgeLoaded) return STANDARD_O2_MBAR;
        try {
            float o2 = exp.CCnewmods.mge.grid.EnvironmentGrid.getGas(
                    level, pos, exp.CCnewmods.mge.gas.GasRegistry.OXYGEN);
            return (o2 > 0f) ? o2 : STANDARD_O2_MBAR;
        } catch (Exception e) {
            return STANDARD_O2_MBAR;
        }
    }

    /**
     * Returns the dimension's configured baseline pressure in mbar.
     * <p>
     * This is read from the {@code DimensionAtmosphereProfile} attached to the
     * chunk's {@code EnvironmentChunkData}. For the overworld this is typically
     * ~1013 mbar; for thin-atmosphere dimensions (moon, high aether) it will be lower.
     * <p>
     * Falls back to {@link #STANDARD_TOTAL_MBAR} if no profile is attached.
     */
    /**
     * Returns the dimension's configured baseline pressure in mbar.
     * <p>
     * Uses {@code DimensionAtmosphereLoader.loaded(dimensionId)} — the confirmed
     * static method that reads from the data-driven PROFILES map.
     * {@code basePressureMbar} is a public float field on DimensionAtmosphereProfile.
     * <p>
     * NOTE: EnvironmentChunkData has NO static CAPABILITY field in any version of MGE.
     * The capability token is private to GridCapabilityHandler. We use
     * DimensionAtmosphereLoader instead, which is the clean public API.
     */
    public float getDimensionBaselinePressureMbar(Level level, BlockPos pos) {
        if (!mgeLoaded) return STANDARD_TOTAL_MBAR;
        try {
            net.minecraft.resources.ResourceLocation dimId = level.dimension().location();
            exp.CCnewmods.mge.dimension.DimensionAtmosphereProfile profile =
                    exp.CCnewmods.mge.dimension.DimensionAtmosphereLoader.INSTANCE.get(dimId);
            if (profile == null) return STANDARD_TOTAL_MBAR;
            return profile.basePressureMbar;
        } catch (Exception e) {
            return STANDARD_TOTAL_MBAR;
        }
    }

    // ── Atmosphere thinning factor ────────────────────────────────────────────

    /**
     * Computes how much to amplify cold altitude modifiers due to thin atmosphere.
     * <p>
     * Formula: {@code max(1.0, baseline / actual)}
     * <ul>
     *   <li>At sea level (actual ≈ baseline): factor = 1.0 — no amplification.</li>
     *   <li>At 512m altitude (actual ≈ 550 mbar, baseline 1013 mbar): factor ≈ 1.84×</li>
     *   <li>In a near-vacuum dimension (actual 10 mbar): factor ≈ 100×, but we clamp at 3.0.</li>
     * </ul>
     * The factor is also boosted by O2 depletion: if O2 is below standard fraction
     * (20.95% of total), the factor scales up further, representing wind-chill and
     * physiological cold stress from low O2. This integrates naturally with MGE's
     * dimension atmosphere JSON where moons/aether can have non-standard gas mixes.
     * <p>
     * Protection items counteract this factor — full protection makes factor = 1.0
     * regardless of how thin the atmosphere is (the gear is handling it).
     *
     * @param totalMbar         actual total pressure at player position
     * @param baselineMbar      dimension's configured baseline pressure
     * @param oxygenMbar        actual O2 partial pressure
     * @param protectionFrac    0–1 from protection items (1 = full gear, 0 = bare)
     * @return amplifier ≥ 1.0 (clamped to [1.0, 3.0])
     */
    public static double computeAtmosphereThinFactor(float totalMbar, float baselineMbar,
                                                     float oxygenMbar, double protectionFrac) {
        if (baselineMbar <= 0f) return 1.0;

        // Base pressure ratio: how thin is the air relative to the dimension norm?
        double pressureRatio = Math.max(1.0, baselineMbar / Math.max(totalMbar, 1f));

        // O2 depletion boost: compare to expected O2 fraction (20.95%)
        double expectedO2 = baselineMbar * 0.2095;
        double o2Ratio = (expectedO2 > 0) ? Math.max(1.0, expectedO2 / Math.max(oxygenMbar, 0.1f)) : 1.0;

        // Combine: thin air × low O2 (capped so each can contribute up to 1.5×)
        double raw = pressureRatio * Math.min(o2Ratio, 1.5);

        // Protection gear attenuates back toward 1.0
        double attenuated = 1.0 + (raw - 1.0) * (1.0 - protectionFrac);

        return Math.max(1.0, Math.min(3.0, attenuated));
    }
}
