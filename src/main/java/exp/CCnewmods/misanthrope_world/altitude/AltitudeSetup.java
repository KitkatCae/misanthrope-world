package exp.CCnewmods.misanthrope_world.altitude;

import exp.CCnewmods.misanthrope_world.altitude.compat.MgeAtmosphereReader;
import exp.CCnewmods.misanthrope_world.config.AltitudeConfig;
import exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeTemperatureManager;
import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Single entry point wiring the altitude temperature system into Misanthrope World's init.
 * <p>
 * Gated on {@link MisWorldConfig#isAltitudeTemperatureEnabled()}.
 * If the config toggle is off, no bands are loaded, no ColdSweat modifier is ever
 * applied, and the tick handler fast-returns without doing anything.
 */
public final class AltitudeSetup {

    private static final Logger LOGGER = LogManager.getLogger("misanthrope_world");

    private AltitudeSetup() {}

    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (!MisWorldConfig.isAltitudeTemperatureEnabled()) {
                LOGGER.info("[MisWorld Altitude] Altitude temperature system disabled in config — skipping init.");
                return;
            }

            // Detect MGE and Project Atmosphere
            MgeAtmosphereReader.getInstance().tryLoad();

            // Load band file (creates default if absent)
            AltitudeConfig.getInstance().reload();

            // Force class-load so Forge picks up the @SubscribeEvent handlers on
            // AltitudeTemperatureManager (which are NOPs when the system is disabled)
            AltitudeTemperatureManager.getInstance().getClass();

            LOGGER.info("[MisWorld Altitude] Altitude system initialised. {} band(s) loaded from '{}'. MGE: {}",
                    AltitudeConfig.getInstance().getBands().size(),
                    AltitudeConfig.getInstance().currentFileName(),
                    MgeAtmosphereReader.getInstance().isMgeLoaded() ? "active" : "absent");
        });
    }
}
