package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Wires {@link ImpactHandler} (ported from MVSE) into MWorld's own tick and
 * level lifecycle, the same way {@code StructuralStressField} and
 * {@code CrackPropagator} hook {@code TickEvent.LevelTickEvent} rather than a
 * mod-specific event bus like MVSE's {@code MVSForgeEvents} did.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ImpactSystemSetup {

    private ImpactSystemSetup() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        ImpactHandler.serverTick(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ImpactHandler.onLevelUnload(level);
        }
    }
}
