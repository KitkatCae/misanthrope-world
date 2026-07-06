package exp.CCnewmods.misanthrope_world.physics.sonicboom.client;

import exp.CCnewmods.misanthrope_world.physics.sonicboom.network.SupersonicRumblePacket;
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
 * Plays {@link MisWorldSounds#SUPERSONIC_RUMBLE} as a continuous looping,
 * moving sound while a ship stays supersonic.
 *
 * <p>Requires an actual {@code assets/misanthrope_world/sounds/supersonic_rumble.ogg}
 * to exist — wired up ahead of the audio being ready, per request. Same
 * missing-sound caveat as {@code ReentryRoarPlayer}.</p>
 *
 * <p>Driven by {@link SupersonicRumblePacket} start/stop events rather than a
 * continuous sync — see that class's doc for why. Between start and stop,
 * this tracks the ship's live position itself via VS2's client-side
 * {@link ClientShip} lookup, same pattern as {@code PlasmaEdgeRenderer} and
 * {@code ReentryRoarPlayer}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SupersonicRumblePlayer {

    private SupersonicRumblePlayer() {
    }

    private static final Map<Long, RumbleSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    public static void onPacket(SupersonicRumblePacket pkt) {
        if (!pkt.active) {
            RumbleSoundInstance existing = ACTIVE.remove(pkt.shipId);
            if (existing != null) existing.requestStop();
            return;
        }

        // Already rumbling (e.g. a duplicate/late start packet) — don't
        // double-play.
        if (ACTIVE.containsKey(pkt.shipId)) return;

        RumbleSoundInstance instance = new RumbleSoundInstance(pkt.shipId,
                new Vec3(pkt.shipPos.x, pkt.shipPos.y, pkt.shipPos.z));
        ACTIVE.put(pkt.shipId, instance);
        Minecraft.getInstance().getSoundManager().play(instance);
    }

    private static final class RumbleSoundInstance extends AbstractTickableSoundInstance {

        private final long shipId;
        private volatile boolean stopRequested = false;

        RumbleSoundInstance(long shipId, Vec3 initialPos) {
            super(MisWorldSounds.SUPERSONIC_RUMBLE.get(), SoundSource.NEUTRAL, RandomSource.create());
            this.shipId = shipId;
            this.x = initialPos.x;
            this.y = initialPos.y;
            this.z = initialPos.z;
            this.looping = true;
            this.delay = 0;
            this.volume = 1.0f;
            this.pitch = 1.0f;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
        }

        void requestStop() {
            stopRequested = true;
        }

        @Override
        public void tick() {
            if (stopRequested) return; // isStopped() takes over from here

            ClientShip ship = findClientShip(Minecraft.getInstance(), shipId);
            if (ship != null) {
                var pos = ship.getTransform().getPositionInWorld();
                this.x = pos.x();
                this.y = pos.y();
                this.z = pos.z();
            }
            // If the ship's gone but no stop packet arrived (e.g. it was
            // destroyed rather than decelerating), keep playing at its last
            // known position — better than an abrupt cutoff, and the sound
            // system will naturally fall silent as the player moves away if
            // no one's there to hear it continue anyway.
        }

        @Override
        public boolean isStopped() {
            return stopRequested;
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
