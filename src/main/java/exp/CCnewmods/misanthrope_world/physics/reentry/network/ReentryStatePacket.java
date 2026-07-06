package exp.CCnewmods.misanthrope_world.physics.reentry.network;

import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import exp.CCnewmods.misanthrope_world.physics.reentry.client.PlasmaTrailRenderer;
import exp.CCnewmods.misanthrope_world.physics.reentry.client.PlasmaEdgeRenderer;
import exp.CCnewmods.misanthrope_world.physics.reentry.client.ReentryRoarPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Periodic S2C packet carrying kinetic heating state for one ship.
 *
 * <p>Sent every {@code clientSyncTickInterval} ticks to all players within
 * {@code clientSyncRadius} of the ship.  Intensity 0 signals the client to
 * stop rendering the plasma trail for this ship.</p>
 *
 * <h3>Channel</h3>
 * Registered on MWorld's existing {@link CrackNetwork#CHANNEL} at ID 5 —
 * {@code CrackSyncPacket} has 0, the hull pressure packets have 1-4 (see
 * {@code HullPressureNetwork}'s class doc for why IDs aren't centrally
 * auto-incremented across files).
 */
public final class ReentryStatePacket {

    private static final int ID = 5;

    /** Ship ID — used to key client-side render state. */
    public final long shipId;

    /**
     * Heating intensity [0, 1].
     * 0 = subsonic/no heating, 1 = max Mach plasma.
     */
    public final float intensity;

    /** World-space position of the ship's transform origin (approximate nose). */
    public final Vec3 shipPos;

    /**
     * Normalised velocity direction (world-space).
     * Trail particles stream opposite to this.
     */
    public final Vec3 velDir;

    /** Current Mach number — used to pick colour tier on the client. */
    public final float mach;

    public ReentryStatePacket(long shipId, float intensity, Vec3 shipPos, Vec3 velDir, float mach) {
        this.shipId    = shipId;
        this.intensity = intensity;
        this.shipPos   = shipPos;
        this.velDir    = velDir;
        this.mach      = mach;
    }

    // ── Network I/O ───────────────────────────────────────────────────────────

    public static void encode(ReentryStatePacket p, FriendlyByteBuf buf) {
        buf.writeLong(p.shipId);
        buf.writeFloat(p.intensity);
        buf.writeDouble(p.shipPos.x);
        buf.writeDouble(p.shipPos.y);
        buf.writeDouble(p.shipPos.z);
        buf.writeFloat((float) p.velDir.x);
        buf.writeFloat((float) p.velDir.y);
        buf.writeFloat((float) p.velDir.z);
        buf.writeFloat(p.mach);
    }

    public static ReentryStatePacket decode(FriendlyByteBuf buf) {
        long  shipId    = buf.readLong();
        float intensity = buf.readFloat();
        Vec3  shipPos   = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3  velDir    = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
        float mach      = buf.readFloat();
        return new ReentryStatePacket(shipId, intensity, shipPos, velDir, mach);
    }

    public static void handle(ReentryStatePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(pkt));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(ReentryStatePacket pkt) {
        // Previously PlasmaTrailRenderer/PlasmaEdgeRenderer.onStatePacket were
        // ALSO called directly from handle() above, off the network thread,
        // in addition to here (PlasmaTrailRenderer was double-called; only
        // PlasmaEdgeRenderer's call was off-thread only). Consolidated to one
        // call each, on the main thread, where client rendering state should
        // actually be touched.
        PlasmaTrailRenderer.onStatePacket(pkt);
        PlasmaEdgeRenderer.onStatePacket(pkt);
        ReentryRoarPlayer.onStatePacket(pkt);
    }

    public static void register() {
        CrackNetwork.CHANNEL.registerMessage(ID, ReentryStatePacket.class,
                ReentryStatePacket::encode, ReentryStatePacket::decode, ReentryStatePacket::handle);
    }
}
