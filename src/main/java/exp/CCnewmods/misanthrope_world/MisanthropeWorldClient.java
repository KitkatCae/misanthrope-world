package exp.CCnewmods.misanthrope_world;

import exp.CCnewmods.misanthrope_world.objects.MisWorldBlockEntityRegistry;
import exp.CCnewmods.misanthrope_world.wet_sand.client.WetnessTintHandler;
import exp.CCnewmods.misanthrope_world.physics.collapse.client.LatticeCollapseRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

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
        });
    }
}
