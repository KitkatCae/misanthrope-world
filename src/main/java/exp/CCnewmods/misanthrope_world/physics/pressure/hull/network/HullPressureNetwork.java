package exp.CCnewmods.misanthrope_world.physics.pressure.hull.network;

import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import exp.CCnewmods.misanthrope_world.physics.pressure.hull.client.HullDeformationRenderer;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.util.function.Supplier;

/**
 * All pressure-system server-to-client network packets in one file.
 *
 * <h3>Packet types</h3>
 * <ul>
 *   <li>{@link DeformPacket} — per-block elastic+inflation+stage deformation
 *       update. Sent when visual state changes by more than the dead-band.</li>
 *   <li>{@link StageAdvancePacket} — fired when a block's deformation stage
 *       increments. Triggers stage sound and particle burst client-side.</li>
 *   <li>{@link TensionPausePacket} — fired when the inter-stage pause begins.
 *       Client plays a low creak/groan sound and holds visual deformation.</li>
 *   <li>{@link BreachPacket} — fired on block destruction by pressure.
 *       Triggers breach animation, sound, and particle effects.</li>
 * </ul>
 *
 * <h3>Channel</h3>
 * Ported from MVSE, where these rode MVSE's own {@code MVSNetwork.CHANNEL} at
 * IDs 19-22 (after MVSE's other packets, up to VaporisePacket at 18). Now
 * registered on MWorld's existing {@link CrackNetwork#CHANNEL} instead of
 * creating a second channel — that channel currently only has
 * {@code CrackSyncPacket} at ID 0, so these take 1-4. If MWorld ever gets
 * more sync packets from a different system, pick IDs that don't collide
 * with 0-4 (this file) rather than relying on an auto-incrementing counter
 * shared across files, since these registrations don't all happen in one
 * place.
 *
 * <h3>Broadcast range</h3>
 * All packets use {@code PacketDistributor.NEAR} with a range of 128 blocks
 * from the ship world position. Players beyond 128 blocks won't see deformation
 * detail (it would be too small to notice anyway).
 */
public final class HullPressureNetwork {

    private HullPressureNetwork() {}

    private static final double BROADCAST_RANGE_SQ = 128.0 * 128.0;

    // =========================================================================
    // DeformPacket  (ID 1)
    // =========================================================================

    /**
     * Tells the client how much a hull block is visually deformed this tick.
     * Sent for every block whose visual state changed by more than the dead-band.
     *
     * <p>{@code deformAmount}: signed, fraction of one block.
     * Positive = outward (expansion). Negative = inward (crush).</p>
     *
     * <p>{@code inflationFraction}: [0,1] outward balloon expansion for
     * inflatable materials. 0 = original shape. 1 = fully inflated.</p>
     *
     * <p>{@code stage}: current plastic deformation stage [0, N].</p>
     */
    public record DeformPacket(
            long   shipId,
            BlockPos shipPos,
            float  deformAmount,
            float  inflationFraction,
            int    stage,
            float  deltaMbar
    ) {
        static final int ID = 1;

        public static void encode(DeformPacket p, FriendlyByteBuf b) {
            b.writeLong(p.shipId);
            b.writeBlockPos(p.shipPos);
            b.writeFloat(p.deformAmount);
            b.writeFloat(p.inflationFraction);
            b.writeVarInt(p.stage);
            b.writeFloat(p.deltaMbar);
        }

        public static DeformPacket decode(FriendlyByteBuf b) {
            return new DeformPacket(b.readLong(), b.readBlockPos(),
                    b.readFloat(), b.readFloat(), b.readVarInt(), b.readFloat());
        }

        public static void handle(DeformPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> HullDeformationRenderer.onDeformPacket(p));
            ctx.get().setPacketHandled(true);
        }

        public static void register() {
            CrackNetwork.CHANNEL.registerMessage(ID, DeformPacket.class,
                    DeformPacket::encode, DeformPacket::decode, DeformPacket::handle);
        }
    }

    // =========================================================================
    // StageAdvancePacket  (ID 2)
    // =========================================================================

    /**
     * Fires when a block advances to a new plastic deformation stage.
     * Client plays a stage-appropriate sound and particle burst.
     *
     * <p>Stage sounds (approximate mapping):</p>
     * <ul>
     *   <li>Stage 1 → {@code minecraft:block.iron_door.close} (metallic dent)</li>
     *   <li>Stage 2 → {@code minecraft:entity.iron_golem.hurt} (deep crunch)</li>
     *   <li>Stage 3 → {@code minecraft:block.anvil.land} (catastrophic crunch)</li>
     * </ul>
     */
    public record StageAdvancePacket(
            long   shipId,
            BlockPos shipPos,
            int    newStage,
            float  deltaMbar,
            BlockPhysicsData.PressureBreachMode breachMode
    ) {
        static final int ID = 2;

        public static void encode(StageAdvancePacket p, FriendlyByteBuf b) {
            b.writeLong(p.shipId);
            b.writeBlockPos(p.shipPos);
            b.writeVarInt(p.newStage);
            b.writeFloat(p.deltaMbar);
            b.writeVarInt(p.breachMode.ordinal());
        }

        public static StageAdvancePacket decode(FriendlyByteBuf b) {
            long shipId   = b.readLong();
            BlockPos pos  = b.readBlockPos();
            int stage     = b.readVarInt();
            float delta   = b.readFloat();
            var mode      = BlockPhysicsData.PressureBreachMode.values()[b.readVarInt()];
            return new StageAdvancePacket(shipId, pos, stage, delta, mode);
        }

        public static void handle(StageAdvancePacket p,
                                   Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> HullDeformationRenderer.onStageAdvance(p));
            ctx.get().setPacketHandled(true);
        }

        public static void register() {
            CrackNetwork.CHANNEL.registerMessage(ID, StageAdvancePacket.class,
                    StageAdvancePacket::encode, StageAdvancePacket::decode, StageAdvancePacket::handle);
        }
    }

    // =========================================================================
    // TensionPausePacket  (ID 3)
    // =========================================================================

    /**
     * Fires when a block enters the inter-stage tension pause.
     * Client plays a low creak/groan and freezes visual deformation.
     */
    public record TensionPausePacket(
            long   shipId,
            BlockPos shipPos,
            int    currentStage,
            float  deltaMbar
    ) {
        static final int ID = 3;

        public static void encode(TensionPausePacket p, FriendlyByteBuf b) {
            b.writeLong(p.shipId);
            b.writeBlockPos(p.shipPos);
            b.writeVarInt(p.currentStage);
            b.writeFloat(p.deltaMbar);
        }

        public static TensionPausePacket decode(FriendlyByteBuf b) {
            return new TensionPausePacket(b.readLong(), b.readBlockPos(),
                    b.readVarInt(), b.readFloat());
        }

        public static void handle(TensionPausePacket p,
                                   Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> HullDeformationRenderer.onTensionPause(p));
            ctx.get().setPacketHandled(true);
        }

        public static void register() {
            CrackNetwork.CHANNEL.registerMessage(ID, TensionPausePacket.class,
                    TensionPausePacket::encode, TensionPausePacket::decode, TensionPausePacket::handle);
        }
    }

    // =========================================================================
    // BreachPacket  (ID 4)
    // =========================================================================

    /**
     * Fires when a hull block is destroyed by pressure.
     * Client triggers breach animation (inward/outward particles,
     * rush-of-air or implosion sound, brief fog distortion).
     */
    public record BreachPacket(
            long   shipId,
            BlockPos shipPos,
            BlockPhysicsData.PressureBreachMode mode,
            float  deltaMbar
    ) {
        static final int ID = 4;

        public static void encode(BreachPacket p, FriendlyByteBuf b) {
            b.writeLong(p.shipId);
            b.writeBlockPos(p.shipPos);
            b.writeVarInt(p.mode.ordinal());
            b.writeFloat(p.deltaMbar);
        }

        public static BreachPacket decode(FriendlyByteBuf b) {
            long shipId  = b.readLong();
            BlockPos pos = b.readBlockPos();
            var mode     = BlockPhysicsData.PressureBreachMode.values()[b.readVarInt()];
            float delta  = b.readFloat();
            return new BreachPacket(shipId, pos, mode, delta);
        }

        public static void handle(BreachPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> HullDeformationRenderer.onBreach(p));
            ctx.get().setPacketHandled(true);
        }

        public static void register() {
            CrackNetwork.CHANNEL.registerMessage(ID, BreachPacket.class,
                    BreachPacket::encode, BreachPacket::decode, BreachPacket::handle);
        }
    }

    // =========================================================================
    // Server-side send helpers
    // =========================================================================

    public static void sendDeformPacket(ServerLevel level, LoadedServerShip ship,
                                  BlockPos shipPos, float deform,
                                  float inflation, int stage, float delta) {
        var origin = shipWorldOrigin(ship);
        CrackNetwork.CHANNEL.send(near(origin, level),
                new DeformPacket(ship.getId(), shipPos, deform, inflation, stage, delta));
    }

    public static void sendStageAdvancePacket(ServerLevel level, LoadedServerShip ship,
                                        BlockPos shipPos, int newStage, float delta,
                                        BlockPhysicsData.PressureBreachMode mode) {
        var origin = shipWorldOrigin(ship);
        CrackNetwork.CHANNEL.send(near(origin, level),
                new StageAdvancePacket(ship.getId(), shipPos, newStage, delta, mode));
    }

    public static void sendTensionPausePacket(ServerLevel level, LoadedServerShip ship,
                                        BlockPos shipPos, int stage, float delta) {
        var origin = shipWorldOrigin(ship);
        CrackNetwork.CHANNEL.send(near(origin, level),
                new TensionPausePacket(ship.getId(), shipPos, stage, delta));
    }

    public static void sendBreachPacket(ServerLevel level, LoadedServerShip ship,
                                  BlockPos shipPos,
                                  BlockPhysicsData.PressureBreachMode mode,
                                  float delta) {
        var origin = shipWorldOrigin(ship);
        CrackNetwork.CHANNEL.send(near(origin, level),
                new BreachPacket(ship.getId(), shipPos, mode, delta));
    }

    // =========================================================================
    // Registration
    // =========================================================================

    public static void register() {
        DeformPacket.register();
        StageAdvancePacket.register();
        TensionPausePacket.register();
        BreachPacket.register();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Vec3 shipWorldOrigin(LoadedServerShip ship) {
        var p = ship.getTransform().getPositionInWorld();
        return new Vec3(p.x(), p.y(), p.z());
    }

    private static PacketDistributor.PacketTarget near(Vec3 origin, ServerLevel level) {
        return PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                origin.x, origin.y, origin.z,
                BROADCAST_RANGE_SQ,
                level.dimension()));
    }
}
