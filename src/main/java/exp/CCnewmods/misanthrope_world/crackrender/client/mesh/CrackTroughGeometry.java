package exp.CCnewmods.misanthrope_world.crackrender.client.mesh;

import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.data.VeinSegment;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

import java.util.Random;

/**
 * Generates the trough geometry for a single VeinSegment on a single face.
 * <p>
 * ── Geometry model ────────────────────────────────────────────────────────────
 * For each face the vein crosses, we emit:
 * <p>
 * 1. SLOPE QUADS (solid layer): The face is split into triangles either side
 * of the crack path. Each triangle fans from the block face plane down to
 * the trough floor at depth = cause.troughDepth. This replaces the flat
 * face quad that vanilla would have drawn.
 * <p>
 * [face plane, normal out]
 * ┌───────┐     Trough path in UV space
 * │╲  A  /│     A & B = sloped face triangles, fan from trough edge
 * │ ╲   / │     C = trough floor (darkest)
 * │  ╲ /  │     D = inner walls (angled, dark)
 * │ B ╳ B │
 * │  / \  │
 * └───────┘
 * <p>
 * 2. TROUGH FLOOR (solid layer): Narrow quad at the bottom of the trough,
 * very dark vertex color. Width = lineWidth * 0.5.
 * <p>
 * 3. INNER WALLS (solid layer): Two thin quads sloping from face plane
 * down to trough floor on either side of the crack line.
 * <p>
 * 4. VOID QUAD (cutout layer, level 3 only): Fully transparent quad at the
 * back of the trough, punching a hole through so light bleeds through.
 * Placed at depth = 1.0 (through the block face).
 * <p>
 * 5. FACE REPLACEMENT (solid layer): The two triangles of the original face
 * but with the trough carved out. These fill the face area outside the trough.
 * Since we suppressed vanilla's BakedQuad for this face, we must provide
 * the full face geometry ourselves, sampling the block's texture UV.
 * <p>
 * ── Vertex format ─────────────────────────────────────────────────────────────
 * BLOCK vertex format: position (3f), color (4ub), UV (2f), overlay (2s),
 * lightmap (2s), normal (3b).
 * We emit vertices matching this format via VertexConsumer.
 * <p>
 * ── Texture handling ──────────────────────────────────────────────────────────
 * The face replacement quads need the block's own texture UVs. These are passed
 * in from the BakedQuad data extracted before suppression in the mixin.
 * Trough geometry (floor, walls, void) uses a dedicated 1×1 white pixel from
 * the misanthrope_core texture atlas, colored purely by vertex color.
 */
public final class CrackTroughGeometry {

    // Solid crack interior color (near-black, slight warm tint)
    private static final int FLOOR_R = 12, FLOOR_G = 8, FLOOR_B = 6;
    // Wall color (dark, slightly lighter than floor)
    private static final int WALL_R = 22, WALL_G = 16, WALL_B = 14;

    private CrackTroughGeometry() {
    }

    /**
     * Emit all trough geometry for one VeinSegment on one face of its block.
     *
     * @param solid         VertexConsumer for the solid render layer (trough + face replacement)
     * @param cutout        VertexConsumer for the cutout render layer (void gap at level 3)
     * @param pose          the current PoseStack.Pose (pose matrix + normal matrix)
     * @param entry         the CrackEntry for this block
     * @param segment       the specific vein segment to render
     * @param face          which face of the block this is (NORTH/SOUTH/EAST/WEST/UP/DOWN)
     * @param faceQuadUVs   the original BakedQuad's UV data [4][2] for face replacement
     *                      null if the face quad was not available (fallback to no replacement)
     * @param packedLight   combined sky+block light
     * @param packedOverlay combined overlay
     */
    public static void emitFace(VertexConsumer solid,
                                VertexConsumer cutout,
                                com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                                CrackEntry entry,
                                VeinSegment segment,
                                Direction face,
                                float[][] faceQuadUVs,
                                int packedLight,
                                int packedOverlay) {

        CrackCause cause = entry.cause();
        int level = entry.level();
        float depth = cause.troughDepth * (1f + (level - 1) * 0.4f); // deeper as level rises
        depth = Math.min(depth, 0.45f); // cap — don't go past block centre
        float lineHalfWidth = cause.baseLineWidth(level) * 0.5f;

        // Determine entry/exit UVs on this face
        // If this face is the entry face, use entry UV; if exit, use exit UV;
        // for origin/terminus, generate a centre point
        float u1, v1, u2, v2;
        if (face.equals(segment.entryFace())) {
            u1 = segment.entryU();
            v1 = segment.entryV();
            // Extend toward exit or generate based on seed
            u2 = segment.exitFace() != null && !face.equals(segment.exitFace())
                    ? 0.5f : segment.exitU();
            v2 = segment.exitFace() != null && !face.equals(segment.exitFace())
                    ? 0.5f : segment.exitV();
        } else if (face.equals(segment.exitFace())) {
            u1 = segment.exitU();
            v1 = segment.exitV();
            u2 = 0.5f;
            v2 = 0.5f; // toward block centre
        } else {
            return; // this face isn't crossed by this segment
        }

        // Add some curvature from the seed
        Random curveRng = new Random(segment.seed() ^ (long) face.ordinal() * 0xDEADBEEFL);
        float midU = (u1 + u2) * 0.5f + (curveRng.nextFloat() - 0.5f) * 0.08f;
        float midV = (v1 + v2) * 0.5f + (curveRng.nextFloat() - 0.5f) * 0.08f;

        Matrix4f mat = pose.pose();
        Matrix3f normal = pose.normal();

        // Face normal vector for this face
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        // ── 1. Face replacement quads (full face, solid layer) ────────────────
        // We emit the full face as two triangles, but with the crack path
        // excluded (triangulated around it). For simplicity in this pass,
        // we emit the full face and rely on the trough geometry on top.
        // Full face replacement with proper texture UVs:
        if (faceQuadUVs != null) {
            emitFaceReplacement(solid, mat, normal, face, faceQuadUVs,
                    packedLight, packedOverlay, nx, ny, nz);
        }

        // ── 2. Trough geometry (solid layer) ─────────────────────────────────
        // Emit trough in face-local UV space, transformed to 3D by faceToWorld

        // Crack path points in face UV space
        float[] p1 = faceUVToWorld(face, u1, v1, 0f);
        float[] mid = faceUVToWorld(face, midU, midV, 0f);
        float[] p2 = faceUVToWorld(face, u2, v2, 0f);

        // Perpendicular to crack line in UV space
        float dU = u2 - u1, dV = v2 - v1;
        float segLen = (float) Math.sqrt(dU * dU + dV * dV);
        if (segLen < 0.001f) return;
        float perpU = -dV / segLen * lineHalfWidth;
        float perpV = dU / segLen * lineHalfWidth;

        // Trough edge points (left and right of crack line, at face surface)
        float[] leftSurface1 = faceUVToWorld(face, u1 + perpU, v1 + perpV, 0f);
        float[] rightSurface1 = faceUVToWorld(face, u1 - perpU, v1 - perpV, 0f);
        float[] leftSurface2 = faceUVToWorld(face, u2 + perpU, v2 + perpV, 0f);
        float[] rightSurface2 = faceUVToWorld(face, u2 - perpU, v2 - perpV, 0f);

        // Trough floor points (same UV but pushed back by depth)
        float[] leftFloor1 = faceUVToWorld(face, u1 + perpU * 0.5f, v1 + perpV * 0.5f, depth);
        float[] rightFloor1 = faceUVToWorld(face, u1 - perpU * 0.5f, v1 - perpV * 0.5f, depth);
        float[] leftFloor2 = faceUVToWorld(face, u2 + perpU * 0.5f, v2 + perpV * 0.5f, depth);
        float[] rightFloor2 = faceUVToWorld(face, u2 - perpU * 0.5f, v2 - perpV * 0.5f, depth);

        // Left inner wall: surface → floor
        emitQuad(solid, mat, normal,
                leftSurface1, leftSurface2, leftFloor2, leftFloor1,
                WALL_R, WALL_G, WALL_B, 220,
                0f, 0f, 0f, 0f, packedLight, packedOverlay, nx, ny, nz);

        // Right inner wall
        emitQuad(solid, mat, normal,
                rightSurface1, rightSurface2, rightFloor2, rightFloor1,
                WALL_R, WALL_G, WALL_B, 220,
                0f, 0f, 0f, 0f, packedLight, packedOverlay, nx, ny, nz);

        // Trough floor
        emitQuad(solid, mat, normal,
                leftFloor1, leftFloor2, rightFloor2, rightFloor1,
                FLOOR_R, FLOOR_G, FLOOR_B, 255,
                0f, 0f, 0f, 0f, packedLight, packedOverlay, nx, ny, nz);

        // ── 3. Heat glow on inner walls at level 3 ────────────────────────────
        if (level >= CrackEntry.LEVEL_SEVERE && cause.hasGlow()) {
            int glowColor = cause.glowColor();
            int gr = (glowColor >> 16) & 0xFF;
            int gg = (glowColor >> 8) & 0xFF;
            int gb = glowColor & 0xFF;
            int ga = 160;

            emitQuad(solid, mat, normal,
                    leftFloor1, leftFloor2, rightFloor2, rightFloor1,
                    gr, gg, gb, ga,
                    0f, 0f, 0f, 0f, packedLight, packedOverlay, nx, ny, nz);
        }

        // ── 4. Void gap (cutout layer, level 3 only) ──────────────────────────
        if (level >= CrackEntry.LEVEL_SEVERE) {
            // Wider void at full depth (through the block face)
            float voidHalfW = lineHalfWidth * 1.8f;
            float voidPerpU = -dV / segLen * voidHalfW;
            float voidPerpV = dU / segLen * voidHalfW;

            float[] voidLeft1 = faceUVToWorld(face, u1 + voidPerpU, v1 + voidPerpV, 1.0f);
            float[] voidRight1 = faceUVToWorld(face, u1 - voidPerpU, v1 - voidPerpV, 1.0f);
            float[] voidLeft2 = faceUVToWorld(face, u2 + voidPerpU, v2 + voidPerpV, 1.0f);
            float[] voidRight2 = faceUVToWorld(face, u2 - voidPerpU, v2 - voidPerpV, 1.0f);

            // Fully transparent quad — alpha=0 punches the hole
            emitQuad(cutout, mat, normal,
                    voidLeft1, voidLeft2, voidRight2, voidRight1,
                    0, 0, 0, 0,
                    0f, 0f, 0f, 0f, packedLight, packedOverlay, nx, ny, nz);
        }
    }

    // ── Face replacement emission ─────────────────────────────────────────────

    /**
     * Re-emit the full face using the original BakedQuad UVs. This provides the
     * visible block texture on the parts of the face not occupied by the trough.
     * <p>
     * faceQuadUVs: [4][2] array — UV coordinates for each of the 4 quad vertices
     * in the order: [bottom-left, bottom-right, top-right, top-left]
     */
    private static void emitFaceReplacement(VertexConsumer vc,
                                            Matrix4f mat, Matrix3f normal,
                                            Direction face,
                                            float[][] uvs,
                                            int light, int overlay,
                                            float nx, float ny, float nz) {
        // 4 corners of the face in local block space
        float[][] corners = faceCorners(face);
        for (int i = 0; i < 4; i++) {
            float[] c = corners[i];
            vc.vertex(mat, c[0], c[1], c[2])
                    .color(255, 255, 255, 255) // white — texture tint applied separately
                    .uv(uvs[i][0], uvs[i][1])
                    .overlayCoords(overlay)
                    .uv2(light)
                    .normal(normal, nx, ny, nz)
                    .endVertex();
        }
    }

    // ── Quad emission ─────────────────────────────────────────────────────────

    private static void emitQuad(VertexConsumer vc,
                                 Matrix4f mat, Matrix3f normal,
                                 float[] a, float[] b, float[] c, float[] d,
                                 int r, int g, int bl, int alpha,
                                 float u1, float v1, float u2, float v2,
                                 int light, int overlay,
                                 float nx, float ny, float nz) {
        vertex(vc, mat, normal, a, r, g, bl, alpha, u1, v1, light, overlay, nx, ny, nz);
        vertex(vc, mat, normal, b, r, g, bl, alpha, u2, v1, light, overlay, nx, ny, nz);
        vertex(vc, mat, normal, c, r, g, bl, alpha, u2, v2, light, overlay, nx, ny, nz);
        vertex(vc, mat, normal, d, r, g, bl, alpha, u1, v2, light, overlay, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc,
                               Matrix4f mat, Matrix3f normal,
                               float[] pos,
                               int r, int g, int b, int a,
                               float u, float v,
                               int light, int overlay,
                               float nx, float ny, float nz) {
        vc.vertex(mat, pos[0], pos[1], pos[2])
                .color(r, g, b, a)
                .uv(u, v)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(normal, nx, ny, nz)
                .endVertex();
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    /**
     * Convert face UV [0,1]² + depth to block-local XYZ [0,1]³.
     * depth=0 is the face surface, depth>0 is recessed into the block.
     * <p>
     * The "into the block" direction is opposite to the face normal.
     */
    private static float[] faceUVToWorld(Direction face, float u, float v, float depth) {
        // inset = depth along -face.normal
        float id = depth; // how far to push inward
        return switch (face) {
            case UP -> new float[]{u, 1f - id, v};
            case DOWN -> new float[]{u, id, 1f - v};
            case NORTH -> new float[]{1f - u, v, id};
            case SOUTH -> new float[]{u, v, 1f - id};
            case WEST -> new float[]{id, v, 1f - u};
            case EAST -> new float[]{1f - id, v, u};
        };
    }

    /**
     * The 4 corner vertices of a block face in local block space [0,1]³,
     * in standard quad order (bottom-left, bottom-right, top-right, top-left).
     */
    private static float[][] faceCorners(Direction face) {
        return switch (face) {
            case UP -> new float[][]{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}};
            case DOWN -> new float[][]{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}};
            case NORTH -> new float[][]{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}};
            case SOUTH -> new float[][]{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}};
            case WEST -> new float[][]{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}};
            case EAST -> new float[][]{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}};
        };
    }
}
