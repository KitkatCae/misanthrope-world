package exp.CCnewmods.misanthrope_world.physics.vaporise.client;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side screen flash for vaporisation events.
 *
 * <p>Manages a short-lived brightness surge by:</p>
 * <ul>
 *   <li>Pushing fog colour toward white via
 *       {@link ViewportEvent.ComputeFogColor}</li>
 *   <li>Collapsing the near and far fog planes to zero via
 *       {@link ViewportEvent.RenderFog}, making the world appear washed out
 *       in bright light</li>
 * </ul>
 *
 * <h3>Flash curve</h3>
 * The flash decays exponentially: peak at trigger, then {@code strength *= DECAY}
 * per tick until below the cutoff threshold. Duration scales with intensity:
 * intensity 1.0 → ~12 ticks (0.6s); intensity 10.0 → ~24 ticks (1.2s) due to
 * the higher starting value needing more decay steps to reach the cutoff.
 *
 * <h3>Multiple simultaneous flashes</h3>
 * If a second vaporisation occurs while a flash is in progress, the new
 * strength is added to the current (capped at {@link #MAX_STRENGTH}). Flashes
 * stack additively so nearby simultaneous explosions compound correctly.
 *
 * <h3>Lifecycle</h3>
 * {@link #clientTick()} must be called each client tick from
 * {@code exp.CCnewmods.misanthrope_world.VSPhysicsClientEvents}.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(
        modid = Misanthrope_world.MODID,
        bus   = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class VaporiseFlashRenderer {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Exponential decay per tick — controls flash duration. */
    private static final float DECAY        = 0.82f;
    /** Below this strength, the flash is considered over. */
    private static final float CUTOFF       = 0.02f;
    /** Maximum flash strength regardless of additive stacking. */
    private static final float MAX_STRENGTH = 8.0f;

    // -------------------------------------------------------------------------
    // State (single-threaded client tick)
    // -------------------------------------------------------------------------

    private static float currentStrength = 0f;

    private VaporiseFlashRenderer() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Triggers a flash with the given strength. Additive with any ongoing flash.
     * Called from {@link VaporiseClientHandler#onVaporise}.
     *
     * @param strength distance- and intensity-scaled flash strength [0.05, ~10]
     */
    public static void triggerFlash(float strength) {
        currentStrength = Math.min(MAX_STRENGTH, currentStrength + strength);
    }

    /**
     * Advances flash decay. Call once per client tick from
     * {@code MVSClientEvents.onClientTick}.
     */
    public static void clientTick() {
        if (currentStrength <= CUTOFF) {
            currentStrength = 0f;
            return;
        }
        currentStrength *= DECAY;
    }

    // -------------------------------------------------------------------------
    // Render hooks
    // -------------------------------------------------------------------------

    /**
     * Tints the fog colour toward blinding white proportional to strength.
     * At full strength (8.0), the tint is solid white. At 0.1, it is a faint
     * warm white (slightly yellow-white, like a distant plasma flash).
     */
    @SubscribeEvent
    public static void onFogColour(ViewportEvent.ComputeFogColor event) {
        if (currentStrength <= CUTOFF) return;

        // Normalised [0,1] where 1 = MAX_STRENGTH
        float t = Math.min(1.0f, currentStrength / MAX_STRENGTH);

        // Blend toward warm white: (1.0, 0.95, 0.85) — plasma is not pure blue-white
        float r = lerp(event.getRed(),   1.00f, t);
        float g = lerp(event.getGreen(), 0.95f, t);
        float b = lerp(event.getBlue(),  0.85f, t);

        event.setRed(r);
        event.setGreen(g);
        event.setBlue(b);
    }

    /**
     * Collapses fog planes to create the washed-out "everything white" effect.
     * At low strength, only near plane is affected (subtle brightness).
     * At high strength, far plane also collapses (total whiteout).
     */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (currentStrength <= CUTOFF) return;

        float t = Math.min(1.0f, currentStrength / MAX_STRENGTH);

        // Near plane: always driven toward zero for the brightness effect
        float nearFactor = 1.0f - t * 0.98f;
        event.setNearPlaneDistance(event.getNearPlaneDistance() * nearFactor);

        // Far plane: only collapse at high strength (>0.3)
        if (t > 0.3f) {
            float farFactor = 1.0f - (t - 0.3f) / 0.7f * 0.85f;
            event.setFarPlaneDistance(event.getFarPlaneDistance() * Math.max(0.05f, farFactor));
        }

        event.setCanceled(true);
    }

    // -------------------------------------------------------------------------
    // Util
    // -------------------------------------------------------------------------

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
