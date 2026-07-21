package exp.CCnewmods.misanthrope_world.charcoal_pit;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Renders the placed {@link LogPileBlock} by delegating to
 * {@link LogPileRenderer}, which reads the block entity's slot data and
 * emits correctly textured quads for each log element.
 *
 * <p>Register in {@code MisanthropeClient.onClientSetup}:
 * <pre>
 *   BlockEntityRenderers.register(
 *       CharcoalPitRegistration.LOG_PILE_BE.get(),
 *       LogPileBlockEntityRenderer::new);
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public class LogPileBlockEntityRenderer implements BlockEntityRenderer<LogPileBlockEntity> {

    public LogPileBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(LogPileBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        LogPileRenderer.getInstance().renderForBlock(
                be, poseStack, buffers, packedLight, packedOverlay);
    }
}
