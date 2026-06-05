package exp.CCnewmods.misanthrope_world.crackrender.network;

import exp.CCnewmods.misanthrope_world.crackrender.world.CrackStateMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Sends the full crack state for a chunk to a player when they begin watching it.
 * This ensures clients have correct crack state for all loaded chunks, not just
 * changes that occurred while they were connected.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CrackChunkLoadHandler {

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        if (!(event.getPlayer().level() instanceof ServerLevel level)) return;
        ServerPlayer player = event.getPlayer();
        CrackStateMap stateMap = CrackStateMap.get(level);

        CrackSyncPacket.sendChunkLoad(
                player,
                stateMap,
                event.getPos().x,
                event.getPos().z
        );
    }
}
