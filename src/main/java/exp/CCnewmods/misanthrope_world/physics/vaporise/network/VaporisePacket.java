package exp.CCnewmods.misanthrope_world.physics.vaporise.network;

import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import exp.CCnewmods.misanthrope_world.physics.vaporise.client.VaporiseClientHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Server → Client packet sent when a ship vaporises on impact.
 *
 * <p>Carries the world-space origin of the vaporisation event and an intensity
 * value derived from the ship's kinetic energy at the moment of vaporisation.
 * The client uses this to:</p>
 * <ul>
 *   <li>Trigger the {@link VaporiseFlashRenderer} screen flash</li>
 *   <li>Spawn the Photon {@code mvs:fx/vaporise} particle effect at the origin</li>
 *   <li>Spawn the in-world {@link VaporiseBillboardRenderer} expanding plasma sphere</li>
 * </ul>
 *
 * <h3>Intensity scale</h3>
 * {@code intensity = sqrt(0.5 * mass * speed^2) / 1e6}, clamped [0.1, 10.0].
 * At intensity 1.0: visible flash, 8-block radius glow sphere, ~2s duration.
 * At intensity 10.0: blinding flash, 32-block radius, ~5s duration.
 *
 * <h3>Registration</h3>
 * Registered via {@link #register()}, called from MWorld's commonSetup.
 * Rides MWorld's existing {@code CrackNetwork#CHANNEL} at ID 8 (0=crack sync,
 * 1-4=hull pressure, 5=reentry state, 6=sound barrier boom, 7=supersonic
 * rumble start/stop).
 */
public final class VaporisePacket {

    private static final int ID = 8;

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    public final Vec3    origin;
    /** Kinetic energy proxy [0.1, 10.0] — drives all visual scale parameters. */
    public final float   intensity;
    /** True if Photon mod is present on the server side (pre-check avoids client load). */
    public final boolean photonAvailable;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public VaporisePacket(Vec3 origin, float intensity, boolean photonAvailable) {
        this.origin          = origin;
        this.intensity       = intensity;
        this.photonAvailable = photonAvailable;
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static void encode(VaporisePacket p, FriendlyByteBuf buf) {
        buf.writeDouble(p.origin.x);
        buf.writeDouble(p.origin.y);
        buf.writeDouble(p.origin.z);
        buf.writeFloat(p.intensity);
        buf.writeBoolean(p.photonAvailable);
    }

    public static VaporisePacket decode(FriendlyByteBuf buf) {
        Vec3  origin    = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float intensity = buf.readFloat();
        boolean photon  = buf.readBoolean();
        return new VaporisePacket(origin, intensity, photon);
    }

    // -------------------------------------------------------------------------
    // Handler (client main thread)
    // -------------------------------------------------------------------------

    public static void handle(VaporisePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(p));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(VaporisePacket p) {
        VaporiseClientHandler.onVaporise(p.origin, p.intensity, p.photonAvailable);
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public static void register() {
        CrackNetwork.CHANNEL.registerMessage(ID, VaporisePacket.class,
                VaporisePacket::encode, VaporisePacket::decode, VaporisePacket::handle);
    }

    // -------------------------------------------------------------------------
    // Server-side send helper
    // -------------------------------------------------------------------------

    /**
     * Broadcasts the vaporisation event to all clients within range of the
     * origin. Range is {@code max(64, intensity * 24)} blocks.
     *
     * @param level      the server level
     * @param origin     world-space vaporisation point
     * @param mass       ship mass in VS2 kg
     * @param speedMs    ship speed at vaporisation in m/s
     * @param photon     whether Photon mod is loaded on this server
     */
    public static void sendToNear(ServerLevel level, Vec3 origin,
                                   double mass, double speedMs, boolean photon) {
        double ke        = 0.5 * mass * speedMs * speedMs;
        float  intensity = (float) Math.max(0.1, Math.min(10.0, Math.sqrt(ke) / 1e6));
        double range     = Math.max(64.0, intensity * 24.0);

        CrackNetwork.CHANNEL.send(
                PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                        origin.x, origin.y, origin.z,
                        range * range,
                        level.dimension())),
                new VaporisePacket(origin, intensity, photon));
    }
}
