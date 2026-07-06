package exp.CCnewmods.misanthrope_world;

import exp.CCnewmods.misanthrope_world.objects.MisWorldBlockEntityRegistry;
import exp.CCnewmods.misanthrope_world.physics.collapse.client.LatticeCollapseRenderer;
import exp.CCnewmods.misanthrope_world.physics.pressure.client.WorldBlockDeformRenderLayer;
import exp.CCnewmods.misanthrope_world.physics.reentry.client.PlasmaParticle;
import exp.CCnewmods.misanthrope_world.registry.MisWorldParticles;
import exp.CCnewmods.misanthrope_world.wet_sand.client.WetnessTintHandler;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;

@Mod.EventBusSubscriber(modid = Misanthrope_world.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class MisanthropeWorldClient {

    public static void init() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(MisanthropeWorldClient::clientSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(MisanthropeWorldClient::onRegisterBlockColors);
    }

    private static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        WetnessTintHandler.onRegisterBlockColors(event);
    }

    private static void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            BlockEntityRenderers.register(
                    MisWorldBlockEntityRegistry.LATTICE_COLLAPSE_BE.get(),
                    LatticeCollapseRenderer::new);

            // Ported from MVSE's clientSetup
            net.minecraft.client.Minecraft.getInstance().particleEngine.register(
                    MisWorldParticles.PLASMA_TRAIL.get(),
                    PlasmaParticle.Provider::new);
        });

        // ── World-space pressure deformation render layer ─────────────────────
        // Registered on the Forge bus (not the mod bus) because
        // RenderLevelStageEvent fires on the Forge bus.
        MinecraftForge.EVENT_BUS.register(WorldBlockDeformRenderLayer.class);
    }
}
