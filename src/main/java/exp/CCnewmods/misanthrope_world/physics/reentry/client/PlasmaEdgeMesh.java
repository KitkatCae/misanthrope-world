package exp.CCnewmods.misanthrope_world.physics.reentry.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.primitives.AABBdc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the geometry for one ship's plasma edge effect.
 *
 * <h3>Mesh structure</h3>
 * For each exposed block face on the leading edge (faces whose outward normal
 * has a positive dot with the velocity direction), we emit:
 * <ol>
 *   <li><b>Face quad</b>  — the 1×1 block face itself, at trailT=0.
 *       This is the glowing "contact sheet" pressed against the nose.</li>
 *   <li><b>Trail quad</b> — a rectangle extending {@link #TRAIL_LENGTH} blocks
 *       behind the face in the {@code -velDir} direction.
 *       Vertices step from trailT=0 (face edge) to trailT=1 (tip).</li>
 * </ol>
 *
 * <h3>Vertex format</h3>
 * {@link DefaultVertexFormat#POSITION_COLOR_TEX}:
 * <ul>
 *   <li>Position — world-space XYZ (already transformed via shipToWorld)</li>
 *   <li>Color    — RGBA: rgb=Mach-tier colour, a=turbulence seed (packed as byte)</li>
 *   <li>UV0      — (faceU, trailT): faceU∈[0,1] across face; trailT∈[0,1] along trail</li>
 * </ul>
 *
 * <h3>Coordinate space</h3>
 * All vertices are emitted in world space using the ship's
 * {@code shipToWorld} matrix. The vertex shader receives them with
 * only the camera view/projection applied.
 *
 * <h3>Rebuild policy</h3>
 * The mesh is rebuilt when the velocity direction changes by more than
 * {@link #REBUILD_ANGLE_COS} cosine units, or when intensity crosses a
 * significant threshold boundary. Between rebuilds, only the shader
 * uniforms (Intensity, Time) are updated — no geometry work.
 */
@OnlyIn(Dist.CLIENT)
public final class PlasmaEdgeMesh {

    // ── Config ────────────────────────────────────────────────────────────────

    /** Trail geometry extends this many blocks behind the leading face. */
    public static final float TRAIL_LENGTH = 32.0f;

    /**
     * Cosine threshold for rebuilding the mesh when velDir rotates.
     * cos(5°) ≈ 0.9962. If dot(oldVelDir, newVelDir) < this, rebuild.
     */
    private static final double REBUILD_ANGLE_COS = 0.9962;

    /** Maximum number of faces to tessellate (caps perf on giant ships). */
    private static final int MAX_FACES = 1024;

    // ── State ─────────────────────────────────────────────────────────────────

    /** Last velDir used to build this mesh. */
    private final Vector3d lastVelDir = new Vector3d(0, -1, 0);

    /** True if the geometry buffers need to be rebuilt. */
    private boolean dirty = true;

    /** Packed vertex data ready for upload. Null until first build. */
    private float[] vertexData = null;

    /** Number of vertices in {@link #vertexData}. */
    private int vertexCount = 0;

    // Floats per vertex: 3(pos) + 4(color as 4 floats) + 2(uv) = 9
    // We'll pack color as 4 separate floats matching POSITION_COLOR_TEX layout
    private static final int FLOATS_PER_VERTEX = 9;

    // ── RNG for turbulence seed ───────────────────────────────────────────────
    private static final Random RNG = new Random();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if the mesh needs rebuilding given the new velocity direction.
     * The caller should call {@link #rebuild} and then {@link #upload}.
     */
    public boolean needsRebuild(Vector3d velDir) {
        return dirty || velDir.dot(lastVelDir) < REBUILD_ANGLE_COS;
    }

    /**
     * Rebuilds the mesh geometry from the ship's world-space AABB and current
     * velocity direction.
     *
     * @param worldAabb      the ship's world AABB ({@code ClientShip.getRenderAABB()})
     * @param shipToWorld    the ship-to-world transform matrix (from renderTransform)
     * @param velDir         normalised world-space velocity direction
     * @param machTierColor  RGB colour for the Mach tier, passed to vertex Color
     */
    public void rebuild(AABBdc worldAabb,
                        Matrix4dc shipToWorld,
                        Vector3d velDir,
                        Vector3f machTierColor) {
        lastVelDir.set(velDir);
        dirty = false;

        // Identify dominant axis and build leading-edge face quads
        List<float[]> faces = collectLeadingFaces(worldAabb, velDir);

        // Each face → 1 face quad (4 verts) + 1 trail quad (4 verts) = 8 verts
        // Each quad = 2 triangles = 6 indices, but we use quads via QUADS topology
        // so 4 verts per quad
        int totalVerts = faces.size() * 8;
        vertexData  = new float[totalVerts * FLOATS_PER_VERTEX];
        vertexCount = 0;

        float r = machTierColor.x, g = machTierColor.y, b = machTierColor.z;

        for (float[] face : faces) {
            float seed = RNG.nextFloat(); // turbulence phase offset for this face

            // face[] = {x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3}
            // four corners of the 1×1 block face in world space
            // trail direction = -velDir * TRAIL_LENGTH
            float tx = (float) (-velDir.x * TRAIL_LENGTH);
            float ty = (float) (-velDir.y * TRAIL_LENGTH);
            float tz = (float) (-velDir.z * TRAIL_LENGTH);

            // Face quad (trailT = 0)
            emitVert(face[0],  face[1],  face[2],  r, g, b, seed, 0f, 0f);
            emitVert(face[3],  face[4],  face[5],  r, g, b, seed, 1f, 0f);
            emitVert(face[6],  face[7],  face[8],  r, g, b, seed, 1f, 0f);
            emitVert(face[9],  face[10], face[11], r, g, b, seed, 0f, 0f);

            // Trail quad (trailT goes 0→1)
            // Front edge of trail shares face edge
            emitVert(face[0],       face[1],       face[2],       r, g, b, seed, 0f, 0f);
            emitVert(face[3],       face[4],       face[5],       r, g, b, seed, 1f, 0f);
            emitVert(face[3]+tx,    face[4]+ty,    face[5]+tz,    r, g, b, seed, 1f, 1f);
            emitVert(face[0]+tx,    face[1]+ty,    face[2]+tz,    r, g, b, seed, 0f, 1f);
        }

        vertexCount = faces.size() * 8;
    }

    private void emitVert(float x, float y, float z,
                          float r, float g, float b, float seed,
                          float u, float v) {
        int base = vertexCount * FLOATS_PER_VERTEX;
        vertexData[base]   = x;
        vertexData[base+1] = y;
        vertexData[base+2] = z;
        vertexData[base+3] = r;
        vertexData[base+4] = g;
        vertexData[base+5] = b;
        vertexData[base+6] = seed; // packed as alpha
        vertexData[base+7] = u;
        vertexData[base+8] = v;
        vertexCount++;
    }

    /**
     * Uploads this mesh's vertices into the provided {@link BufferBuilder}.
     * The builder must already be {@link BufferBuilder#begin}-ed with
     * {@link VertexFormat.Mode#QUADS} and {@link DefaultVertexFormat#POSITION_COLOR_TEX}.
     */
    public void upload(BufferBuilder bb, double camX, double camY, double camZ) {
        if (vertexData == null || vertexCount == 0) return;

        for (int i = 0; i < vertexCount; i++) {
            int base = i * FLOATS_PER_VERTEX;
            // Subtract camera position (world-relative rendering)
            float wx = vertexData[base]   - (float) camX;
            float wy = vertexData[base+1] - (float) camY;
            float wz = vertexData[base+2] - (float) camZ;
            float r  = vertexData[base+3];
            float g  = vertexData[base+4];
            float b  = vertexData[base+5];
            float a  = vertexData[base+6]; // seed in alpha slot
            float u  = vertexData[base+7];
            float v  = vertexData[base+8];
            bb.vertex(wx, wy, wz)
              .color(r, g, b, a)
              .uv(u, v)
              .endVertex();
        }
    }

    public boolean isEmpty() { return vertexCount == 0; }
    public int vertexCount()  { return vertexCount; }

    // ── Geometry ──────────────────────────────────────────────────────────────

    /**
     * Collects world-space quads for each block face on the leading edge.
     *
     * <p>We use the ship's world AABB and the velocity direction to identify
     * which axis is dominant, then sample grid positions along that face
     * at 1-block intervals across the other two axes.</p>
     *
     * <p>Returns a list of float[12] arrays — four corners per face, each
     * corner is (x,y,z) in world space.</p>
     */
    private static List<float[]> collectLeadingFaces(AABBdc aabb, Vector3d velDir) {
        List<float[]> faces = new ArrayList<>();

        double ax = Math.abs(velDir.x);
        double ay = Math.abs(velDir.y);
        double az = Math.abs(velDir.z);

        if (ax >= ay && ax >= az) {
            // Dominant X — YZ face
            float faceX = velDir.x > 0
                    ? (float) aabb.maxX()
                    : (float) aabb.minX();
            buildFacesYZ(faces, faceX, (float)aabb.minY(), (float)aabb.maxY(),
                    (float)aabb.minZ(), (float)aabb.maxZ());
        } else if (ay >= ax && ay >= az) {
            // Dominant Y — XZ face
            float faceY = velDir.y > 0
                    ? (float) aabb.maxY()
                    : (float) aabb.minY();
            buildFacesXZ(faces, faceY, (float)aabb.minX(), (float)aabb.maxX(),
                    (float)aabb.minZ(), (float)aabb.maxZ());
        } else {
            // Dominant Z — XY face
            float faceZ = velDir.z > 0
                    ? (float) aabb.maxZ()
                    : (float) aabb.minZ();
            buildFacesXY(faces, faceZ, (float)aabb.minX(), (float)aabb.maxX(),
                    (float)aabb.minY(), (float)aabb.maxY());
        }

        return faces;
    }

    // Each method builds 1×1 quads tiling across the leading face.
    // Capped at MAX_FACES total to bound geometry cost on large ships.

    private static void buildFacesYZ(List<float[]> out, float x,
                                      float minY, float maxY, float minZ, float maxZ) {
        for (float y = minY; y < maxY && out.size() < MAX_FACES; y += 1f) {
            for (float z = minZ; z < maxZ && out.size() < MAX_FACES; z += 1f) {
                float y1 = Math.min(y + 1f, maxY);
                float z1 = Math.min(z + 1f, maxZ);
                out.add(new float[]{
                    x, y,  z,
                    x, y1, z,
                    x, y1, z1,
                    x, y,  z1
                });
            }
        }
    }

    private static void buildFacesXZ(List<float[]> out, float y,
                                      float minX, float maxX, float minZ, float maxZ) {
        for (float x = minX; x < maxX && out.size() < MAX_FACES; x += 1f) {
            for (float z = minZ; z < maxZ && out.size() < MAX_FACES; z += 1f) {
                float x1 = Math.min(x + 1f, maxX);
                float z1 = Math.min(z + 1f, maxZ);
                out.add(new float[]{
                    x,  y, z,
                    x1, y, z,
                    x1, y, z1,
                    x,  y, z1
                });
            }
        }
    }

    private static void buildFacesXY(List<float[]> out, float z,
                                      float minX, float maxX, float minY, float maxY) {
        for (float x = minX; x < maxX && out.size() < MAX_FACES; x += 1f) {
            for (float y = minY; y < maxY && out.size() < MAX_FACES; y += 1f) {
                float x1 = Math.min(x + 1f, maxX);
                float y1 = Math.min(y + 1f, maxY);
                out.add(new float[]{
                    x,  y,  z,
                    x1, y,  z,
                    x1, y1, z,
                    x,  y1, z
                });
            }
        }
    }
}
