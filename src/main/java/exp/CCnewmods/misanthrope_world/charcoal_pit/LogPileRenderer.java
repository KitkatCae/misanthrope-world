package exp.CCnewmods.misanthrope_world.charcoal_pit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.data.ModelData;

import java.util.List;

/**
 * Renders the Log Pile block and item with per-slot wood textures.
 *
 * <p>The template model at
 * {@code misanthrope_world:block/template/log_pile/log_pile} has 32 placeholder
 * texture keys ({@code side1}–{@code side16}, {@code cross1}–{@code cross16}).
 * This renderer reads the block entity's slot data, resolves the correct
 * {@link TextureAtlasSprite} for each slot via {@link WoodTextureResolver},
 * and emits the model's quads with UV-remapped vertex consumers so each
 * log element displays the correct wood's bark and end-grain textures.
 *
 * <p>Empty slots are skipped (no quads emitted for that element).
 *
 * <h3>Item rendering</h3>
 * When rendering as an item (in hand, GUI, ground), the held
 * {@link CutWoodItem}'s wood type is read from NBT and all 16 slots are
 * filled with that same type, giving a preview of what a full pile of that
 * wood would look like.
 */
@OnlyIn(Dist.CLIENT)
public class LogPileRenderer extends BlockEntityWithoutLevelRenderer {

    private static final RandomSource RANDOM = RandomSource.create();

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    private static LogPileRenderer INSTANCE;

    public static LogPileRenderer getInstance() {
        if (INSTANCE == null) {
            Minecraft mc = Minecraft.getInstance();
            INSTANCE = new LogPileRenderer(
                    mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
        }
        return INSTANCE;
    }

    public static final IClientItemExtensions CLIENT_EXTENSIONS = new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return getInstance();
        }
    };

    public LogPileRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
        super(dispatcher, models);
    }

    // -----------------------------------------------------------------------
    // Item rendering
    // -----------------------------------------------------------------------

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context,
                             PoseStack poseStack, MultiBufferSource buffers,
                             int packedLight, int packedOverlay) {

        // For item display: fill all slots with the held cut wood's type
        String[] slots = new String[LogPileBlockEntity.CAPACITY];
        String woodType = CutWoodItem.getWoodTypeId(stack);
        if (woodType == null || woodType.isEmpty()) woodType = "minecraft:oak";
        for (int i = 0; i < slots.length; i++) slots[i] = woodType;

        renderPile(slots, LogPileBlockEntity.CAPACITY, poseStack, buffers,
                packedLight, packedOverlay);
    }

    // -----------------------------------------------------------------------
    // Block rendering (called by LogPileBlockEntityRenderer)
    // -----------------------------------------------------------------------

    public void renderForBlock(LogPileBlockEntity be, PoseStack poseStack,
                               MultiBufferSource buffers, int packedLight,
                               int packedOverlay) {
        renderPile(be.getSlotsSnapshot(), be.getCount(),
                poseStack, buffers, packedLight, packedOverlay);
    }

    // -----------------------------------------------------------------------
    // Core render
    // -----------------------------------------------------------------------

    /**
     * Maps a baked quad to a slot index 0–15 by computing the centroid of its
     * four vertex positions and mapping into the 4×4 grid layout of the model.
     *
     * <p>Each element occupies a 4×4×16 column (in block-pixel space):
     * <pre>
     *   row 0 (Y  0– 4): slots  0  1  2  3   (X 0–4, 4–8, 8–12, 12–16)
     *   row 1 (Y  4– 8): slots  4  5  6  7
     *   row 2 (Y  8–12): slots  8  9  10 11
     *   row 3 (Y 12–16): slots 12 13  14 15
     * </pre>
     * Vertex data stride is 8 ints: x, y, z, color, u, v, uv2, normal.
     * Positions are floats in block units (0.0–1.0).
     */
    private static int quadSlotIndex(BakedQuad quad) {
        int[] vd = quad.getVertices();
        if (vd.length < 8 * 4) return -1;

        float sumX = 0, sumY = 0;
        for (int v = 0; v < 4; v++) {
            int base = v * 8;
            sumX += Float.intBitsToFloat(vd[base]);
            sumY += Float.intBitsToFloat(vd[base + 1]);
        }
        // centroid in 0.0–1.0; each cell is 0.25 wide/tall
        int col = Math.min(3, (int) ((sumX / 4f) * 4f));
        int row = Math.min(3, (int) ((sumY / 4f) * 4f));
        return row * 4 + col;
    }

    private void renderPile(String[] slots, int filledCount,
                            PoseStack poseStack, MultiBufferSource buffers,
                            int packedLight, int packedOverlay) {

        BakedModel model = Minecraft.getInstance().getModelManager()
                .getModel(CharcoalPitRegistration.LOG_PILE_MODEL_LOC);
        if (model == null) return;

        // Resolve target sprites for each filled slot up front
        TextureAtlasSprite[] sideSprites = new TextureAtlasSprite[LogPileBlockEntity.CAPACITY];
        TextureAtlasSprite[] crossSprites = new TextureAtlasSprite[LogPileBlockEntity.CAPACITY];
        for (int i = 0; i < filledCount && i < LogPileBlockEntity.CAPACITY; i++) {
            if (slots[i] != null && !slots[i].isEmpty()) {
                sideSprites[i] = WoodTextureResolver.getSideSprite(slots[i]);
                crossSprites[i] = WoodTextureResolver.getCrossSprite(slots[i]);
            }
        }

        // The model uses a single shared placeholder sprite for each texture role
        TextureAtlasSprite placeholderSide = getPlaceholderSprite("side");
        TextureAtlasSprite placeholderCross = getPlaceholderSprite("cross");

        PoseStack.Pose pose = poseStack.last();
        VertexConsumer base = buffers.getBuffer(RenderType.cutoutMipped());

        // Walk every quad once. Determine which slot it belongs to via centroid,
        // skip if that slot is empty, then emit with the correct target sprite.
        for (Direction dir : new Direction[]{null,
                Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST,
                Direction.UP, Direction.DOWN}) {

            RANDOM.setSeed(42L);
            List<BakedQuad> quads = model.getQuads(null, dir, RANDOM,
                    ModelData.EMPTY, null);

            for (BakedQuad quad : quads) {
                int slot = quadSlotIndex(quad);
                if (slot < 0 || slot >= filledCount || sideSprites[slot] == null) continue;

                TextureAtlasSprite quadSprite = quad.getSprite();

                if (quadSprite.equals(placeholderSide)) {
                    VertexConsumer vc = placeholderSide.equals(sideSprites[slot])
                            ? base
                            : new SpriteShiftConsumer(base, placeholderSide, sideSprites[slot]);
                    vc.putBulkData(pose, quad, 1f, 1f, 1f, 1f, packedLight, packedOverlay, true);

                } else if (quadSprite.equals(placeholderCross)) {
                    VertexConsumer vc = placeholderCross.equals(crossSprites[slot])
                            ? base
                            : new SpriteShiftConsumer(base, placeholderCross, crossSprites[slot]);
                    vc.putBulkData(pose, quad, 1f, 1f, 1f, 1f, packedLight, packedOverlay, true);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Placeholder sprite lookup
    // -----------------------------------------------------------------------

    private static TextureAtlasSprite getPlaceholderSprite(String type) {
        ResourceLocation loc = new ResourceLocation("misanthrope_world",
                "block/log_pile_placeholder_" + type);
        return Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(loc);
    }

    // -----------------------------------------------------------------------
    // SpriteShiftConsumer — reused from MortarRenderer pattern
    // -----------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    static final class SpriteShiftConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final TextureAtlasSprite from, to;

        SpriteShiftConsumer(VertexConsumer delegate,
                            TextureAtlasSprite from, TextureAtlasSprite to) {
            this.delegate = delegate;
            this.from = from;
            this.to = to;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            delegate.color(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            float relU = (u - from.getU0()) / (from.getU1() - from.getU0());
            float relV = (v - from.getV0()) / (from.getV1() - from.getV0());
            delegate.uv(to.getU0() + relU * (to.getU1() - to.getU0()),
                    to.getV0() + relV * (to.getV1() - to.getV0()));
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }
}
