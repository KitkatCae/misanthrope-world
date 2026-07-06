package exp.CCnewmods.misanthrope_world.physics.pressure.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * Renders visual hull deformation overlays for world-space pressure blocks.
 *
 * <p>Fires on {@link RenderLevelStageEvent.Stage#AFTER_SOLID_BLOCKS} — same
 * stage as the crack render overlay — so deformed faces appear above solid
 * geometry but beneath translucent. Each frame, walks
 * {@link WorldBlockDeformRenderer#STATES} and emits displaced face quads for
 * any block with non-trivial deform or inflation.
 *
 * <h3>Visual encoding</h3>
 * <ul>
 *   <li><b>Inward crush</b> (deformAmount &lt; 0): face displaced inward by
 *       |deformAmount| blocks; slightly darker tint; concave shadowing.</li>
 *   <li><b>Outward expansion</b> (deformAmount &gt; 0): face displaced outward;
 *       slightly lighter. Stage 2+ gets a stress-line texture overlay.</li>
 *   <li><b>Inflation</b>: all six faces drawn at a scale of
 *       {@code 0.5 + inflationFraction × 0.5} blocks from centre; translucent
 *       white fading to near-transparent at high inflation (material thinning).</li>
 *   <li><b>Tension-pause flicker</b>: overlay alpha pulses at ~2 Hz via a
 *       simple {@code System.currentTimeMillis() % 500 < 250} square wave.</li>
 * </ul>
 *
 * <p>Unlike MVSE's {@code HullDeformationRenderLayer} (which applies the VS2
 * ship transform to shift quads into moving ship space), this renderer uses
 * fixed world-space coordinates — a simpler path with no matrix stack push.
 */
@OnlyIn(Dist.CLIENT)
public final class WorldBlockDeformRenderLayer {

    private WorldBlockDeformRenderLayer() {}

    private static final float SKIP_THRESHOLD = 0.003f;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        if (WorldBlockDeformRenderer.STATES.isEmpty()) return;

        Minecraft mc     = Minecraft.getInstance();
        Vec3      camera = event.getCamera().getPosition();

        Tesselator   tess   = Tesselator.getInstance();
        BufferBuilder buf    = tess.getBuilder();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        boolean flicker = (System.currentTimeMillis() % 500L) < 250L;

        for (Map.Entry<BlockPos, WorldBlockDeformRenderer.DeformState> entry
                : WorldBlockDeformRenderer.STATES.entrySet()) {

            BlockPos pos = entry.getKey();
            WorldBlockDeformRenderer.DeformState ds = entry.getValue();

            float deform    = ds.deformAmount;
            float inflation = ds.inflationFraction;
            boolean pause   = ds.tensionPause;

            boolean hasDeform    = Math.abs(deform) >= SKIP_THRESHOLD;
            boolean hasInflation = inflation >= SKIP_THRESHOLD;

            if (!hasDeform && !hasInflation) continue;

            // World-space block origin relative to camera
            double ox = pos.getX() - camera.x;
            double oy = pos.getY() - camera.y;
            double oz = pos.getZ() - camera.z;

            if (hasInflation) {
                renderInflation(buf, ox, oy, oz, inflation, pause, flicker);
            } else if (hasDeform) {
                renderDeformation(buf, ox, oy, oz, deform, ds.stage, pause, flicker);
            }
        }

        tess.end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ── Deformation face rendering ────────────────────────────────────────────

    /**
     * Renders a single displaced quad on the face most aligned with the deform
     * direction. For positive (outward) deform the face pushes away; for
     * negative (inward) it recedes. All six faces are rendered but with
     * alpha scaled by how close they are to the camera frustum centre (cheap
     * silhouette approximation — only strongly affected faces are vivid).
     */
    private static void renderDeformation(
            BufferBuilder buf,
            double ox, double oy, double oz,
            float deform, int stage, boolean tensionPause, boolean flicker) {

        boolean inward = deform < 0;
        float absDeform = Math.abs(deform);

        // Base alpha proportional to deformation; tension-pause flicker
        float baseAlpha = Math.min(0.6f, absDeform * 4f);
        if (tensionPause && !flicker) baseAlpha *= 0.3f;

        // Colour: crush = warm red tint, expansion = cool blue tint
        float r = inward ? 0.8f : 0.3f;
        float g = inward ? 0.3f : 0.5f;
        float b = inward ? 0.3f : 0.9f;

        // Stage 2+ gets brighter warning tint
        if (stage >= 2) { r = Math.min(1f, r + 0.2f); g = Math.min(1f, g + 0.1f); }

        for (Direction face : Direction.values()) {
            float nx = face.getStepX();
            float ny = face.getStepY();
            float nz = face.getStepZ();

            // Offset: outward faces get pushed in the deform direction,
            // inward faces get pulled back
            float offset = inward ? -absDeform : absDeform;

            // Face origin — corner of the block face
            double fx = ox + (nx > 0 ? 1 : 0);
            double fy = oy + (ny > 0 ? 1 : 0);
            double fz = oz + (nz > 0 ? 1 : 0);

            // Displace face by offset along normal
            fx += nx * offset;
            fy += ny * offset;
            fz += nz * offset;

            emitFaceQuad(buf, face, fx, fy, fz, r, g, b, baseAlpha);
        }
    }

    /**
     * Renders an inflation overlay: all six faces expanded outward at
     * {@code 0.5 + inflationFraction * 0.5} blocks from centre, translucent
     * white fading to near-transparent at full inflation.
     */
    private static void renderInflation(
            BufferBuilder buf,
            double ox, double oy, double oz,
            float inflation, boolean tensionPause, boolean flicker) {

        // Scale: at inflation=0, half-block radius; at inflation=1, full block
        float scale = 0.5f + inflation * 0.5f;

        // Alpha drops as material thins toward tear
        float alpha = Math.max(0.05f, 0.4f * (1f - inflation * 0.9f));
        if (tensionPause && !flicker) alpha *= 0.4f;

        // White → yellow as inflation increases (stress colouring)
        float r = 1.0f;
        float g = Math.max(0.6f, 1.0f - inflation * 0.5f);
        float b = Math.max(0.3f, 1.0f - inflation * 0.8f);

        double cx = ox + 0.5;
        double cy = oy + 0.5;
        double cz = oz + 0.5;

        for (Direction face : Direction.values()) {
            float nx = face.getStepX();
            float ny = face.getStepY();
            float nz = face.getStepZ();

            double fx = cx + nx * scale - (nx == 0 ? scale : 0);
            double fy = cy + ny * scale - (ny == 0 ? scale : 0);
            double fz = cz + nz * scale - (nz == 0 ? scale : 0);

            emitFaceQuad(buf, face, fx, fy, fz, r, g, b, alpha);
        }
    }

    // ── Quad emission ─────────────────────────────────────────────────────────

    /**
     * Emits a unit-square quad on {@code face} at position {@code (fx, fy, fz)}.
     * The quad lies on the plane perpendicular to {@code face}'s normal.
     */
    private static void emitFaceQuad(
            BufferBuilder buf,
            Direction face,
            double fx, double fy, double fz,
            float r, float g, float b, float a) {

        int ri = (int)(r * 255);
        int gi = (int)(g * 255);
        int bi = (int)(b * 255);
        int ai = (int)(a * 255);

        // Two tangent axes for the face
        float[] u = tangentU(face);
        float[] v = tangentV(face);

        // Four corners of the quad
        buf.vertex(fx,          fy,          fz         ).color(ri, gi, bi, ai).endVertex();
        buf.vertex(fx + u[0],   fy + u[1],   fz + u[2]  ).color(ri, gi, bi, ai).endVertex();
        buf.vertex(fx + u[0] + v[0], fy + u[1] + v[1], fz + u[2] + v[2]).color(ri, gi, bi, ai).endVertex();
        buf.vertex(fx + v[0],   fy + v[1],   fz + v[2]  ).color(ri, gi, bi, ai).endVertex();
    }

    private static float[] tangentU(Direction face) {
        return switch (face) {
            case DOWN, UP     -> new float[]{ 1, 0, 0 };
            case NORTH, SOUTH -> new float[]{ 1, 0, 0 };
            case WEST, EAST   -> new float[]{ 0, 0, 1 };
        };
    }

    private static float[] tangentV(Direction face) {
        return switch (face) {
            case DOWN, UP     -> new float[]{ 0, 0, 1 };
            case NORTH, SOUTH -> new float[]{ 0, 1, 0 };
            case WEST, EAST   -> new float[]{ 0, 1, 0 };
        };
    }
}
