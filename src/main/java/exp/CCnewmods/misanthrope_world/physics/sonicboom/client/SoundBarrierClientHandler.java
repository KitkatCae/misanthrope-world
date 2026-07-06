package exp.CCnewmods.misanthrope_world.physics.sonicboom.client;

import exp.CCnewmods.misanthrope_world.physics.sonicboom.network.SoundBarrierPacket;
import exp.CCnewmods.misanthrope_world.registry.MisWorldSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side receiver for {@link SoundBarrierPacket}.
 *
 * <h3>Sound variants</h3>
 * Mirrors Supersonic mod's distance-based selection:
 * <ul>
 *   <li>&lt; 32 blocks  → {@code sonic_boom_close}  (loud, bass-heavy)</li>
 *   <li>32–128 blocks   → {@code sonic_boom_medium}</li>
 *   <li>&gt; 128 blocks → {@code sonic_boom_far}    (soft, distant rumble)</li>
 * </ul>
 *
 * All three use the sound events registered in {@link exp.CCnewmods.misanthrope_world.registry.MisWorldSounds}.
 * The actual .ogg files come from the supersonic mod's jar assets, so the
 * sounds.json in MVS references them as {@code supersonic:sonic_boom_close}
 * etc. until custom audio is provided.
 *
 * <h3>Bloom post-effect</h3>
 * A brief white flash is applied by posting a timed overlay to
 * {@link SoundBarrierPostEffect}, which uses a simple alpha-timed white quad
 * overlay rendered by {@link AerodynamicsClientEvents}.
 */
@OnlyIn(Dist.CLIENT)
public final class SoundBarrierClientHandler {

    private SoundBarrierClientHandler() {}

    public static void onBoomPacket(SoundBarrierPacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Vec3 boomPos = pkt.origin;
        Vec3 playerPos = mc.player.getEyePosition();
        double dist = playerPos.distanceTo(boomPos);

        // Select sound variant
        net.minecraft.sounds.SoundEvent sound;
        float volume;
        float pitch = 0.9f + mc.level.random.nextFloat() * 0.2f;

        if (dist < 32.0) {
            sound  = MisWorldSounds.SONIC_BOOM_CLOSE.get();
            volume = Math.min(4.0f, pkt.strength * 0.8f * pkt.pressureScale);
        } else if (dist < 128.0) {
            sound  = MisWorldSounds.SONIC_BOOM_MEDIUM.get();
            volume = Math.min(3.0f, pkt.strength * 0.6f * pkt.pressureScale);
        } else {
            sound  = MisWorldSounds.SONIC_BOOM_FAR.get();
            volume = Math.min(2.0f, pkt.strength * 0.4f * pkt.pressureScale);
        }

        // Play positional sound at boom origin
        SoundInstance instance = SimpleSoundInstance.forUI(sound, volume, pitch);
        mc.getSoundManager().play(instance);

        // Trigger bloom flash — intensity based on proximity and pressure
        float bloomIntensity = (float) Math.max(0.0, 1.0 - dist / 64.0)
                               * pkt.strength * 0.3f * pkt.pressureScale;
        if (bloomIntensity > 0.02f) {
            SoundBarrierPostEffect.triggerBloom(bloomIntensity);
        }
    }
}
