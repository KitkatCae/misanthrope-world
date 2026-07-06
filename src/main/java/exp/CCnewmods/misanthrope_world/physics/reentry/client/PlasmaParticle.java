package exp.CCnewmods.misanthrope_world.physics.reentry.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Plasma trail particle.
 *
 * <p>Visually matches the GIF reference: long directional streaks streaming
 * behind the ship, white-hot at the nose fading to orange then red then
 * transparent at the trail tips.</p>
 *
 * <h3>Motion</h3>
 * Spawned with an initial velocity that is the reverse of the ship's travel
 * direction, scaled by the ship's speed.  No gravity.  Slight outward spread
 * perpendicular to velocity gives the volumetric "widening wake" look.
 *
 * <h3>Colour ramp (by Mach / age)</h3>
 * <pre>
 *   age 0–20%   : white  (1.0, 1.0, 1.0)
 *   age 20–50%  : orange (1.0, 0.45, 0.0)
 *   age 50–80%  : red    (0.8, 0.1,  0.0)
 *   age 80–100% : fade to transparent red
 * </pre>
 *
 * The Mach-dependent tint passed in via {@code rData} / {@code gData} / {@code bData}
 * shifts the white point toward violet at extreme Mach.
 */
@OnlyIn(Dist.CLIENT)
public final class PlasmaParticle extends TextureSheetParticle {

    /** Particle size scales with this. Spawner sets it from ship AABB extent. */
    private final float baseScale;

    /** Mach-dependent tint for the white phase [0,1]. 0 = warm white, 1 = blue-violet. */
    private final float machTint;

    PlasmaParticle(ClientLevel level, double x, double y, double z,
                   double vx, double vy, double vz,
                   float baseScale, float machTint,
                   SpriteSet sprites) {
        super(level, x, y, z, vx, vy, vz);
        this.baseScale = baseScale;
        this.machTint  = machTint;
        this.gravity   = 0f;
        this.lifetime  = 20 + level.random.nextInt(20); // 20–40 ticks per streak
        this.quadSize  = baseScale * (0.4f + level.random.nextFloat() * 0.4f);
        this.hasPhysics = false;
        this.setSpriteFromAge(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) return;

        float lifeFrac = (float) age / (float) lifetime;

        // Colour ramp
        if (lifeFrac < 0.2f) {
            // White-hot (biased toward blue-violet at high mach)
            float t = lifeFrac / 0.2f;
            rCol = lerp(1.0f, 1.0f, t) - machTint * 0.4f * lifeFrac;
            gCol = lerp(1.0f, 0.8f, t) - machTint * 0.6f * lifeFrac;
            bCol = lerp(1.0f, 0.3f, t) + machTint * 0.5f;
        } else if (lifeFrac < 0.5f) {
            // Orange phase
            float t = (lifeFrac - 0.2f) / 0.3f;
            rCol = lerp(1.0f, 0.9f, t);
            gCol = lerp(0.8f, 0.2f, t);
            bCol = lerp(0.3f, 0.0f, t);
        } else if (lifeFrac < 0.8f) {
            // Red phase
            float t = (lifeFrac - 0.5f) / 0.3f;
            rCol = lerp(0.9f, 0.6f, t);
            gCol = lerp(0.2f, 0.05f, t);
            bCol = 0f;
        } else {
            // Fade out
            float t = (lifeFrac - 0.8f) / 0.2f;
            rCol = lerp(0.6f, 0.2f, t);
            gCol = lerp(0.05f, 0f, t);
            bCol = 0f;
            alpha = lerp(1.0f, 0f, t);
        }

        // Slight size taper toward end of life
        quadSize = baseScale * (1.0f - lifeFrac * 0.5f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ── Provider ──────────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    public static final class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type,
                                       ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            // vz is overloaded to carry machTint (see PlasmaTrailRenderer)
            float baseScale = 0.3f;
            float machTint  = (float) Math.abs(vz) > 10f ? (float)(vz / 100.0) : 0f;
            // Reinterpret vz as actual z-velocity when in normal range
            double realVz = Math.abs(vz) > 10f ? 0.0 : vz;
            return new PlasmaParticle(level, x, y, z, vx, vy, realVz, baseScale, machTint, sprites);
        }
    }
}
