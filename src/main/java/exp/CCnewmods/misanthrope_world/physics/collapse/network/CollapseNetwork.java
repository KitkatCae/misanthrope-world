package exp.CCnewmods.misanthrope_world.physics.collapse.network;

import exp.CCnewmods.misanthrope_world.physics.collapse.LatticeCollapseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Network channel for {@link LatticeCollapseBlockEntity} delta field sync.
 *
 * <p>Sends only changed density cells (delta compression). With an average of
 * ~6 changed cells per tick and updates every 4 ticks, bandwidth is about
 * 30 bytes per collapse per 4 ticks — negligible.
 */
public final class CollapseNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("misanthrope_core", "collapse_sync"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(id++,
                CollapseFieldDeltaPacket.class,
                CollapseFieldDeltaPacket::encode,
                CollapseFieldDeltaPacket::decode,
                CollapseFieldDeltaPacket::handle);
    }

    /**
     * Sends a delta packet to all players tracking the given position.
     */
    public static void sendDelta(ServerLevel level, BlockPos pos,
                                 byte[] indices, float[] values, int count) {
        CollapseFieldDeltaPacket pkt = new CollapseFieldDeltaPacket(pos, indices, values, count);
        CHANNEL.send(
                PacketDistributor.TRACKING_CHUNK.with(
                        () -> level.getChunk(pos.getX() >> 4, pos.getZ() >> 4)),
                pkt);
    }

    // ── Packet ────────────────────────────────────────────────────────────────

    public record CollapseFieldDeltaPacket(BlockPos pos, byte[] indices,
                                           float[] values, int count) {

        public static void encode(CollapseFieldDeltaPacket pkt, FriendlyByteBuf buf) {
            buf.writeBlockPos(pkt.pos);
            buf.writeByte(pkt.count);
            for (int i = 0; i < pkt.count; i++) {
                buf.writeByte(pkt.indices[i]);
                buf.writeFloat(pkt.values[i]);
            }
        }

        public static CollapseFieldDeltaPacket decode(FriendlyByteBuf buf) {
            BlockPos pos = buf.readBlockPos();
            int count = buf.readByte() & 0xFF;
            byte[] indices = new byte[count];
            float[] values = new float[count];
            for (int i = 0; i < count; i++) {
                indices[i] = buf.readByte();
                values[i] = buf.readFloat();
            }
            return new CollapseFieldDeltaPacket(pos, indices, values, count);
        }

        public static void handle(CollapseFieldDeltaPacket pkt,
                                  Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level == null) return;
                BlockEntity be = mc.level.getBlockEntity(pkt.pos);
                if (be instanceof LatticeCollapseBlockEntity lbe) {
                    lbe.applyFieldDelta(pkt.indices, pkt.values, pkt.count);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
