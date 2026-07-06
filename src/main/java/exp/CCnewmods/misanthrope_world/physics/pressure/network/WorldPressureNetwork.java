package exp.CCnewmods.misanthrope_world.physics.pressure.network;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.pressure.client.WorldBlockDeformRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Network channel for world-space pressure deformation events.
 *
 * <p>Four packet types — mirroring the ship-keyed packets in MVSE's
 * {@code HullPressureNetwork} but keyed by world-space {@link BlockPos}
 * instead of {@code shipId + shipPos}. Sent to all players who have the
 * relevant chunk loaded (via {@link PacketDistributor#TRACKING_CHUNK}).
 *
 * <h3>Registration</h3>
 * Call {@link #register()} from {@code Misanthrope_world.commonSetup}
 * inside {@code event.enqueueWork()}.
 */
public final class WorldPressureNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("misanthrope_world", "pressure"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int nextId = 0;

    public static void register() {
        CHANNEL.registerMessage(nextId++, DeformPacket.class,
                DeformPacket::encode, DeformPacket::decode, DeformPacket::handle);
        CHANNEL.registerMessage(nextId++, StageAdvancePacket.class,
                StageAdvancePacket::encode, StageAdvancePacket::decode, StageAdvancePacket::handle);
        CHANNEL.registerMessage(nextId++, TensionPausePacket.class,
                TensionPausePacket::encode, TensionPausePacket::decode, TensionPausePacket::handle);
        CHANNEL.registerMessage(nextId++, BreachPacket.class,
                BreachPacket::encode, BreachPacket::decode, BreachPacket::handle);
    }

    // ── Server-side send helpers ──────────────────────────────────────────────

    public static void sendDeformPacket(ServerLevel level, BlockPos pos,
                                         float deformAmount, float inflationFraction,
                                         int stage, float deltaMbar) {
        CHANNEL.send(
                PacketDistributor.TRACKING_CHUNK.with(
                        () -> level.getChunkAt(pos)),
                new DeformPacket(pos, deformAmount, inflationFraction, stage, deltaMbar));
    }

    public static void sendStageAdvancePacket(ServerLevel level, BlockPos pos,
                                               int newStage, float deltaMbar,
                                               BlockPhysicsData.PressureBreachMode mode) {
        CHANNEL.send(
                PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(pos)),
                new StageAdvancePacket(pos, newStage, deltaMbar, mode));
    }

    public static void sendTensionPausePacket(ServerLevel level, BlockPos pos,
                                               int currentStage, float deltaMbar) {
        CHANNEL.send(
                PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(pos)),
                new TensionPausePacket(pos, currentStage, deltaMbar));
    }

    public static void sendBreachPacket(ServerLevel level, BlockPos pos,
                                         BlockPhysicsData.PressureBreachMode mode,
                                         float deltaMbar) {
        CHANNEL.send(
                PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(pos)),
                new BreachPacket(pos, mode, deltaMbar));
    }

    // ── Packet records ────────────────────────────────────────────────────────

    /** Elastic/inflation state changed — update visual deformation. */
    public record DeformPacket(
            BlockPos pos,
            float deformAmount,
            float inflationFraction,
            int stage,
            float deltaMbar
    ) {
        public static void encode(DeformPacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos());
            b.writeFloat(p.deformAmount());
            b.writeFloat(p.inflationFraction());
            b.writeVarInt(p.stage());
            b.writeFloat(p.deltaMbar());
        }
        public static DeformPacket decode(FriendlyByteBuf b) {
            return new DeformPacket(b.readBlockPos(), b.readFloat(),
                    b.readFloat(), b.readVarInt(), b.readFloat());
        }
        public static void handle(DeformPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> WorldBlockDeformRenderer.onDeformPacket(p));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Deformation stage advanced after tension pause. */
    public record StageAdvancePacket(
            BlockPos pos,
            int newStage,
            float deltaMbar,
            BlockPhysicsData.PressureBreachMode breachMode
    ) {
        public static void encode(StageAdvancePacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos());
            b.writeVarInt(p.newStage());
            b.writeFloat(p.deltaMbar());
            b.writeVarInt(p.breachMode().ordinal());
        }
        public static StageAdvancePacket decode(FriendlyByteBuf b) {
            return new StageAdvancePacket(b.readBlockPos(), b.readVarInt(),
                    b.readFloat(),
                    BlockPhysicsData.PressureBreachMode.values()[b.readVarInt()]);
        }
        public static void handle(StageAdvancePacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> WorldBlockDeformRenderer.onStageAdvance(p));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Block entered inter-stage tension pause — play groan, start flicker. */
    public record TensionPausePacket(
            BlockPos pos,
            int currentStage,
            float deltaMbar
    ) {
        public static void encode(TensionPausePacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos());
            b.writeVarInt(p.currentStage());
            b.writeFloat(p.deltaMbar());
        }
        public static TensionPausePacket decode(FriendlyByteBuf b) {
            return new TensionPausePacket(b.readBlockPos(), b.readVarInt(), b.readFloat());
        }
        public static void handle(TensionPausePacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> WorldBlockDeformRenderer.onTensionPause(p));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Block breached — trigger client visual/sound effect. */
    public record BreachPacket(
            BlockPos pos,
            BlockPhysicsData.PressureBreachMode mode,
            float deltaMbar
    ) {
        public static void encode(BreachPacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos());
            b.writeVarInt(p.mode().ordinal());
            b.writeFloat(p.deltaMbar());
        }
        public static BreachPacket decode(FriendlyByteBuf b) {
            return new BreachPacket(b.readBlockPos(),
                    BlockPhysicsData.PressureBreachMode.values()[b.readVarInt()],
                    b.readFloat());
        }
        public static void handle(BreachPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> WorldBlockDeformRenderer.onBreach(p));
            ctx.get().setPacketHandled(true);
        }
    }
}
