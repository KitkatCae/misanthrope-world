package exp.CCnewmods.misanthrope_world.compat.fullstop;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forces three FullStop server config values to {@code false} regardless of what's
 * in the config file or what a player sets via the config GUI. FullStop is now a
 * required dependency (see {@code MWorld_FullStop_Integration_v1.md}), and these
 * three overlap with systems Misanthrope already owns:
 *
 * <ul>
 *   <li>{@code enablePressureSimulation} — redundant with MGE's EnvironmentGrid +
 *       MWorld's own pressure mechanics. Two independent pressure simulations on
 *       the same player is a bug, not a feature.</li>
 *   <li>{@code kineticBlockBreaking} — redundant with MWorld's own structural /
 *       fracture_toughness / crack_threshold_fraction system.</li>
 *   <li>{@code entityCollisionDamage} — despite the name, this isn't just damage:
 *       {@code EntityCollisionHandler.handle()} is a full elastic-collision impulse
 *       solver plus an auto-mount system (landing on a smaller entity rides it).
 *       Real conflict risk against KHP's solid-mob-collision/Living-Platform logic,
 *       so this is disabled too.</li>
 * </ul>
 *
 * Mechanism: this is a config lock via {@link ModConfigEvent}, not a bytecode mixin.
 * {@code ForgeConfigSpec.ConfigValue#set(T)} writes straight into the backing config
 * AND updates the cached value {@code get()} reads (bytecode-verified against
 * forge-1.20.1-47.4.20-universal.jar), so this re-applies cleanly on every load,
 * {@code /reload}, and config-GUI edit without needing to touch FullStop's internal
 * classes at all — it survives FullStop's own rewrites the same way the
 * namespace-based damage detection in {@code KineticImpactHandler} does.
 *
 * <p>All three flags are confirmed top-of-method gates with no other bypass path
 * (read the full body of {@code PressureHandler.onLivingTick},
 * {@code KineticBlockInteractions.handleBlockImpacts}, and
 * {@code EntityCollisionHandler.handle()} to confirm this before writing this
 * class), so the config lock alone is sufficient — no mixin needed.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FullStopConfigLock {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/FullStopConfigLock");

    private FullStopConfigLock() {
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        enforce(event.getConfig());
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        enforce(event.getConfig());
    }

    private static void enforce(ModConfig config) {
        if (!"fullstop".equals(config.getModId())) return;
        if (config.getType() != ModConfig.Type.SERVER) return;

        boolean changed = false;
        changed |= forceOff(FullStopConfig.SERVER.enablePressureSimulation, "enablePressureSimulation");
        changed |= forceOff(FullStopConfig.SERVER.kineticBlockBreaking, "kineticBlockBreaking");
        changed |= forceOff(FullStopConfig.SERVER.entityCollisionDamage, "entityCollisionDamage");

        if (changed) {
            // Persist to disk too, so the .toml file on disk doesn't silently
            // disagree with actual behavior if someone edits it directly and
            // wonders why their change had no effect.
            FullStopConfig.SERVER.enablePressureSimulation.save();
        }
    }

    /**
     * @return true if the value had to be corrected (was {@code true} and got forced to {@code false})
     */
    private static boolean forceOff(net.minecraftforge.common.ForgeConfigSpec.BooleanValue value, String name) {
        if (value.get()) {
            value.set(false);
            LOGGER.info("[FullStopConfigLock] Forced fullstop.{} to false (Misanthrope owns this system).", name);
            return true;
        }
        return false;
    }
}
