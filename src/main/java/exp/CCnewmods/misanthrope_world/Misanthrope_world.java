package exp.CCnewmods.misanthrope_world;

import exp.CCnewmods.misanthrope_world.altitude.AltitudeSetup;
import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.WorldSimulation;
import exp.CCnewmods.misanthrope_world.physics.clockwork.ClockworkThermalBridge;
import exp.CCnewmods.misanthrope_world.physics.collapse.network.CollapseNetwork;
import exp.CCnewmods.misanthrope_world.physics.offgas.OffGasHandler;
import exp.CCnewmods.misanthrope_world.physics.structural.MinecollapseBypassHandler;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import exp.CCnewmods.misanthrope_world.crackrender.CrackRenderSetup;
import exp.CCnewmods.misanthrope_world.objects.MisWorldBlockEntityRegistry;
import exp.CCnewmods.misanthrope_world.wet_sand.WaterConsumptionSystem;
import exp.CCnewmods.misanthrope_world.wet_sand.WetSandRegistration;
import exp.CCnewmods.misanthrope_world.wet_sand.WetSandRegistry;
import exp.CCnewmods.misanthrope_world.objects.MisWorldBlocks;
import net.minecraftforge.event.AddReloadListenerEvent;
import exp.CCnewmods.misanthrope_world.temperature.TemperatureSystemSetup;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(Misanthrope_world.MODID)
public class Misanthrope_world {

    public static final String MODID = "misanthrope_world";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public Misanthrope_world() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // ── Unified config (must be first) ────────────────────────────────────
        // Registers misanthrope_world-server.toml with Forge's config system.
        // Values are available after FMLCommonSetupEvent fires.
        MisWorldConfig.register();

        // ── Block / BE registration ───────────────────────────────────────────
        MisWorldBlocks.DEF_REG.register(modEventBus);
        WetSandRegistration.register(modEventBus);
        MisWorldBlockEntityRegistry.DEF_REG.register(modEventBus);

        // ── Temperature (always on — core purpose of this mod) ────────────────
        TemperatureSystemSetup.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> MisanthropeWorldClient::init);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        // Wet sand registry is a data-driven reload listener; we only add it
        // here even if the system is disabled — the registry itself is harmless
        // when empty. The actual block registration is gated below.
        event.addListener(WetSandRegistry.INSTANCE);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Log system state now that the config has been loaded by Forge
        MisWorldConfig.logSystemState();

        // ── Crack render setup (client mesh injection) ────────────────────────
        // CrackRenderSetup sets up the client-side mesh injector regardless of
        // the crack toggle — the client doesn't know the server's config state
        // at this point. The actual propagator simply won't fire events when
        // crackSystem = false, so no cracks will appear anyway.
        CrackRenderSetup.commonSetup(event);

        event.enqueueWork(() -> {
            // ── Always-on physics infrastructure ─────────────────────────────
            // BlockPhysicsRegistry is the material_properties JSON loader used by
            // many systems (offgas, thermal simulation, etc.) — always needed.
            BlockPhysicsRegistry.INSTANCE.getClass();

            // OffGasHandler and thermal systems are part of the temperature bridge
            // (always on).
            OffGasHandler.class.getName();
            ClockworkThermalBridge.class.getName();
            WorldSimulation.class.getName();

            // ── Crack / structural system ─────────────────────────────────────
            if (MisWorldConfig.isCrackSystemEnabled() || MisWorldConfig.isCollapseSystemEnabled()) {
                StructuralStressField.class.getName();
                MinecollapseBypassHandler.class.getName();
            }

            // ── Collapse / lattice system ─────────────────────────────────────
            if (MisWorldConfig.isCollapseSystemEnabled()) {
                CollapseNetwork.register();
            }

            // ── Wet sand system ───────────────────────────────────────────────
            if (MisWorldConfig.isWetSandEnabled()) {
                WetSandRegistration.registerWetSandEntries();
                WetSandRegistration.registerModCompatEntries();
                MinecraftForge.EVENT_BUS.register(WaterConsumptionSystem.class);
            } else {
                LOGGER.info("[MisWorld] Wet sand system disabled — skipping block registration.");
            }
        });

        // ── Altitude temperature system ───────────────────────────────────────
        // AltitudeSetup checks MisWorldConfig.isAltitudeTemperatureEnabled() itself.
        AltitudeSetup.commonSetup(event);
    }
}
