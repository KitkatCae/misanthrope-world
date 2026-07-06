package exp.CCnewmods.misanthrope_world.physics.sonicboom;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Wires the ported sonic boom system into MWorld's tick/level lifecycle,
 * matching {@code ImpactSystemSetup}/{@code HullPressureSystemSetup}/
 * {@code ReentrySystemSetup}'s pattern.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SonicBoomSystemSetup {

    private SonicBoomSystemSetup() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        SoundBarrierHandler.serverTick(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SoundBarrierHandler.onLevelUnload(level);
        }
    }
}
