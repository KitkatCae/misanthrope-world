package exp.CCnewmods.misanthrope_world.crackrender.network;

import exp.CCnewmods.misanthrope_world.crackrender.client.ClientCrackCache;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackStateMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Syncs crack state changes from the server to all clients in the same level.
 * <p>
 * ── Payload ───────────────────────────────────────────────────────────────────
 * A compact list of changed entries. Entries with level=0 (fully healed)
 * are sent as removals so the client cache is cleaned up.
 * <p>
 * ── When sent ─────────────────────────────────────────────────────────────────
 * - CrackPropagator.onServerTick: after each propagation cycle for changed blocks
 * - On chunk load: full crack state for the chunk sent to the loading player
 * (see CrackChunkLoadHandler)
 * <p>
 * ── Client handling ───────────────────────────────────────────────────────────
 * On receipt, ClientCrackCache is updated and the affected chunk sections are
 * marked dirty so the chunk compiler re-bakes their geometry.
 */
public class CrackSyncPacket {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/CrackSync");

    private final List<CompoundTag> changedEntries;
    private final List<Long> removedPositions;

    public CrackSyncPacket(List<CompoundTag> changedEntries, List<Long> removedPositions) {
        this.changedEntries = changedEntries;
        this.removedPositions = removedPositions;
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(changedEntries.size());
        for (CompoundTag tag : changedEntries) buf.writeNbt(tag);

        buf.writeInt(removedPositions.size());
        for (long pos : removedPositions) buf.writeLong(pos);
    }

    public static CrackSyncPacket decode(FriendlyByteBuf buf) {
        int changeCount = buf.readInt();
        List<CompoundTag> changes = new ArrayList<>(changeCount);
        for (int i = 0; i < changeCount; i++) changes.add(buf.readNbt());

        int removeCount = buf.readInt();
        List<Long> removals = new ArrayList<>(removeCount);
        for (int i = 0; i < removeCount; i++) removals.add(buf.readLong());

        return new CrackSyncPacket(changes, removals);
    }

    // ── Client handler ────────────────────────────────────────────────────────

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            for (CompoundTag tag : changedEntries) {
                try {
                    CrackEntry entry = CrackEntry.load(tag);
                    ClientCrackCache.put(entry);
                    markSectionDirty(mc, entry.pos());
                } catch (Exception e) {
                    LOGGER.warn("[CrackSync] Failed to load entry from packet: {}", e.getMessage());
                }
            }

            for (long posLong : removedPositions) {
                BlockPos pos = BlockPos.of(posLong);
                ClientCrackCache.remove(pos);
                markSectionDirty(mc, pos);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void markSectionDirty(Minecraft mc, BlockPos pos) {
        // Force the chunk section containing this pos to recompile its mesh
        if (mc.levelRenderer != null) {
            mc.levelRenderer.setSectionDirtyWithNeighbors(
                    pos.getX() >> 4,
                    pos.getY() >> 4,
                    pos.getZ() >> 4
            );
        }
    }

    // ── Server-side factory ───────────────────────────────────────────────────

    /**
     * Build and broadcast a CrackSyncPacket for the given dirty positions.
     * Entries that no longer exist in the stateMap are sent as removals.
     */
    public static void sendChanges(ServerLevel level,
                                   CrackStateMap stateMap,
                                   Set<BlockPos> dirtyPositions) {
        List<CompoundTag> changes = new ArrayList<>();
        List<Long> removals = new ArrayList<>();

        for (BlockPos pos : dirtyPositions) {
            CrackEntry entry = stateMap.get(pos);
            if (entry != null && entry.hasCracks()) {
                changes.add(entry.save());
            } else {
                removals.add(pos.asLong());
            }
        }

        if (changes.isEmpty() && removals.isEmpty()) return;

        CrackSyncPacket packet = new CrackSyncPacket(changes, removals);
        CrackNetwork.CHANNEL.send(
                PacketDistributor.DIMENSION.with(level::dimension),
                packet
        );
    }

    /**
     * Send the full crack state for one chunk section to a single player
     * (called on chunk load).
     */
    public static void sendChunkLoad(ServerPlayer player,
                                     CrackStateMap stateMap,
                                     int chunkX, int chunkZ) {
        List<CompoundTag> changes = new ArrayList<>();
        List<Long> removals = new ArrayList<>();

        for (CrackEntry entry : stateMap.allEntries()) {
            BlockPos p = entry.pos();
            if ((p.getX() >> 4) == chunkX && (p.getZ() >> 4) == chunkZ) {
                if (entry.hasCracks()) {
                    changes.add(entry.save());
                }
            }
        }

        if (changes.isEmpty()) return;

        CrackSyncPacket packet = new CrackSyncPacket(changes, removals);
        CrackNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
