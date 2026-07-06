package exp.CCnewmods.misanthrope_world.physics.reentry.client;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.physics.reentry.network.ReentryStatePacket;
import exp.CCnewmods.misanthrope_world.registry.MisWorldParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side manager for plasma trail rendering.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Maintains a per-ship {@link ClientReentryState} map updated by
 *       incoming {@link ReentryStatePacket}s.</li>
 *   <li>Each client tick: spawns {@link PlasmaParticle}s from the ship's
 *       leading-edge position, streaming backward along the velocity vector,
 *       with spread proportional to ship AABB size and intensity.</li>
 *   <li>When the local player is inside or near a reentering ship:
 *       applies orange–red fog tint via {@link ViewportEvent.ComputeFogColor}
 *       and compresses the near plane.</li>
 *   <li>State expires after {@code EXPIRY_TICKS} without a packet update.</li>
 * </ul>
 *
 * <h3>Particle count</h3>
 * Scales with intensity.  At Mach 5 (intensity ≈ 0.05): ~2 particles/tick.
 * At Mach 208 (intensity = 1.0): ~60 particles/tick from the leading edge.
 * This matches the GIF — dense, volumetric, long streaks filling the wake.
 *
 * <h3>Colour tier by Mach</h3>
 * <pre>
 *   Mach  5–12  → orange  (machTint = 0)
 *   Mach 12–25  → yellow-white (machTint = 0.3)
 *   Mach 25–80  → white   (machTint = 0.6)
 *   Mach 80+    → blue-violet plasma (machTint = 1.0)
 * </pre>
 * machTint is passed to {@link PlasmaParticle} via the vz parameter slot
 * when the value exceeds 10 (indicating it is a tint, not a velocity).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Misanthrope_world.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PlasmaTrailRenderer {

    private PlasmaTrailRenderer() {}

    // ── State ─────────────────────────────────────────────────────────────────

    private static final Map<Long, ClientReentryState> STATES = new ConcurrentHashMap<>();

    /** Ticks without a packet before a ship's trail is removed. */
    private static final int EXPIRY_TICKS = 20;

    /** RNG for particle spread. */
    private static final Random RNG = new Random();

    private static record ClientReentryState(
            float intensity,
            Vec3 shipPos,
            Vec3 velDir,
            float mach,
            int age    // ticks since last packet
    ) {
        ClientReentryState withAge(int a) {
            return new ClientReentryState(intensity, shipPos, velDir, mach, a);
        }
    }

    // ── Packet receiver ───────────────────────────────────────────────────────

    public static void onStatePacket(ReentryStatePacket pkt) {
        STATES.put(pkt.shipId, new ClientReentryState(
                pkt.intensity, pkt.shipPos, pkt.velDir, pkt.mach, 0));
    }

    // ── Client tick ───────────────────────────────────────────────────────────

    /**
     * Called from {@code exp.CCnewmods.misanthrope_world.VSPhysicsClientEvents}
     * every client tick.
     */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Iterator<Map.Entry<Long, ClientReentryState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ClientReentryState state = entry.getValue();

            // Age the state
            int newAge = state.age() + 1;
            if (newAge > EXPIRY_TICKS) {
                it.remove();
                continue;
            }
            entry.setValue(state.withAge(newAge));

            if (state.intensity() < 0.005f) continue;

            // Spawn particles
            spawnTrailParticles(mc, state);
        }
    }

    // ── Particle spawning ─────────────────────────────────────────────────────

    private static void spawnTrailParticles(Minecraft mc, ClientReentryState state) {
        float intensity = state.intensity();
        Vec3  shipPos   = state.shipPos();
        Vec3  velDir    = state.velDir();
        float mach      = state.mach();

        // Number of particles this tick — scales with intensity
        int count = Math.max(1, (int) (intensity * 60));

        // MachTint: 0 (orange) → 1 (blue-violet)
        float machTint;
        if (mach < 12f)      machTint = 0f;
        else if (mach < 25f) machTint = (mach - 12f) / 13f * 0.3f;
        else if (mach < 80f) machTint = 0.3f + (mach - 25f) / 55f * 0.3f;
        else                 machTint = Math.min(1.0f, 0.6f + (mach - 80f) / 128f * 0.4f);

        // Trail speed: particles fly backward at (intensity * 3 + 0.5) blocks/tick
        double trailSpeed = intensity * 3.0 + 0.5;

        for (int i = 0; i < count; i++) {
            // Spawn at ship position with slight spread perpendicular to velocity
            double spreadR = intensity * 2.0; // blocks of spread radius
            double spreadX = (RNG.nextDouble() - 0.5) * 2 * spreadR;
            double spreadY = (RNG.nextDouble() - 0.5) * 2 * spreadR;
            double spreadZ = (RNG.nextDouble() - 0.5) * 2 * spreadR;

            // Cancel out spread along velocity direction (keep spread perpendicular)
            double dot = spreadX * velDir.x + spreadY * velDir.y + spreadZ * velDir.z;
            spreadX -= dot * velDir.x;
            spreadY -= dot * velDir.y;
            spreadZ -= dot * velDir.z;

            double px = shipPos.x + spreadX;
            double py = shipPos.y + spreadY;
            double pz = shipPos.z + spreadZ;

            // Velocity: mostly backward along velDir, with tiny random jitter
            double jitter = 0.05;
            double vx = -velDir.x * trailSpeed + (RNG.nextDouble() - 0.5) * jitter;
            double vy = -velDir.y * trailSpeed + (RNG.nextDouble() - 0.5) * jitter;
            // Encode machTint in vz slot: use value > 10 as sentinel
            // PlasmaParticle.Provider handles this interpretation
            double vzEncoded = machTint * 100.0 + 11.0; // always > 10

            mc.level.addParticle(MisWorldParticles.PLASMA_TRAIL.get(),
                    px, py, pz, vx, vy, vzEncoded);
        }
    }

    // ── Fog tint (player on/near reentering ship) ─────────────────────────────

    /**
     * Current combined fog intensity from all nearby reentering ships.
     * 0 = no tint, 1 = full orange-red fog.
     */
    private static float currentFogIntensity = 0f;

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (currentFogIntensity <= 0f) return;

        float t = currentFogIntensity;
        // Orange-red tint: push R up, push G/B down
        event.setRed  (Math.min(1f, event.getRed()   + t * 0.6f));
        event.setGreen(Math.max(0f, event.getGreen() - t * 0.3f));
        event.setBlue (Math.max(0f, event.getBlue()  - t * 0.5f));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (currentFogIntensity < 0.1f) return;
        // Compress near-plane to create "heat shimmer" close to camera
        event.setNearPlaneDistance(event.getNearPlaneDistance() * (1f - currentFogIntensity * 0.3f));
        event.setCanceled(true);
    }

    /**
     * Called by VSPhysicsClientEvents.onClientTick to update fog
     * intensity from the current player distance to all reentering ships.
     */
    public static void updateFogIntensity(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            currentFogIntensity = 0f;
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        float maxIntensity = 0f;
        for (ClientReentryState state : STATES.values()) {
            if (state.intensity() < 0.01f) continue;
            double dist = eye.distanceTo(state.shipPos());
            // Full intensity within 8 blocks, fades to 0 at 50 blocks
            if (dist < 50.0) {
                float proximityFactor = (float) Math.max(0, 1.0 - dist / 50.0);
                maxIntensity = Math.max(maxIntensity, state.intensity() * proximityFactor);
            }
        }
        // Smooth the transition
        if (maxIntensity > currentFogIntensity) {
            currentFogIntensity = Math.min(maxIntensity, currentFogIntensity + 0.05f);
        } else {
            currentFogIntensity = Math.max(maxIntensity, currentFogIntensity - 0.08f);
        }
    }
}
