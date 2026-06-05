package exp.CCnewmods.misanthrope_world.mixin.crackrender;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.misanthrope_world.crackrender.client.ClientCrackCache;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.RenderType;

import java.util.List;

/**
 * Suppresses the vanilla BakedQuad output for faces that have crack trough
 * geometry replacing them in the chunk mesh.
 * <p>
 * ── Why face suppression is needed ───────────────────────────────────────────
 * The chunk injector writes trough geometry into the same solid buffer. If
 * vanilla's original flat face quad is also present for that face, it sits
 * exactly coplanar with our trough opening and completely occludes it — not
 * z-fighting (depth tie broken by draw order) but total visual occlusion.
 * We must suppress it so our geometry is the only thing on that face.
 * <p>
 * ── Approach ─────────────────────────────────────────────────────────────────
 * 1. @Inject at HEAD of tesselateBlock() captures the current BlockPos into a
 * ThreadLocal (safe — compile() is per-thread per section).
 * <p>
 * 2. @Redirect on BakedModel.getQuads() returns List.of() for faces that have
 * crack geometry, suppressing all vanilla quad output for that face.
 * face == null (unculled/interior quads) is never suppressed.
 * <p>
 * ── Method targets ────────────────────────────────────────────────────────────
 * tesselateBlock:
 * net.minecraft.client.renderer.block.ModelBlockRenderer
 * tesselateBlock(BlockAndTintGetter, BakedModel, BlockState, BlockPos,
 * PoseStack, VertexConsumer, boolean, RandomSource, long, int,
 * ModelData, RenderType) → void
 * SRG: m_110908_ (confirmed from tsrg — matches "tesselateBlock" Parchment name)
 * <p>
 * getQuads call inside tesselateWithAO / tesselateWithoutAO:
 * net.minecraft.client.resources.model.BakedModel.getQuads(
 * BlockState, Direction, RandomSource, ModelData, RenderType) → List<BakedQuad>
 * We redirect both the AO and non-AO paths since the call appears in both.
 * <p>
 * ── Forge note ────────────────────────────────────────────────────────────────
 * Forge's renderBatched delegates to tesselateBlock after setting up ModelData.
 * Targeting tesselateBlock covers both the AO and non-AO code paths since it
 * calls tesselateWithAO or tesselateWithoutAO internally.
 */
@Mixin(value = ModelBlockRenderer.class, remap = false)
public class ModelBlockRendererCrackMixin {

    /**
     * ThreadLocal carrying the current block position being tessellated.
     * Set at tesselateBlock HEAD, read by the getQuads redirect.
     * ThreadLocal is correct here — chunk compilation is per-thread.
     */
    private static final ThreadLocal<BlockPos> CURRENT_POS = new ThreadLocal<>();

    // ── Capture current block pos ─────────────────────────────────────────────

    @Inject(
            method = "tesselateBlock",
            at = @At("HEAD"),
            remap = false
    )
    private void misanthrope_capturePos(BlockAndTintGetter level,
                                        BakedModel model,
                                        BlockState state,
                                        BlockPos pos,
                                        PoseStack pose,
                                        VertexConsumer consumer,
                                        boolean checkSides,
                                        RandomSource random,
                                        long seed,
                                        int overlay,
                                        ModelData modelData,
                                        RenderType renderType,
                                        CallbackInfo ci) {
        CURRENT_POS.set(pos);
    }

    @Inject(
            method = "tesselateBlock",
            at = @At("RETURN"),
            remap = false
    )
    private void misanthrope_clearPos(BlockAndTintGetter level,
                                      BakedModel model,
                                      BlockState state,
                                      BlockPos pos,
                                      PoseStack pose,
                                      VertexConsumer consumer,
                                      boolean checkSides,
                                      RandomSource random,
                                      long seed,
                                      int overlay,
                                      ModelData modelData,
                                      RenderType renderType,
                                      CallbackInfo ci) {
        CURRENT_POS.remove();
    }

    // ── Suppress BakedQuad output for cracked faces ───────────────────────────

    /**
     * Redirect BakedModel.getQuads() inside tesselateWithAO.
     * Returns empty list for faces with crack geometry replacing them.
     * face == null (unculled interior quads) is never suppressed.
     */
    @Redirect(
            method = "tesselateWithAO",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/resources/model/BakedModel.getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;Lnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)Ljava/util/List;"
            ),
            remap = false
    )
    private List<BakedQuad> misanthrope_suppressCrackedFaceAO(
            BakedModel model,
            BlockState state,
            Direction face,
            RandomSource random,
            ModelData modelData,
            RenderType renderType) {
        return suppressOrPassthrough(model, state, face, random, modelData, renderType);
    }

    /**
     * Same redirect for tesselateWithoutAO.
     */
    @Redirect(
            method = "tesselateWithoutAO",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/resources/model/BakedModel.getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;Lnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)Ljava/util/List;"
            ),
            remap = false
    )
    private List<BakedQuad> misanthrope_suppressCrackedFaceFlat(
            BakedModel model,
            BlockState state,
            Direction face,
            RandomSource random,
            ModelData modelData,
            RenderType renderType) {
        return suppressOrPassthrough(model, state, face, random, modelData, renderType);
    }

    // ── Shared logic ──────────────────────────────────────────────────────────

    private static List<BakedQuad> suppressOrPassthrough(BakedModel model,
                                                         BlockState state,
                                                         Direction face,
                                                         RandomSource random,
                                                         ModelData modelData,
                                                         RenderType renderType) {
        // Never suppress unculled (interior) quads
        if (face == null) {
            return model.getQuads(state, null, random, modelData, renderType);
        }

        BlockPos pos = CURRENT_POS.get();
        if (pos != null && ClientCrackCache.hasCrackOnFace(pos, face)) {
            // Suppress — our chunk mesh injector provides replacement geometry
            return List.of();
        }

        return model.getQuads(state, face, random, modelData, renderType);
    }
}
