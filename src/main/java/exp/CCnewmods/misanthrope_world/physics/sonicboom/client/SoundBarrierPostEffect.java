package exp.CCnewmods.misanthrope_world.physics.sonicboom.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import exp.CCnewmods.misanthrope_world.physics.reentry.client.PlasmaTrailRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Renders a brief white bloom overlay when a sonic boom occurs near the player.
 *
 * <p>Uses a simple immediate-mode full-screen quad at alpha {@link #currentAlpha},
 * which decays each frame.  This is intentionally lightweight — no FBO, no post
 * chain — because the event is momentary and the visual should feel like a flash
 * rather than a sustained effect.</p>
 *
 * <p>For the plasma trail the proper atmospheric fog tint is handled by
 * {@link PlasmaTrailRenderer} via {@link net.minecraftforge.client.event.ViewportEvent}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SoundBarrierPostEffect {

    private SoundBarrierPostEffect() {}

    private static float currentAlpha = 0f;
    private static float peakAlpha    = 0f;

    /** Fraction alpha decays per frame (~60 fps → ~6 frames visible at full intensity). */
    private static final float DECAY_PER_FRAME = 0.08f;

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Triggers a white flash.  Can be called multiple times in the same tick;
     * takes the maximum.
     */
    public static void triggerBloom(float intensity) {
        peakAlpha = Math.max(peakAlpha, Math.min(0.85f, intensity));
        currentAlpha = Math.max(currentAlpha, peakAlpha);
    }

    /**
     * Called each frame from {@link AerodynamicsClientEvents} during the GUI
     * render event (after world, before HUD) to draw the overlay.
     */
    public static void renderFrame(PoseStack poseStack, float screenW, float screenH) {
        if (currentAlpha <= 0.01f) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();

        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float a = currentAlpha;
        // Pure white flash
        bb.vertex(poseStack.last().pose(), 0,       screenH, 0).color(1f, 1f, 1f, a).endVertex();
        bb.vertex(poseStack.last().pose(), screenW, screenH, 0).color(1f, 1f, 1f, a).endVertex();
        bb.vertex(poseStack.last().pose(), screenW, 0,       0).color(1f, 1f, 1f, a).endVertex();
        bb.vertex(poseStack.last().pose(), 0,       0,       0).color(1f, 1f, 1f, a).endVertex();

        Tesselator.getInstance().end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        // Decay
        currentAlpha = Math.max(0f, currentAlpha - DECAY_PER_FRAME);
        if (currentAlpha <= 0f) peakAlpha = 0f;
    }
}
