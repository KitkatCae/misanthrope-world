package exp.CCnewmods.misanthrope_world.physics.reentry.client;

import exp.CCnewmods.misanthrope_world.physics.reentry.network.ReentryStatePacket;
import exp.CCnewmods.misanthrope_world.registry.MisWorldSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays {@link MisWorldSounds#REENTRY_ROAR} as a looping, moving sound while
 * a ship has nonzero kinetic-heating intensity, scaling volume/pitch with it.
 *
 * <p>Requires an actual {@code assets/misanthrope_world/sounds/reentry_roar.ogg}
 * to exist — wired up ahead of the audio being ready, per request. Until the
 * file is dropped in, this will try to play a sound that resolves to nothing
 * audible (Minecraft logs a missing-sound warning, doesn't crash).</p>
 *
 * <h3>Why no new network packet</h3>
 * {@link ReentryStatePacket} already syncs {@code intensity}/{@code mach} for
 * the plasma trail/edge renderers at whatever tick interval
 * {@code AerodynamicsConfig.clientSyncTickInterval} specifies — this just
 * reads the same packet instead of asking the server for anything new.
 *
 * <h3>Following a moving ship</h3>
 * Uses the same {@code ClientShip} lookup pattern already established in
 * {@code PlasmaEdgeRenderer} — the sound instance's {@code tick()} re-queries
 * the ship's current world position each client tick, rather than trusting
 * the packet's snapshot position (which goes stale between syncs since the
 * ship keeps moving).
 */
@OnlyIn(Dist.CLIENT)
public final class ReentryRoarPlayer {

    private ReentryRoarPlayer() {
    }

    private static final Map<Long, RoarSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    /** Below this intensity, the roar stops entirely rather than fading to silence. */
    private static final float STOP_THRESHOLD = 0.03f;

    public static void onStatePacket(ReentryStatePacket pkt) {
        if (pkt.intensity < STOP_THRESHOLD) {
            RoarSoundInstance existing = ACTIVE.remove(pkt.shipId);
            if (existing != null) existing.requestStop();
            return;
        }

        RoarSoundInstance instance = ACTIVE.get(pkt.shipId);
        if (instance == null || instance.isStopped()) {
            instance = new RoarSoundInstance(pkt.shipId, new Vec3(pkt.shipPos.x, pkt.shipPos.y, pkt.shipPos.z));
            ACTIVE.put(pkt.shipId, instance);
            Minecraft.getInstance().getSoundManager().play(instance);
        }
        instance.updateTarget(pkt.intensity, pkt.mach);
    }

    /**
     * Tickable, positional, looping roar sound. Volume scales with intensity
     * [0,1]; pitch scales gently with Mach number so a faster pass sounds
     * higher/more strained without becoming cartoonish.
     */
    private static final class RoarSoundInstance extends AbstractTickableSoundInstance {

        private final long shipId;
        private volatile float targetVolume = 0f;
        private volatile float targetPitch = 1f;
        private volatile boolean stopRequested = false;

        RoarSoundInstance(long shipId, Vec3 initialPos) {
            super(MisWorldSounds.REENTRY_ROAR.get(), SoundSource.NEUTRAL, RandomSource.create());
            this.shipId = shipId;
            this.x = initialPos.x;
            this.y = initialPos.y;
            this.z = initialPos.z;
            this.looping = true;
            this.delay = 0;
            this.volume = 0f;
            this.pitch = 1f;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
        }

        void updateTarget(float intensity, float mach) {
            this.targetVolume = Math.min(1.5f, intensity);
            this.targetPitch = (float) Math.max(0.6, Math.min(1.4, 0.8 + Math.log1p(mach) * 0.08));
        }

        void requestStop() {
            stopRequested = true;
        }

        @Override
        public void tick() {
            if (stopRequested) {
                this.volume = 0f;
                return;
            }

            // Ease toward target volume/pitch instead of snapping, so periodic
            // packet updates don't cause audible stepping.
            this.volume += (targetVolume - this.volume) * 0.2f;
            this.pitch += (targetPitch - this.pitch) * 0.1f;

            ClientShip ship = findClientShip(Minecraft.getInstance(), shipId);
            if (ship != null) {
                var pos = ship.getTransform().getPositionInWorld();
                this.x = pos.x();
                this.y = pos.y();
                this.z = pos.z();
            }
            // If the ship's gone, keep playing at its last known position and
            // let the intensity->0 packet (or lack of further packets timing
            // out via requestStop from elsewhere) bring it down naturally.
        }

        @Override
        public boolean isStopped() {
            return stopRequested && this.volume <= 0.001f;
        }

        private static ClientShip findClientShip(Minecraft mc, long shipId) {
            if (mc.level == null) return null;
            try {
                var shipWorld = VSGameUtilsKt.getShipWorldNullable(mc.level);
                if (shipWorld == null) return null;
                var allShips = shipWorld.getAllShips();
                if (allShips == null) return null;
                var ship = allShips.getById(shipId);
                return (ship instanceof ClientShip cs) ? cs : null;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
