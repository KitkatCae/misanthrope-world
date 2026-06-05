package exp.CCnewmods.misanthrope_world.furnace.environment;

import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/**
 * Samples all environment factors relevant to furnace/bloomery operation
 * at a given world position.
 *
 * ── Usage ─────────────────────────────────────────────────────────────────────
 * Call {@link #sample(Level, BlockPos)} once per furnace tick (server side only).
 * The returned {@link Sample} record is immutable and cheap to pass around.
 *
 * ── Factors ───────────────────────────────────────────────────────────────────
 *
 * ambientCelsius     — ambient air temperature at position (MisTemperatureAPI)
 * oxygenMbar         — local O₂ partial pressure (MGE)
 *                      Standard: 209.5 mbar. Below 160 mbar: fire won't sustain.
 *                      Above 209.5 mbar: oxidising atmosphere, faster combustion.
 * waterVaporMbar     — local water vapour pressure (MGE)
 *                      Standard: 10 mbar. High humidity slows drying, wet fuel.
 * fuelGasMbar        — combined mbar of combustible gases present (MGE)
 *                      Methane, hydrogen, propane etc. — contributes extra heat.
 * isRaining          — whether it is currently raining at the position (level sky)
 * isWindy            — from Project Atmosphere wind speed if available
 * windFactor         — [0.5, 2.0] fan factor for open-top furnaces
 *                      Calm = 0.5 (weak draft), strong wind = 2.0 (forced draft)
 *
 * ── Temperature modifier formula ─────────────────────────────────────────────
 * For open-top / open-front structures:
 *   effectiveHeatMultiplier = oxygenFactor × windFactor × (1 - humidityPenalty)
 *                           + fuelGasBonus
 *
 * oxygenFactor:
 *   O₂ < 160 mbar → 0.0  (fire extinguishes)
 *   O₂ 160-209 mbar → lerp(0.3, 1.0)
 *   O₂ 209-350 mbar → lerp(1.0, 1.8)  (enriched oxygen, faster combustion)
 *   O₂ > 350 mbar → 1.8 (capped — pure O₂ is explosive, not just hot)
 *
 * humidityPenalty:
 *   vapor < 10 mbar → 0.0  (dry air, no penalty)
 *   vapor 10-30 mbar → lerp(0, 0.15)
 *   vapor > 30 mbar → 0.15 + 0.05 × (vapor - 30) / 10 clamped to 0.35
 *   (Wet fuel and steam formation reduce effective combustion temperature)
 *
 * windFactor (for open structures only — sealed structures are wind-immune):
 *   Calm wind (< 0.2 m/s) → 0.5 (poor natural draft)
 *   Normal wind (0.5-2 m/s) → 1.0
 *   Strong wind (> 5 m/s) → up to 2.0
 *   But if raining: wind_effective = min(wind, 1.2) — rain disrupts draft
 *
 * fuelGasBonus: 0.01 per mbar of combustible gas, capped at 0.5
 *   (Small bonus from ambient combustibles — they burn in the hot zone)
 */
public final class FurnaceEnvironmentSampler {

    // Standard atmosphere reference values
    public static final float STANDARD_O2_MBAR = 209.5f;
    public static final float STANDARD_VAPOR_MBAR = 10.0f;
    public static final float MIN_O2_FOR_FIRE_MBAR = 160.0f;

    private FurnaceEnvironmentSampler() {
    }

    /**
     * Sample the environment at the given position.
     * Safe to call every tick — all underlying reads are cached or lightweight.
     *
     * @param level     server level
     * @param pos       the interior centre of the furnace (or any representative pos)
     * @param isSealed  whether the furnace is sealed (blocks wind, modifies gas sampling)
     */
    public static Sample sample(Level level, BlockPos pos, boolean isSealed) {
        double ambientCelsius = MisTemperatureAPI.getAmbientCelsius(level, pos);

        float oxygenMbar = STANDARD_O2_MBAR;
        float vaporMbar = STANDARD_VAPOR_MBAR;
        float fuelGasMbar = 0f;

        // MGE gas sampling (soft dependency)
        exp.CCnewmods.mge.gas.GasComposition capturedComp = null;
        if (ModList.get().isLoaded("mge")) {
            try {
                // Sample from pos; if empty fall back to one block above (air above the fire).
                var comp = exp.CCnewmods.mge.grid.compat.GridAtmosphereCompat.getComposition(level, pos);
                if (comp.totalPressure() <= 0f)
                    comp = exp.CCnewmods.mge.grid.compat.GridAtmosphereCompat.getComposition(level, pos.above());
                if (comp.totalPressure() > 0f) {
                    oxygenMbar = comp.get(exp.CCnewmods.mge.gas.GasRegistry.OXYGEN);
                    vaporMbar = comp.get(exp.CCnewmods.mge.gas.GasRegistry.WATER_VAPOR);
                    fuelGasMbar = sumCombustibleGases(comp);
                    capturedComp = comp;
                }
            } catch (Exception ignored) {
                // MGE not fully loaded — use standard values
            }
        }

        // Rain check (only meaningful for non-sealed structures)
        boolean raining = !isSealed && level.isRaining()
                && level.canSeeSky(pos.above());

        // Wind factor (Project Atmosphere soft dependency)
        float windFactor = computeWindFactor(level, pos, isSealed, raining);

        return new Sample(ambientCelsius, oxygenMbar, capturedComp, vaporMbar, fuelGasMbar,
                raining, windFactor);
    }

    /**
     * Convenience overload assuming structure is not sealed.
     */
    public static Sample sample(Level level, BlockPos pos) {
        return sample(level, pos, false);
    }

    // ── Wind factor ───────────────────────────────────────────────────────────

    private static float computeWindFactor(Level level, BlockPos pos,
                                           boolean isSealed, boolean raining) {
        if (isSealed) return 1.0f; // wind irrelevant — sealed structure has own draft
        if (!ModList.get().isLoaded("projectatmosphere")) return raining ? 0.7f : 1.0f;

        try {
            var windVec = exp.CCnewmods.mge.wind.WindProviderManager
                    .getWind(level, pos);
            float speed = (float) windVec.length(); // m/s equivalent

            float factor;
            if (speed < 0.2f) {
                factor = 0.5f;                          // very calm
            } else if (speed < 0.5f) {
                factor = 0.5f + (speed - 0.2f) / 0.6f; // lerp 0.5→1.0
            } else if (speed < 5.0f) {
                factor = 1.0f + (speed - 0.5f) / 9.0f; // lerp 1.0→1.5
            } else {
                factor = Math.min(2.0f, 1.5f + (speed - 5.0f) * 0.1f);
            }

            // Rain caps the effective wind draft
            if (raining) factor = Math.min(factor, 1.2f);
            return factor;

        } catch (Exception ignored) {
            return raining ? 0.7f : 1.0f;
        }
    }

    // ── Combustible gas sum ───────────────────────────────────────────────────

    private static float sumCombustibleGases(exp.CCnewmods.mge.gas.GasComposition comp) {
        float total = 0f;
        try {
            // Check each combustible gas registered in MGE
            for (var gas : exp.CCnewmods.mge.gas.GasRegistry.all()) {
                if (gas.properties().isFlammable()) {
                    total += comp.get(gas);
                }
            }
        } catch (Exception ignored) {
        }
        return total;
    }

    // ── Sample record ─────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of environmental conditions at a furnace position.
     */
    public record Sample(
            double ambientCelsius,
            float oxygenMbar,
            @javax.annotation.Nullable exp.CCnewmods.mge.gas.GasComposition gasComposition,
            float waterVaporMbar,
            float fuelGasMbar,
            boolean isRaining,
            float windFactor
    ) {
        /**
         * Whether O₂ is sufficient to sustain combustion.
         */
        public boolean canSustainFire() {
            return oxygenMbar >= MIN_O2_FOR_FIRE_MBAR;
        }

        /**
         * Effective heat multiplier for open-structure combustion.
         * Accounts for oxygen richness, humidity, wind draft, and ambient fuel gas.
         *
         * 1.0 = standard conditions. > 1.0 = hotter-burning. < 1.0 = weaker.
         */
        public double effectiveHeatMultiplier() {
            if (!canSustainFire()) return 0.0;

            // Oxygen factor
            double oxyFactor;
            float o2 = oxygenMbar;
            if (o2 < MIN_O2_FOR_FIRE_MBAR) {
                oxyFactor = 0.0;
            } else if (o2 <= STANDARD_O2_MBAR) {
                // lerp 0.3 → 1.0 over [160, 209.5]
                oxyFactor = 0.3 + 0.7 * (o2 - MIN_O2_FOR_FIRE_MBAR)
                        / (STANDARD_O2_MBAR - MIN_O2_FOR_FIRE_MBAR);
            } else {
                // lerp 1.0 → 1.8 over [209.5, 350]
                oxyFactor = 1.0 + 0.8 * Math.min(1.0,
                        (o2 - STANDARD_O2_MBAR) / 140.5f);
            }

            // Humidity penalty
            float vapor = waterVaporMbar;
            double humidityPenalty;
            if (vapor <= STANDARD_VAPOR_MBAR) {
                humidityPenalty = 0.0;
            } else if (vapor <= 30f) {
                humidityPenalty = 0.15 * (vapor - STANDARD_VAPOR_MBAR) / 20.0;
            } else {
                humidityPenalty = 0.15 + 0.05 * Math.min(4.0, (vapor - 30f) / 10.0);
            }

            // Fuel gas bonus
            double fuelBonus = Math.min(0.5, fuelGasMbar * 0.01);

            return oxyFactor * windFactor * (1.0 - humidityPenalty) + fuelBonus;
        }

        /**
         * For sealed structures: only oxygen and humidity matter, not wind.
         * Bellows are handled separately via {@link #withBellowsBoost}.
         */
        public double effectiveHeatMultiplierSealed() {
            if (!canSustainFire()) return 0.0;
            // Reuse oxyFactor + humidity but force windFactor = 1.0
            Sample sealedSample = new Sample(ambientCelsius, oxygenMbar,
                    gasComposition(), waterVaporMbar, fuelGasMbar, isRaining, 1.0f);
            return sealedSample.effectiveHeatMultiplier();
        }

        /**
         * Apply a bellows boost on top of this sample.
         * The bellows intensity (0.0–1.0) maps to an additional multiplier of
         * up to {@code maxBoost} on top of the base heat multiplier.
         *
         * @param bellowsIntensity 0.0 = no bellows, 1.0 = maximum bellows blow
         * @param maxBoost         maximum additional multiplier (e.g. 1.5 = up to 2.5× total)
         */
        public double withBellowsBoost(double baseMultiplier,
                                       double bellowsIntensity,
                                       double maxBoost) {
            // Bellows inject oxygen so also check if adding air fixes O₂ shortage
            double effectiveO2 = oxygenMbar + (float) (bellowsIntensity * 100f);
            if (effectiveO2 < MIN_O2_FOR_FIRE_MBAR) return 0.0;
            return baseMultiplier * (1.0 + bellowsIntensity * maxBoost);
        }

        /**
         * Wetness drain multiplier for pottery wheel clay and similar drying systems.
         * Matches the model in {@code PotteryWheelBlockEntity.readHumidityFactor()}.
         */
        /**
         * Returns the partial pressure (mbar) of any MGE gas at the sample position.
         * Returns 0f if MGE is absent or the gas was not present.
         *
         * @param gasId the full resource location of the gas, e.g. "mge:nitrogen"
         */
        public float gasPresenceMbar(net.minecraft.resources.ResourceLocation gasId) {
            if (gasComposition == null) return 0f;
            return exp.CCnewmods.mge.gas.GasRegistry
                    .get(gasId)
                    .map(gasComposition::get)
                    .orElse(0f);
        }

        public float clayWetnessDrainMultiplier() {
            float vapor = waterVaporMbar;
            if (vapor < 0.5f) return 3.0f;
            float factor = STANDARD_VAPOR_MBAR / vapor; // 10 / vapor → 1.0 at standard
            return Math.max(0.25f, Math.min(3.0f, factor));
        }
    }
}
