package exp.CCnewmods.misanthrope_world.physics.collapse.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.misanthrope_world.physics.collapse.LatticeCollapseBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Renders a {@link LatticeCollapseBlockEntity} by running marching cubes on
 * the interpolated density field and emitting textured triangles.
 *
 * <h3>Triplanar texture mapping</h3>
 * For each triangle vertex, UV coordinates are computed from two of the three
 * world-space axes (XY, YZ, or XZ) depending on which face the triangle is most
 * aligned with. This avoids UV seams at arbitrary cut angles — any cross-section
 * through the block looks correct regardless of angle.
 *
 * <h3>Per-frame interpolation</h3>
 * The client field is updated from delta packets every 4 server ticks. Between
 * updates, {@link LatticeCollapseBlockEntity#getInterpolatedField} lerps between
 * the previous and current field using wall-clock time for smooth animation.
 *
 * <h3>Normals</h3>
 * Per-triangle flat normals computed from the cross product of the two edge
 * vectors. No smooth normals needed — fractured stone should look angular.
 */
public class LatticeCollapseRenderer implements BlockEntityRenderer<LatticeCollapseBlockEntity> {

    /**
     * Ticks between server field updates (must match BE).
     */
    private static final float SERVER_UPDATE_INTERVAL_NS = 4f * 50_000_000f; // 4 ticks in nanos

    public LatticeCollapseRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(LatticeCollapseBlockEntity be, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {

        // Compute interpolation factor based on time since last packet
        long now = System.nanoTime();
        long lastUpdate = be.getClientLastUpdate();
        float t = lastUpdate == 0 ? 0f
                : Math.min(1f, (now - lastUpdate) / SERVER_UPDATE_INTERVAL_NS);

        float[][][] field = be.getInterpolatedField(t);

        // Extract triangles via marching cubes
        List<float[]> triangles = MarchingCubes.extractTriangles(field);
        if (triangles.isEmpty()) return;

        // Get texture sprite for the original block
        TextureAtlasSprite sprite = getSprite(be.getOriginalBlock());
        if (sprite == null) return;

        VertexConsumer vc = buffers.getBuffer(RenderType.cutout());
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        Matrix3f nor = pose.normal();

        for (float[] tri : triangles) {
            // tri = {x0,y0,z0, x1,y1,z1, x2,y2,z2} in [0,1]³
            float x0 = tri[0], y0 = tri[1], z0 = tri[2];
            float x1 = tri[3], y1 = tri[4], z1 = tri[5];
            float x2 = tri[6], y2 = tri[7], z2 = tri[8];

            // Flat normal from cross product
            float ex = x1 - x0, ey = y1 - y0, ez = z1 - z0;
            float fx = x2 - x0, fy = y2 - y0, fz = z2 - z0;
            float nx = ey * fz - ez * fy;
            float ny = ez * fx - ex * fz;
            float nz = ex * fy - ey * fx;
            float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nLen < 1e-6f) continue;
            nx /= nLen;
            ny /= nLen;
            nz /= nLen;

            // Triplanar: pick dominant axis for UV
            float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);

            emitVertex(vc, mat, nor, sprite, x0, y0, z0, nx, ny, nz, ax, ay, az, packedLight, packedOverlay);
            emitVertex(vc, mat, nor, sprite, x1, y1, z1, nx, ny, nz, ax, ay, az, packedLight, packedOverlay);
            emitVertex(vc, mat, nor, sprite, x2, y2, z2, nx, ny, nz, ax, ay, az, packedLight, packedOverlay);
        }
    }

    private static void emitVertex(VertexConsumer vc, Matrix4f mat, Matrix3f nor,
                                   TextureAtlasSprite sprite,
                                   float x, float y, float z,
                                   float nx, float ny, float nz,
                                   float ax, float ay, float az,
                                   int packedLight, int packedOverlay) {
        // Triplanar UV projection
        float u, v;
        if (ay >= ax && ay >= az) {
            // Top/bottom face dominant — project XZ
            u = sprite.getU(x);
            v = sprite.getV(z);
        } else if (ax >= az) {
            // East/West dominant — project YZ
            u = sprite.getU(z);
            v = sprite.getV(y);
        } else {
            // North/South dominant — project XY
            u = sprite.getU(x);
            v = sprite.getV(y);
        }

        vc.vertex(mat, x, y, z)
                .color(1f, 1f, 1f, 1f)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(packedLight)
                .normal(nor, nx, ny, nz)
                .endVertex();
    }

    @Nullable
    private static TextureAtlasSprite getSprite(@Nullable ResourceLocation blockId) {
        if (blockId == null) return getFallback();
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        BlockState state = block.defaultBlockState();
        // Get the baked model's particle texture — this is what vanilla uses for
        // block-break particles, so it's always the "main" texture of the block.
        var model = Minecraft.getInstance().getBlockRenderer()
                .getBlockModel(state);
        TextureAtlasSprite sprite = model.getParticleIcon();
        return sprite != null ? sprite : getFallback();
    }

    @Nullable
    private static TextureAtlasSprite getFallback() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(new ResourceLocation("minecraft", "block/stone"));
    }

    @Override
    public boolean shouldRenderOffScreen(LatticeCollapseBlockEntity be) {
        return true; // always render — collapsing block shouldn't pop out of view
    }
}
