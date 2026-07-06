package exp.CCnewmods.misanthrope_world.physics.vaporise.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Renders an in-world expanding plasma sphere at vaporisation origins.
 *
 * <h3>Visual model</h3>
 * Each vaporisation spawns a {@link PlasmaFront}: an expanding icosphere drawn
 * as an additive-blended triangle mesh. The sphere expands outward at
 * {@code expansionRate} blocks/tick, fading from white-hot core colour
 * to transparent as it expands. The GLSL shader ({@code vaporise_plasma.vsh}
 * / {@code vaporise_plasma.fsh}) applies:
 * <ul>
 *   <li>Per-vertex normal-based rim lighting to give the sphere depth</li>
 *   <li>Additive blend ({@code GL_ONE, GL_ONE}) so it brightens the world
 *       behind it without z-fighting</li>
 *   <li>A {@code u_age} uniform [0,1] that drives colour from blue-white
 *       (plasma, age~0) → orange-white (hot gas, age~0.4) → transparent
 *       (cooled, age~1)</li>
 * </ul>
 *
 * <h3>Oculus / Iris compatibility</h3>
 * Rendered at {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}
 * which fires AFTER Iris's composite and final passes. The sphere is therefore
 * not affected by shaderpack tonemapping or bloom, but IS correctly depth-tested
 * against world geometry so it doesn't bleed through walls.
 *
 * If you want the sphere to interact with shaderpack bloom, move rendering to
 * {@code AFTER_SOLID_BLOCKS} — it will be picked up by shaderpack composites.
 * Current choice keeps the visual consistent whether or not a shaderpack is loaded.
 *
 * <h3>Icosphere geometry</h3>
 * We use a subdivided icosphere (1 subdivision = 80 triangles) hard-coded as
 * unit-sphere vertices. The vertex buffer is built once and re-scaled per frame
 * via the model-view matrix. This avoids rebuilding geometry every tick.
 *
 * <h3>Performance</h3>
 * At most {@link #MAX_SIMULTANEOUS} active fronts. Older fronts are removed
 * when the limit is exceeded. Each front is one draw call (~80 triangles),
 * negligible GPU cost.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(
        modid = Misanthrope_world.MODID,
        bus   = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class VaporiseBillboardRenderer {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int   MAX_SIMULTANEOUS = 8;
    /** Blocks/tick the sphere expands at intensity 1.0. */
    private static final float BASE_EXPANSION_RATE = 0.8f;
    /** Maximum sphere radius in blocks (intensity 10 → 10×base). */
    private static final float MAX_RADIUS = 48f;

    // Shader resource locations
    static final ResourceLocation VERT_SHADER =
            new ResourceLocation(Misanthrope_world.MODID,
                    "shaders/program/vaporise_plasma.vsh");
    static final ResourceLocation FRAG_SHADER =
            new ResourceLocation(Misanthrope_world.MODID,
                    "shaders/program/vaporise_plasma.fsh");

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private static final List<PlasmaFront> FRONTS = new ArrayList<>();
    private static ShaderInstance plasmaShader = null;

    private VaporiseBillboardRenderer() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Spawns an expanding plasma front at the given world-space origin.
     * Called from {@link VaporiseClientHandler#onVaporise} on the client thread.
     */
    public static void spawn(Vec3 origin, float intensity) {
        if (FRONTS.size() >= MAX_SIMULTANEOUS) {
            FRONTS.remove(0); // drop oldest
        }
        float maxRadius      = Math.min(MAX_RADIUS, intensity * 8f);
        float expansionRate  = BASE_EXPANSION_RATE * Math.max(1f, (float) Math.sqrt(intensity));
        int   lifetimeTicks  = (int) (maxRadius / expansionRate) + 10; // +10 tick fade tail
        FRONTS.add(new PlasmaFront(origin, maxRadius, expansionRate, lifetimeTicks));
    }

    /** Call each client tick from {@code MVSClientEvents}. */
    public static void clientTick() {
        Iterator<PlasmaFront> it = FRONTS.iterator();
        while (it.hasNext()) {
            PlasmaFront f = it.next();
            f.tick();
            if (f.isDead()) it.remove();
        }
    }

    // -------------------------------------------------------------------------
    // Shader registration
    // -------------------------------------------------------------------------
    // Moved to VaporiseSystemSetup.ClientModEvents (MOD bus). This class is
    // annotated with bus = Mod.EventBusSubscriber.Bus.FORGE, but
    // RegisterShadersEvent fires on the MOD bus — a subscriber here would
    // simply never be called, which is almost certainly why plasmaShader
    // never actually got set in MVSE, meaning this billboard likely never
    // rendered anything despite spawn()/clientTick() tracking fronts
    // correctly. Same bus mismatch already found and fixed for
    // PlasmaEdgeShader in the reentry phase.

    /** Called by {@code VaporiseSystemSetup.ClientModEvents.onRegisterShaders}. */
    public static void setPlasmaShader(ShaderInstance shader) {
        plasmaShader = shader;
    }

    // -------------------------------------------------------------------------
    // Render hook
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (FRONTS.isEmpty() || plasmaShader == null) return;

        Minecraft mc     = Minecraft.getInstance();
        Vec3      camPos = mc.gameRenderer.getMainCamera().getPosition();

        PoseStack ps = event.getPoseStack();
        ps.pushPose();

        // Camera-relative translation (avoids floating-point precision loss at
        // large world coordinates — same technique as vanilla particle rendering)
        // The projection matrix from the event already has camera transform baked in
        // for the level render, but PoseStack here is identity; we push camera-relative
        // translation ourselves.

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE); // additive
        RenderSystem.depthMask(false); // don't write depth — additive sphere is transparent

        Matrix4f projMatrix = RenderSystem.getProjectionMatrix();

        for (PlasmaFront front : FRONTS) {
            if (front.currentRadius <= 0.01f || front.alpha <= 0.005f) continue;
            renderFront(ps, camPos, projMatrix, front);
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();

        ps.popPose();
    }

    // -------------------------------------------------------------------------
    // Per-front render
    // -------------------------------------------------------------------------

    private static void renderFront(PoseStack ps, Vec3 camPos,
                                    Matrix4f projMatrix, PlasmaFront front) {
        ps.pushPose();
        // Translate to front origin, camera-relative
        ps.translate(
                front.origin.x - camPos.x,
                front.origin.y - camPos.y,
                front.origin.z - camPos.z);
        ps.scale(front.currentRadius, front.currentRadius, front.currentRadius);

        // Colour: blue-white → orange → transparent
        float age = front.age(); // [0,1]
        float r, g, b;
        if (age < 0.3f) {
            // Blue-white plasma core (age 0→0.3)
            float t = age / 0.3f;
            r = lerp(0.9f, 1.0f, t);
            g = lerp(0.95f, 0.85f, t);
            b = lerp(1.0f, 0.5f, t);
        } else if (age < 0.65f) {
            // Transition to orange fireball (age 0.3→0.65)
            float t = (age - 0.3f) / 0.35f;
            r = lerp(1.0f, 1.0f, t);
            g = lerp(0.85f, 0.45f, t);
            b = lerp(0.5f, 0.05f, t);
        } else {
            // Fade to transparent red-orange ember (age 0.65→1.0)
            float t = (age - 0.65f) / 0.35f;
            r = lerp(1.0f, 0.6f, t);
            g = lerp(0.45f, 0.1f, t);
            b = lerp(0.05f, 0f, t);
        }
        float alpha = front.alpha;

        Matrix4f model = ps.last().pose();

        // Upload uniforms
        plasmaShader.safeGetUniform("u_age")   .set(age);
        plasmaShader.safeGetUniform("u_alpha")  .set(alpha);

        RenderSystem.setShader(() -> plasmaShader);

        // Build icosphere vertex buffer
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        // Render unit icosphere (1 subdivision) scaled by model matrix
        renderIcosphere(buf, model, r, g, b, alpha);

        tess.end();

        ps.popPose();
    }

    // -------------------------------------------------------------------------
    // Icosphere geometry (1-subdivision, 80 triangles, pre-computed unit sphere)
    // -------------------------------------------------------------------------

    // Icosahedron base vertices (normalised)
    private static final float[][] ICO_VERTS;
    private static final int[][]   ICO_TRIS;

    static {
        double phi = (1.0 + Math.sqrt(5.0)) / 2.0;
        double len = Math.sqrt(1 + phi * phi);
        float n = (float) (1.0 / len);
        float p = (float) (phi / len);

        ICO_VERTS = new float[][] {
                {-n,  p,  0}, { n,  p,  0}, {-n, -p,  0}, { n, -p,  0},
                { 0, -n,  p}, { 0,  n,  p}, { 0, -n, -p}, { 0,  n, -p},
                { p,  0, -n}, { p,  0,  n}, {-p,  0, -n}, {-p,  0,  n}
        };
        ICO_TRIS = new int[][] {
                {0,11,5},{0,5,1},{0,1,7},{0,7,10},{0,10,11},
                {1,5,9},{5,11,4},{11,10,2},{10,7,6},{7,1,8},
                {3,9,4},{3,4,2},{3,2,6},{3,6,8},{3,8,9},
                {4,9,5},{2,4,11},{6,2,10},{8,6,7},{9,8,1}
        };
    }

    /**
     * Emits one subdivision of the icosphere into the buffer.
     * Each original triangle is split into 4 sub-triangles → 80 total.
     * Vertices are normalised to the unit sphere and used as normals too.
     */
    private static void renderIcosphere(BufferBuilder buf, Matrix4f model,
                                        float r, float g, float b, float alpha) {
        int ar = (int)(r * 255); int ag = (int)(g * 255);
        int ab = (int)(b * 255); int aa = (int)(alpha * 255);

        for (int[] tri : ICO_TRIS) {
            float[] v0 = ICO_VERTS[tri[0]];
            float[] v1 = ICO_VERTS[tri[1]];
            float[] v2 = ICO_VERTS[tri[2]];

            // 1 subdivision: midpoints, normalised
            float[] m01 = norm(mid(v0, v1));
            float[] m12 = norm(mid(v1, v2));
            float[] m20 = norm(mid(v2, v0));

            // 4 sub-triangles
            emitTri(buf, model, v0,  m01, m20, ar, ag, ab, aa);
            emitTri(buf, model, m01, v1,  m12, ar, ag, ab, aa);
            emitTri(buf, model, m20, m12, v2,  ar, ag, ab, aa);
            emitTri(buf, model, m01, m12, m20, ar, ag, ab, aa);
        }
    }

    private static void emitTri(BufferBuilder buf, Matrix4f model,
                                float[] a, float[] b, float[] c,
                                int r, int g, int bl, int alpha) {
        // Normal = vertex position on unit sphere (outward)
        emitVert(buf, model, a[0], a[1], a[2], a[0], a[1], a[2], r, g, bl, alpha);
        emitVert(buf, model, b[0], b[1], b[2], b[0], b[1], b[2], r, g, bl, alpha);
        emitVert(buf, model, c[0], c[1], c[2], c[0], c[1], c[2], r, g, bl, alpha);
    }

    private static void emitVert(BufferBuilder buf, Matrix4f model,
                                 float x, float y, float z,
                                 float nx, float ny, float nz,
                                 int r, int g, int b, int a) {
        // Transform position by model matrix
        Vector3f pos = new Vector3f(x, y, z);
        pos = model.transformPosition(pos, new Vector3f());
        buf.vertex(pos.x, pos.y, pos.z)
                .color(r, g, b, a)
                .normal(nx, ny, nz)
                .endVertex();
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private static float[] mid(float[] a, float[] b) {
        return new float[]{ (a[0]+b[0])*0.5f, (a[1]+b[1])*0.5f, (a[2]+b[2])*0.5f };
    }

    private static float[] norm(float[] v) {
        float len = (float) Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
        return new float[]{ v[0]/len, v[1]/len, v[2]/len };
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // -------------------------------------------------------------------------
    // PlasmaFront data record
    // -------------------------------------------------------------------------

    static final class PlasmaFront {
        final Vec3  origin;
        final float maxRadius;
        final float expansionRate;
        final int   lifetimeTicks;

        float currentRadius = 0f;
        float alpha         = 1f;
        int   tick          = 0;

        PlasmaFront(Vec3 origin, float maxRadius, float expansionRate, int lifetimeTicks) {
            this.origin        = origin;
            this.maxRadius     = maxRadius;
            this.expansionRate = expansionRate;
            this.lifetimeTicks = lifetimeTicks;
        }

        void tick() {
            tick++;
            currentRadius = Math.min(maxRadius, currentRadius + expansionRate);

            // Alpha: full until max radius reached, then linear fade
            if (currentRadius >= maxRadius) {
                float remainingFraction =
                        1f - (float)(tick - (int)(maxRadius / expansionRate)) /
                                (lifetimeTicks - (int)(maxRadius / expansionRate));
                alpha = Math.max(0f, remainingFraction);
            }
        }

        /** Normalised age [0,1]: 0 = just spawned, 1 = fully faded. */
        float age() {
            return Math.min(1f, (float) tick / lifetimeTicks);
        }

        boolean isDead() { return tick >= lifetimeTicks; }
    }
}