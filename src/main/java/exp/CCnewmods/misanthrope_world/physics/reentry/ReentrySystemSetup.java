package exp.CCnewmods.misanthrope_world.physics.reentry;

import exp.CCnewmods.misanthrope_world.physics.reentry.client.PlasmaEdgeShader;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/**
 * Wires the ported reentry-heating system into MWorld's tick/level lifecycle
 * and registers the plasma-edge shader, matching {@code ImpactSystemSetup}'s
 * pattern. The shader registration piece is ported from MVSE's separate
 * {@code MVSClient.MVSClientModEvents} inner class — folded in here instead
 * of a separate file since it's a single call.
 */
public final class ReentrySystemSetup {

    private ReentrySystemSetup() {
    }

    @Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ServerEvents {
        @SubscribeEvent
        public static void onLevelTick(TickEvent.LevelTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (!(event.level instanceof ServerLevel level)) return;
            KineticHeatingHandler.serverTick(level);
        }

        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel() instanceof ServerLevel level) {
                KineticHeatingHandler.onLevelUnload(level);
            }
        }
    }

    @Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientModEvents {
        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
            PlasmaEdgeShader.onRegisterShaders(event);
        }
    }
}
