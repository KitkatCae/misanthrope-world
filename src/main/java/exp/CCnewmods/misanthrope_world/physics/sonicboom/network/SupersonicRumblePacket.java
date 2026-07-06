package exp.CCnewmods.misanthrope_world.physics.sonicboom.network;

import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.client.SupersonicRumblePlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Sent when a ship starts or stops being supersonic — a rising/falling edge
 * event, NOT a continuous sync. The client keeps the rumble looping and
 * tracks the ship's live position itself (see {@link SupersonicRumblePlayer}),
 * so this only needs to fire twice per supersonic period: once to start,
 * once to stop.
 *
 * <h3>Channel</h3>
 * Registered on MWorld's {@link CrackNetwork#CHANNEL} at ID 7 (0=crack sync,
 * 1-4=hull pressure, 5=reentry state, 6=sound barrier boom).
 */
public final class SupersonicRumblePacket {

    private static final int ID = 7;

    public final long shipId;
    public final boolean active;
    public final Vec3 shipPos;

    public SupersonicRumblePacket(long shipId, boolean active, Vec3 shipPos) {
        this.shipId = shipId;
        this.active = active;
        this.shipPos = shipPos;
    }

    public static void encode(SupersonicRumblePacket p, FriendlyByteBuf buf) {
        buf.writeLong(p.shipId);
        buf.writeBoolean(p.active);
        buf.writeDouble(p.shipPos.x);
        buf.writeDouble(p.shipPos.y);
        buf.writeDouble(p.shipPos.z);
    }

    public static SupersonicRumblePacket decode(FriendlyByteBuf buf) {
        long shipId = buf.readLong();
        boolean active = buf.readBoolean();
        Vec3 pos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new SupersonicRumblePacket(shipId, active, pos);
    }

    public static void handle(SupersonicRumblePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(pkt));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(SupersonicRumblePacket pkt) {
        SupersonicRumblePlayer.onPacket(pkt);
    }

    public static void register() {
        CrackNetwork.CHANNEL.registerMessage(ID, SupersonicRumblePacket.class,
                SupersonicRumblePacket::encode, SupersonicRumblePacket::decode, SupersonicRumblePacket::handle);
    }
}
