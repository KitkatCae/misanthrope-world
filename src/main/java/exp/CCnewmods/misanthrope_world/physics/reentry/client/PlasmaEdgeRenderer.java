package exp.CCnewmods.misanthrope_world.physics.reentry.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.physics.reentry.network.ReentryStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders plasma edge geometry for all actively-heating ships.
 *
 * <h3>Frame cycle</h3>
 * <ol>
 *   <li>Receives {@link ReentryStatePacket} via {@link #onStatePacket} —
 *       updates per-ship state (intensity, velDir, mach, timestamp).</li>
 *   <li>On {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}: for
 *       each active ship, fetches the {@link ClientShip} from VS2's client
 *       ship world, uses {@code getRenderTransform()} (interpolated) for
 *       up-to-date world position, rebuilds the mesh if velDir changed, then
 *       uploads and draws it with {@link PlasmaEdgeShader}.</li>
 * </ol>
 *
 * <h3>Coordinate origin</h3>
 * All vertices are in world space. We subtract the camera position
 * ({@code camX/Y/Z}) from each vertex just before uploading to the
 * {@link BufferBuilder}, matching how vanilla translucent geometry is handled.
 *
 * <h3>Bloom integration</h3>
 * The fragment shader writes to {@code fragData[1]} (bloom buffer).
 * Photon's post-process chain composites this automatically when the
 * framebuffer targets are available. If Photon is absent, the second
 * output is silently ignored by the driver (no error, no crash).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(
        modid = Misanthrope_world.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class PlasmaEdgeRenderer {

    private PlasmaEdgeRenderer() {}

    // ── Per-ship client state ─────────────────────────────────────────────────

    private record ShipPlasmaState(
            long   shipId,
            float  intensity,
            Vec3d  velDir,       // normalised
            float  mach,
            long   lastUpdateMs, // System.currentTimeMillis() of last packet
            PlasmaEdgeMesh mesh
    ) {}

    // Vec3d wrapper (avoids importing net.minecraft.world.phys.Vec3 alias confusion)
    private record Vec3d(double x, double y, double z) {
        Vector3d toJoml() { return new Vector3d(x, y, z); }
    }

    private static final Map<Long, ShipPlasmaState> STATES = new ConcurrentHashMap<>();

    /** Packets older than this are considered stale and removed. */
    private static final long EXPIRY_MS = 2000L;

    // ── Packet receiver ───────────────────────────────────────────────────────

    public static void onStatePacket(ReentryStatePacket pkt) {
        if (pkt.intensity < 0.002f) {
            STATES.remove(pkt.shipId);
            return;
        }
        STATES.compute(pkt.shipId, (id, old) -> {
            PlasmaEdgeMesh mesh = (old != null) ? old.mesh() : new PlasmaEdgeMesh();
            return new ShipPlasmaState(
                    id,
                    pkt.intensity,
                    new Vec3d(pkt.velDir.x, pkt.velDir.y, pkt.velDir.z),
                    pkt.mach,
                    System.currentTimeMillis(),
                    mesh);
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (STATES.isEmpty()) return;

        ShaderInstance shader = PlasmaEdgeShader.get();
        if (shader == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // Camera position for vertex offset
        var cam     = event.getCamera();
        double camX = cam.getPosition().x;
        double camY = cam.getPosition().y;
        double camZ = cam.getPosition().z;

        // Game time in seconds for shader animation
        float timeSecs = mc.level.getGameTime() / 20.0f
                + event.getPartialTick() / 20.0f;

        // Set up render state
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                770, 771,   // srcRGB=SRC_ALPHA, dstRGB=ONE_MINUS_SRC_ALPHA
                1,   771);  // srcA=ONE, dstA=ONE_MINUS_SRC_ALPHA
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // Begin batching all ships into a single draw call for efficiency
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);

        long now = System.currentTimeMillis();
        boolean anyVerts = false;

        Iterator<Map.Entry<Long, ShipPlasmaState>> iter = STATES.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            ShipPlasmaState state = entry.getValue();

            // Expire stale states
            if (now - state.lastUpdateMs() > EXPIRY_MS) {
                iter.remove();
                continue;
            }

            // Look up the VS2 ClientShip for this ship ID
            ClientShip clientShip = findClientShip(mc, state.shipId());
            if (clientShip == null) continue;

            // Get the interpolated render transform
            var renderTransform = clientShip.getRenderTransform();
            if (renderTransform == null) continue;

            // The render AABB is already world-space (AABBdc)
            var renderAabb = clientShip.getRenderAABB();
            if (renderAabb == null) continue;

            // Compute Mach-tier colour
            Vector3f tierColor = machTierColor(state.mach());

            // Rebuild mesh if velDir changed significantly
            Vector3d velDir = state.velDir().toJoml();
            PlasmaEdgeMesh mesh = state.mesh();
            if (mesh.needsRebuild(velDir)) {
                mesh.rebuild(renderAabb,
                        renderTransform.getShipToWorld(),
                        velDir,
                        tierColor);
            }

            if (mesh.isEmpty()) continue;

            // Set per-ship shader uniforms
            PlasmaEdgeShader.setUniforms(state.intensity(), timeSecs,
                    /* bloomStrength */ Math.min(1f, state.intensity() * 1.5f));

            // Upload into the shared buffer
            mesh.upload(bb, camX, camY, camZ);
            anyVerts = true;
        }

        if (anyVerts) {
            // Apply shader and draw
            PlasmaEdgeShader.apply();
            tess.end();
            PlasmaEdgeShader.clear();
        } else {
            // Nothing to draw — discard the builder contents safely
            bb.discard();
        }

        // Restore render state
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Looks up the {@link ClientShip} for a given ship ID from VS2's
     * client-side ship world.
     */
    private static ClientShip findClientShip(Minecraft mc, long shipId) {
        if (mc.level == null) return null;
        try {
            var shipWorld = VSGameUtilsKt.getShipWorldNullable(mc.level);
            if (shipWorld == null) return null;
            // getAllShips returns VsiQueryableShipData<ClientShip> on client
            var allShips = shipWorld.getAllShips();
            if (allShips == null) return null;
            var ship = allShips.getById(shipId);
            return (ship instanceof ClientShip cs) ? cs : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the RGB glow colour for a given Mach number.
     *
     * <pre>
     *   Mach  5–12  → orange  (1.0, 0.45, 0.0)
     *   Mach 12–25  → yellow  (1.0, 0.85, 0.2)
     *   Mach 25–80  → white   (1.0, 1.0,  1.0)
     *   Mach 80+    → violet  (0.7, 0.5,  1.0)
     * </pre>
     */
    public static Vector3f machTierColor(float mach) {
        if (mach < 12f) {
            // orange
            float t = Math.max(0f, (mach - 5f) / 7f);
            return new Vector3f(1f, 0.45f * t, 0f);
        } else if (mach < 25f) {
            // orange → yellow
            float t = (mach - 12f) / 13f;
            return new Vector3f(1f, lerp(0.45f, 0.85f, t), lerp(0f, 0.2f, t));
        } else if (mach < 80f) {
            // yellow → white
            float t = (mach - 25f) / 55f;
            return new Vector3f(1f, lerp(0.85f, 1f, t), lerp(0.2f, 1f, t));
        } else {
            // white → violet
            float t = Math.min(1f, (mach - 80f) / 128f);
            return new Vector3f(lerp(1f, 0.7f, t), lerp(1f, 0.5f, t), 1f);
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
