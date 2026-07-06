package exp.CCnewmods.misanthrope_world.physics.pressure.hull;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Wires the ported hull-pressure system into MWorld's tick/level lifecycle,
 * matching how {@code ImpactSystemSetup} hooks {@code ImpactHandler}.
 *
 * <p>Ported from MVSE, where {@code HullPressureHandler.serverTick} ran in
 * {@code MVSForgeEvents.onServerTick} after {@code VaporiseHandler} and
 * {@code ImpactHandler}. The vaporise-ordering concern doesn't apply here
 * (pressure breach and impact-crater are independent effects on different
 * blocks), so no cross-mod ordering note is needed for this one.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HullPressureSystemSetup {

    private HullPressureSystemSetup() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        HullPressureHandler.serverTick(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            HullPressureHandler.onLevelUnload(level);
        }
    }
}
