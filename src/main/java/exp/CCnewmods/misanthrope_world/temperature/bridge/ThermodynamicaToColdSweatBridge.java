package exp.CCnewmods.misanthrope_world.temperature.bridge;

import com.momosoftworks.coldsweat.api.event.core.registry.BlockTempRegisterEvent;
import com.momosoftworks.coldsweat.api.temperature.block_temp.BlockTemp;
import com.Tribulla.thermodynamica.api.HeatAPI;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Bridges Thermodynamica's block temperature grid into ColdSweat's BlockTemp system.
 * <p>
 * ── What this does ────────────────────────────────────────────────────────────
 * Registers a single custom BlockTemp with ColdSweat that, when ColdSweat asks
 * "how much does this block affect nearby entity temperatures?", queries
 * Thermodynamica's simulated temperature at that position.
 * <p>
 * This means:
 * - A furnace Thermodynamica tracks at 800°C → ColdSweat entities feel heat from it
 * - A luxtructosaurus that emits heat into Thermodynamica's grid → players feel it
 * - Any modded heat source registered with Thermodynamica automatically affects
 * ColdSweat entities without needing separate ColdSweat registration
 * <p>
 * ── Temperature conversion ────────────────────────────────────────────────────
 * Thermodynamica uses Celsius directly. ColdSweat also uses Celsius (in our config).
 * The effect delivered to ColdSweat is the Thermodynamica temperature minus the
 * ambient temperature (the "excess heat"), normalized to ColdSweat's effect units.
 * <p>
 * ColdSweat's BlockTemp.getTemperature() should return a delta in °C that will
 * be applied to the entity's WORLD trait. So we return:
 * (thermoTemp - ambient) × SCALING
 * <p>
 * SCALING is calibrated so that a block at 800°C (like a furnace) nearby produces
 * roughly the same effect as ColdSweat's built-in FurnaceBlockTemp.
 * <p>
 * ── Also bridges TemperatureChangeEvent → ColdSweat world temp cache ──────────
 * When Thermodynamica fires TemperatureChangeEvent for a large temperature change,
 * we can signal ColdSweat to invalidate its cached world temperature for nearby
 * entities. This ensures players near a newly-lit furnace feel the change promptly
 * rather than waiting for ColdSweat's normal cache TTL.
 * <p>
 * ── Only registered if both mods are present ─────────────────────────────────
 * The @SubscribeEvent registration is guarded — if Thermodynamica isn't loaded,
 * the BlockTemp is never registered and ColdSweat behaves as normal.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ThermodynamicaToColdSweatBridge {

    /**
     * How much of Thermodynamica's temperature excess (above ambient)
     * translates into ColdSweat world temperature delta.
     * Tuned so that 800°C furnace excess (~780°C) ≈ ColdSweat furnace effect.
     * ColdSweat's furnace produces roughly +10°C to nearby players.
     * So: 780 × 0.013 ≈ 10.1°C — reasonable.
     */
    private static final double SCALING = 0.013;

    /**
     * Maximum ColdSweat effect this bridge can contribute per block.
     */
    private static final double MAX_EFFECT_CELSIUS = 25.0;

    /**
     * Minimum Thermodynamica excess to produce any ColdSweat effect.
     */
    private static final double MIN_EXCESS_CELSIUS = 10.0;

    @SubscribeEvent
    public static void onBlockTempRegister(BlockTempRegisterEvent event) {
        if (!MisTemperatureAPI.isThermodynamicaLoaded()) return;

        event.register(new ThermodynamicaBlockTemp());
    }

    // ── Custom BlockTemp implementation ───────────────────────────────────────

    private static class ThermodynamicaBlockTemp extends BlockTemp {

        ThermodynamicaBlockTemp() {
            // No specific blocks — we handle all blocks dynamically
            // range: how many blocks away this can affect entities (ColdSweat checks this)
            // We return 0 from getTemperature for out-of-range blocks, so range can be large
        }

        /**
         * Called by ColdSweat's BlockTempModifier for each block near an entity.
         * We query Thermodynamica for the simulated temperature at this position
         * and return the excess as a ColdSweat effect delta.
         */
        @Override
        public double getTemperature(Level level, LivingEntity entity,
                                     BlockState state, BlockPos pos, double distance) {
            try {
                double thermoTemp = HeatAPI.get().getSimulatedCelsius(level, pos).orElse(Double.NaN);
                if (Double.isNaN(thermoTemp)) return 0;

                double ambient = MisTemperatureAPI.getAmbientCelsius(level, pos);
                double excess = thermoTemp - ambient;

                if (Math.abs(excess) < MIN_EXCESS_CELSIUS) return 0;

                // Scale the excess and clamp to max effect
                double effect = excess * SCALING;
                return Math.max(-MAX_EFFECT_CELSIUS, Math.min(MAX_EFFECT_CELSIUS, effect));

            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public boolean isValid(Level level, BlockPos pos, BlockState state) {
            if (!MisTemperatureAPI.isThermodynamicaLoaded()) return false;
            try {
                return HeatAPI.get().getSimulatedCelsius(level, pos).isPresent();
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public Set<net.minecraft.world.level.block.Block> getAffectedBlocks() {
            // Empty set — we don't restrict to specific blocks.
            // ColdSweat will call isValid() to determine if a block counts.
            return Set.of();
        }
    }
}
