package exp.CCnewmods.misanthrope_world.log_splitting;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

import java.util.List;

/**
 * Renders the {@link LogSplittingSlabBlock} by compositing two models:
 *
 * <ol>
 *   <li>The original slab's baked model (from the stored {@link BlockState})
 *       rendered at the block's position — giving the visual appearance of
 *       the original slab material.</li>
 *   <li>The log's baked model (from the log block ID) rendered sitting flush
 *       on top of the slab, translated up by 0.5 (half a block) so it sits
 *       on top of the slab surface.</li>
 * </ol>
 *
 * <p>A crack overlay is drawn on the log using
 * {@link OverlayTexture#crack(int, int)} based on the block entity's
 * {@link LogSplittingSlabBlockEntity#getProgressFraction()}, giving visual
 * feedback that the log is being split.
 */
@OnlyIn(Dist.CLIENT)
public class LogSplittingSlabRenderer
        implements BlockEntityRenderer<LogSplittingSlabBlockEntity> {

    private static final RandomSource RANDOM = RandomSource.create();

    public LogSplittingSlabRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(LogSplittingSlabBlockEntity be, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {

        Minecraft mc = Minecraft.getInstance();

        // --- Render the original slab ---
        BlockState slabState = be.getOriginalSlabState();
        if (slabState != null) {
            BakedModel slabModel = mc.getBlockRenderer()
                    .getBlockModelShaper().getBlockModel(slabState);
            renderBlockModel(slabModel, slabState, poseStack, buffers,
                    packedLight, OverlayTexture.NO_OVERLAY);
        }

        // --- Render the log standing upright on the slab ---
        String rawLogBlockId = be.getRawLogBlockId();
        if (!rawLogBlockId.isEmpty()) {
            BlockState logState = getLogState(rawLogBlockId);
            if (logState != null) {
                BakedModel logModel = mc.getBlockRenderer()
                        .getBlockModelShaper().getBlockModel(logState);

                // Crack overlay based on hit progress
                int crackStage = (int) (be.getProgressFraction() * 9);
                int overlay = crackStage > 0
                        ? OverlayTexture.pack(crackStage, false)
                        : OverlayTexture.NO_OVERLAY;

                poseStack.pushPose();
                // Translate up by 0.5 to sit on top of the bottom half-slab
                poseStack.translate(0, 0.5, 0);
                renderBlockModel(logModel, logState, poseStack, buffers,
                        packedLight, overlay);
                poseStack.popPose();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void renderBlockModel(BakedModel model, BlockState state,
                                         PoseStack poseStack,
                                         MultiBufferSource buffers,
                                         int packedLight, int overlay) {
        VertexConsumer vc = buffers.getBuffer(RenderType.cutoutMipped());
        PoseStack.Pose pose = poseStack.last();
        RANDOM.setSeed(42L);

        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            RANDOM.setSeed(42L);
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                    model.getQuads(state, dir, RANDOM, ModelData.EMPTY, null);
            for (var quad : quads) {
                vc.putBulkData(pose, quad, 1f, 1f, 1f, 1f, packedLight, overlay, true);
            }
        }
        // Unculled quads
        RANDOM.setSeed(42L);
        List<net.minecraft.client.renderer.block.model.BakedQuad> unculled =
                model.getQuads(state, null, RANDOM, ModelData.EMPTY, null);
        for (var quad : unculled) {
            vc.putBulkData(pose, quad, 1f, 1f, 1f, 1f, packedLight, overlay, true);
        }
    }

    /**
     * Looks up the {@link BlockState} for the log by its full registry ID
     * (e.g. {@code "minecraft:oak_log"}) as stored in {@link LogSplittingSlabBlockEntity#getRawLogBlockId()}.
     */
    private static BlockState getLogState(String rawLogBlockId) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getOptional(new net.minecraft.resources.ResourceLocation(rawLogBlockId))
                    .map(net.minecraft.world.level.block.Block::defaultBlockState)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
