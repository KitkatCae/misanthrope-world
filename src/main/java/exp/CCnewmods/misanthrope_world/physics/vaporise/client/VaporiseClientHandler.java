package exp.CCnewmods.misanthrope_world.physics.vaporise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/**
 * Client-side dispatcher for vaporisation visual events.
 *
 * <p>Called from {@link VaporisePacket#handleClient} on the client game thread
 * when the server broadcasts a vaporisation event. Coordinates three independent
 * visual subsystems:</p>
 *
 * <ol>
 *   <li><b>Screen flash</b> — {@link VaporiseFlashRenderer} manages a
 *       short-lived fullscreen brightness pulse via {@link
 *       net.minecraftforge.client.event.ViewportEvent.ComputeFogColor} and
 *       {@link net.minecraftforge.client.event.ViewportEvent.RenderFog}.
 *       Scales with distance and intensity. Attenuated when Oculus is running
 *       with a shaderpack (to avoid double-bloom).</li>
 *   <li><b>In-world billboard</b> — {@link VaporiseBillboardRenderer} renders
 *       an expanding additive sphere around the origin using a custom GLSL
 *       shader. The sphere grows outward for ~1–5 seconds then fades, timed
 *       by {@code intensity}.</li>
 *   <li><b>Photon FX</b> — if Photon is loaded, plays {@code mvs:fx/vaporise}
 *       at the origin block using the same {@code FXHelper} pattern as MGE.</li>
 * </ol>
 *
 * <h3>Oculus compat</h3>
 * If Oculus is present and a shaderpack is active, the flash brightness is
 * halved (shaderpacks typically add their own bloom to the bright spot). The
 * billboard is still rendered since it is a world-space object, not a
 * fullscreen effect, and Oculus composite passes don't affect it in
 * {@code AFTER_TRANSLUCENT_BLOCKS} stage.
 */
@OnlyIn(Dist.CLIENT)
public final class VaporiseClientHandler {

    private static final String OCULUS_MODID = "oculus";
    private static final String PHOTON_MODID = "photon";

    // Photon FX resource location: assets/misanthrope_world/fx/vaporise.fx
    static final ResourceLocation FX_VAPORISE =
            new ResourceLocation("misanthrope_world", "fx/vaporise");

    private VaporiseClientHandler() {}

    /**
     * Called on the client game thread from {@link VaporisePacket#handleClient}.
     *
     * @param origin    world-space position of the vaporisation
     * @param intensity kinetic energy proxy [0.1, 10.0]
     * @param photonAvailableOnServer whether Photon was loaded server-side
     */
    public static void onVaporise(Vec3 origin, float intensity, boolean photonAvailableOnServer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // ── Distance attenuation ──────────────────────────────────────────────
        double dist = mc.player.getEyePosition().distanceTo(origin);
        // Flash falls off with distance: full within 8 blocks, zero beyond 96
        float distFactor = (float) Math.max(0, 1.0 - (dist - 8.0) / 88.0);

        // ── Oculus shaderpack guard ───────────────────────────────────────────
        float oculusFactor = 1.0f;
        if (ModList.get().isLoaded(OCULUS_MODID)) {
            try {
                Class<?> api    = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                boolean shaderActive = (boolean) api.getMethod("isShaderPackInUse").invoke(instance);
                if (shaderActive) oculusFactor = 0.5f; // shaderpack adds bloom already
            } catch (Exception ignored) {}
        }

        float flashStrength = intensity * distFactor * oculusFactor;

        // ── 1. Screen flash ───────────────────────────────────────────────────
        if (flashStrength > 0.05f) {
            VaporiseFlashRenderer.triggerFlash(flashStrength);
        }

        // ── 2. In-world billboard sphere ──────────────────────────────────────
        VaporiseBillboardRenderer.spawn(origin, intensity);

        // ── 3. Photon FX ──────────────────────────────────────────────────────
        boolean photonClient = ModList.get().isLoaded(PHOTON_MODID);
        if (photonClient && photonAvailableOnServer) {
            playPhotonFx(mc.level, origin);
        }
    }

    // -------------------------------------------------------------------------
    // Photon integration
    // -------------------------------------------------------------------------

    /**
     * Plays the {@code mvs:fx/vaporise} Photon effect at the given position.
     * Fully guarded — no-ops if Photon classes aren't present at runtime.
     */
    private static void playPhotonFx(net.minecraft.world.level.Level level, Vec3 pos) {
        try {
            Class<?> fxHelperClass   = Class.forName("com.lowdragmc.photon.client.fx.FXHelper");
            Class<?> blockEffectClass= Class.forName("com.lowdragmc.photon.client.fx.BlockEffect");
            Class<?> fxClass         = Class.forName("com.lowdragmc.photon.client.fx.FX");

            Object fx = fxHelperClass.getMethod("getFX", ResourceLocation.class)
                    .invoke(null, FX_VAPORISE);
            if (fx == null) return;

            BlockPos bp = BlockPos.containing(pos);
            Object effect = blockEffectClass
                    .getConstructor(fxClass, net.minecraft.world.level.Level.class, BlockPos.class)
                    .newInstance(fx, level, bp);
            blockEffectClass.getMethod("start").invoke(effect);
        } catch (Exception ignored) {
            // Photon not present or FX file missing — silent no-op
        }
    }
}
