package exp.CCnewmods.misanthrope_world;

import exp.CCnewmods.misanthrope_world.altitude.AltitudeSetup;
import exp.CCnewmods.misanthrope_world.charcoal_pit.CharcoalPitRegistration;
import exp.CCnewmods.misanthrope_world.drying.EnvironmentalDryingRecipeType;
import exp.CCnewmods.misanthrope_world.log_splitting.LogSplittingRegistration;
import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.WorldSimulation;
import exp.CCnewmods.misanthrope_world.physics.clockwork.ClockworkThermalBridge;
import exp.CCnewmods.misanthrope_world.physics.collapse.network.CollapseNetwork;
import exp.CCnewmods.misanthrope_world.physics.offgas.OffGasHandler;
import exp.CCnewmods.misanthrope_world.physics.phase.PhaseTransitionHandler;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralWorldEventHandler;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import exp.CCnewmods.misanthrope_world.physics.structural.vs2.ImpactConfig;
import exp.CCnewmods.misanthrope_world.physics.structural.vs2.ImpactSystemSetup;
import exp.CCnewmods.misanthrope_world.physics.pressure.hull.HullPressureSystemSetup;
import exp.CCnewmods.misanthrope_world.physics.pressure.hull.network.HullPressureNetwork;
import exp.CCnewmods.misanthrope_world.physics.reentry.AerodynamicsConfig;
import exp.CCnewmods.misanthrope_world.physics.reentry.ReentrySystemSetup;
import exp.CCnewmods.misanthrope_world.physics.reentry.network.ReentryStatePacket;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.RattlePhysicsController;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.SonicBoomSystemSetup;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.network.SoundBarrierPacket;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.network.SupersonicRumblePacket;
import exp.CCnewmods.misanthrope_world.physics.vaporise.VaporiseConfig;
import exp.CCnewmods.misanthrope_world.physics.vaporise.VaporiseSystemSetup;
import exp.CCnewmods.misanthrope_world.physics.vaporise.network.VaporisePacket;
import exp.CCnewmods.misanthrope_world.registry.MisWorldParticles;
import exp.CCnewmods.misanthrope_world.crackrender.CrackRenderSetup;
import exp.CCnewmods.misanthrope_world.objects.MisWorldBlockEntityRegistry;
import exp.CCnewmods.misanthrope_world.wet_sand.WaterConsumptionSystem;
import exp.CCnewmods.misanthrope_world.wet_sand.WetSandRegistration;
import exp.CCnewmods.misanthrope_world.wet_sand.WetSandRegistry;
import exp.CCnewmods.misanthrope_world.objects.MisWorldBlocks;
import exp.CCnewmods.misanthrope_world.registry.MisWorldSounds;
import exp.CCnewmods.misanthrope_world.physics.pressure.PressurePhysicsConfig;
import exp.CCnewmods.misanthrope_world.physics.pressure.WorldSpacePressureHandler;
import exp.CCnewmods.misanthrope_world.physics.pressure.creature.CreaturePressureDamageHandler;
import exp.CCnewmods.misanthrope_world.physics.pressure.creature.CreaturePressureLoader;
import exp.CCnewmods.misanthrope_world.physics.pressure.creature.PressureSealedRegistry;
import exp.CCnewmods.misanthrope_world.physics.pressure.network.WorldPressureNetwork;
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

        // ── Pressure physics config (server toml — separate file) ─────────────
        // PressurePhysicsConfig registers its own TOML so ship-operators can
        // tune pressure difficulty independently of the general MisWorld config.
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                PressurePhysicsConfig.SPEC,
                "misanthrope_pressure.toml");

        // ── Ship-impact config (server toml — separate file) ───────────────────
        // Ported from MVSE's Misanthrope_vs_engine constructor, where this was
        // registered as "misanthrope_vs_engine-impact.toml". Renamed to this
        // mod's own naming convention now that ImpactHandler lives here.
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                ImpactConfig.SPEC,
                "misanthrope_world-impact.toml");

        // ── Aerodynamics config (server toml — separate file) ──────────────────
        // Shared by reentry heating (this phase) and sonic booms (next phase).
        // Ported from MVSE's Misanthrope_vs_engine constructor, same rename
        // convention as ImpactConfig above.
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                AerodynamicsConfig.SPEC,
                "misanthrope_world-aerodynamics.toml");

        // ── Vaporize config (server toml — separate file) ───────────────────────
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                VaporiseConfig.SPEC,
                "misanthrope_world-vaporise.toml");

        // ── Particle registry (plasma trail, ported from MVSE's MVSParticles) ──
        MisWorldParticles.PARTICLES.register(modEventBus);

        // ── Block / BE registration ───────────────────────────────────────────
        MisWorldBlocks.DEF_REG.register(modEventBus);
        WetSandRegistration.register(modEventBus);
        MisWorldBlockEntityRegistry.DEF_REG.register(modEventBus);

        // ── Temperature (always on — core purpose of this mod) ────────────────
        TemperatureSystemSetup.register(modEventBus);
        MisWorldSounds.register(modEventBus);

        // ── Charcoal pit / log splitting / drying (moved from misanthrope_core) ─
        LogSplittingRegistration.register(modEventBus);
        CharcoalPitRegistration.register(modEventBus);
        EnvironmentalDryingRecipeType.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> MisanthropeWorldClient::init);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        // Wet sand registry is a data-driven reload listener; we only add it
        // here even if the system is disabled — the registry itself is harmless
        // when empty. The actual block registration is gated below.
        event.addListener(WetSandRegistry.INSTANCE);

        // Creature pressure profiles and pressure-sealed armour sets are both
        // data-driven; reload them alongside other data-pack resources.
        event.addListener(CreaturePressureLoader.INSTANCE);
        event.addListener(PressureSealedRegistry.INSTANCE);
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

        // Hull pressure system (ported from MVSE) rides MWorld's existing
        // CrackNetwork channel — see HullPressureNetwork's class doc for why.
        event.enqueueWork(HullPressureNetwork::register);
        event.enqueueWork(ReentryStatePacket::register);
        event.enqueueWork(SoundBarrierPacket::register);
        event.enqueueWork(SupersonicRumblePacket::register);
        event.enqueueWork(VaporisePacket::register);

        // Sonic-boom rattle is now actually wired up (see RattlePhysicsController's
        // class doc for why this replaced the ShipRattleAttachment/
        // RattlePhysicsListener/VS2-attachment approach entirely). Registers
        // itself lazily on first trigger() call too, but registering here as
        // well means the first boom in a session doesn't pay the registration
        // cost.
        event.enqueueWork(RattlePhysicsController::register);

        event.enqueueWork(() -> {
            // ── Always-on physics infrastructure ─────────────────────────────
            // BlockPhysicsRegistry is the material_properties JSON loader used by
            // many systems (offgas, thermal simulation, etc.) — always needed.
            BlockPhysicsRegistry.INSTANCE.getClass();

            // OffGasHandler and thermal systems are part of the temperature bridge
            // (always on).
            OffGasHandler.class.getName();
            PhaseTransitionHandler.class.getName();
            ClockworkThermalBridge.class.getName();
            WorldSimulation.class.getName();

            // ── Crack / structural system ─────────────────────────────────────
            if (MisWorldConfig.isCrackSystemEnabled() || MisWorldConfig.isCollapseSystemEnabled()) {
                StructuralStressField.class.getName();
                StructuralWorldEventHandler.class.getName();
            }

            // ── VS2 ship-impact system (crater/embed/hypersonic-disassemble,
            // ported from MVSE) — valkyrienskies is a mandatory dependency of
            // this mod per mods.toml, so no ModList guard is needed here. ────
            ImpactSystemSetup.class.getName();
            HullPressureSystemSetup.class.getName();
            ReentrySystemSetup.ServerEvents.class.getName();
            SonicBoomSystemSetup.class.getName();
            VaporiseSystemSetup.ServerEvents.class.getName();

            // ── World-space pressure system ───────────────────────────────────
            // Always registered — WorldSpacePressureHandler is cheap at idle
            // (empty active set costs nothing). MVSE registers its ship hull
            // variant separately using BlockPressureEvaluator from this mod.
            WorldPressureNetwork.register();
            MinecraftForge.EVENT_BUS.register(WorldSpacePressureHandler.class);

            // ── Creature pressure damage ──────────────────────────────────────
            // Evaluated per-entity-tick; the handler self-guards behind the
            // config flag so it is cheap when disabled.
            MinecraftForge.EVENT_BUS.register(CreaturePressureDamageHandler.class);

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
