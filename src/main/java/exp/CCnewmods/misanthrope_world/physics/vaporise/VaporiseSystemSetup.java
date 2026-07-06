package exp.CCnewmods.misanthrope_world.physics.vaporise;

import exp.CCnewmods.misanthrope_world.physics.vaporise.client.VaporiseBillboardRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/**
 * Wires the ported vaporise system into MWorld's tick lifecycle, matching
 * {@code ImpactSystemSetup}/{@code HullPressureSystemSetup}/
 * {@code ReentrySystemSetup}/{@code SonicBoomSystemSetup}'s pattern.
 *
 * <p>The shader registration here is the fix for the bus-mismatch bug found
 * in {@code VaporiseBillboardRenderer} — {@code RegisterShadersEvent} fires
 * on the MOD bus, not FORGE, so it needs its own {@code Bus.MOD}-annotated
 * class, same as {@code ReentrySystemSetup.ClientModEvents} does for
 * {@code PlasmaEdgeShader}.</p>
 */
public final class VaporiseSystemSetup {

    private VaporiseSystemSetup() {
    }

    @Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ServerEvents {
        /**
         * HIGH priority so this runs before ImpactSystemSetup's own (default
         * NORMAL priority) LevelTickEvent subscriber — a vaporising ship needs
         * to be gone before the impact detector gets to it (see
         * VaporiseHandler's class doc on the pre-emption contract). This used
         * to be an unenforceable cross-mod ordering caveat when Impact lived
         * in MWorld and Vaporise was still in MVSE; now that both are in the
         * same mod, this can actually be guaranteed instead of just hoped for.
         */
        @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
        public static void onLevelTick(TickEvent.LevelTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (!(event.level instanceof ServerLevel level)) return;
            VaporiseHandler.serverTick(level);
        }
    }

    @Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientModEvents {
        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            new ResourceLocation("misanthrope_world", "vaporise_plasma").toString(),
                            DefaultVertexFormat.POSITION_COLOR_NORMAL),
                    VaporiseBillboardRenderer::setPlasmaShader);
        }
    }
}
