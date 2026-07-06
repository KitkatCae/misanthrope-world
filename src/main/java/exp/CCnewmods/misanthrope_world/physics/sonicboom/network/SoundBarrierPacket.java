package exp.CCnewmods.misanthrope_world.physics.sonicboom.network;

import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Sent server to client when a ship crosses Mach 1 (rising edge).
 *
 * <p>The client handler selects one of three sound variants based on the
 * listener's distance from {@link #origin}, and triggers the bloom post-effect.</p>
 *
 * <h3>Channel</h3>
 * Registered on MWorld's {@link CrackNetwork#CHANNEL} at ID 6 (0=crack sync,
 * 1-4=hull pressure, 5=reentry state).
 */
public final class SoundBarrierPacket {

    private static final int ID = 6;

    /** World-space position of the boom (ship nose approximation). */
    public final Vec3 origin;

    /**
     * Shockwave strength, same value passed to ShockwaveHandler.
     * Used to scale sound volume and bloom intensity.
     */
    public final float strength;

    /**
     * Atmospheric pressure scale factor [0, 1].
     * 1 = full standard atmosphere, < 1 = thinner air.
     * Used to mix between the "full boom" and "soft pop" variants.
     */
    public final float pressureScale;

    public SoundBarrierPacket(Vec3 origin, float strength, float pressureScale) {
        this.origin        = origin;
        this.strength      = strength;
        this.pressureScale = pressureScale;
    }

    // ── Network I/O ───────────────────────────────────────────────────────────

    public static void encode(SoundBarrierPacket p, FriendlyByteBuf buf) {
        buf.writeDouble(p.origin.x);
        buf.writeDouble(p.origin.y);
        buf.writeDouble(p.origin.z);
        buf.writeFloat(p.strength);
        buf.writeFloat(p.pressureScale);
    }

    public static SoundBarrierPacket decode(FriendlyByteBuf buf) {
        return new SoundBarrierPacket(
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readFloat(),
                buf.readFloat());
    }

    public static void handle(SoundBarrierPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(pkt));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(SoundBarrierPacket pkt) {
        exp.CCnewmods.misanthrope_world.physics.sonicboom.client.SoundBarrierClientHandler
                .onBoomPacket(pkt);
    }

    public static void register() {
        CrackNetwork.CHANNEL.registerMessage(ID, SoundBarrierPacket.class,
                SoundBarrierPacket::encode, SoundBarrierPacket::decode, SoundBarrierPacket::handle);
    }
}
