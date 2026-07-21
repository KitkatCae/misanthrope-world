package exp.CCnewmods.misanthrope_world.charcoal_pit;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.HashMap;
import java.util.Map;

/**
 * BEWLR for {@link CutWoodItem}.
 *
 * <p>Generates a 16×16 {@link DynamicTexture} per wood type that looks like
 * the end of a log — a cross-section centre surrounded by a bark border:
 *
 * <pre>
 *   ┌────────────────┐
 *   │  bark (side)   │  ← 4px border from stripped log side texture
 *   │  ┌──────────┐  │
 *   │  │  grain   │  │  ← 8×8 centre from log top texture
 *   │  │ (cross)  │  │
 *   │  └──────────┘  │
 *   │  bark (side)   │
 *   └────────────────┘
 * </pre>
 *
 * <p>Textures are sampled from the block atlas via {@link WoodTextureResolver}
 * and composited at startup. Results are cached per wood type ID so the
 * generation cost (a few NativeImage pixel copies) is paid at most once per
 * wood type per session. The cache is cleared by {@link #clearCache()} which
 * should be called from the {@code ModelEvent.BakingCompleted} handler.
 *
 * <p>The composited texture is then used as a custom {@link RenderType} passed
 * to {@code ItemRenderer.render} via a {@link MultiBufferSource} wrapper,
 * giving correct display transforms, lighting and overlay for all contexts
 * (GUI, hand, ground, item frame).
 */
@OnlyIn(Dist.CLIENT)
public class CutWoodItemRenderer extends BlockEntityWithoutLevelRenderer {

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    private static CutWoodItemRenderer INSTANCE;

    public static CutWoodItemRenderer getInstance() {
        if (INSTANCE == null) {
            Minecraft mc = Minecraft.getInstance();
            INSTANCE = new CutWoodItemRenderer(
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

    public CutWoodItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
        super(dispatcher, models);
    }

    // -----------------------------------------------------------------------
    // Texture cache
    // -----------------------------------------------------------------------

    /**
     * Dynamic texture ResourceLocations keyed by wood type ID.
     */
    private static final Map<String, ResourceLocation> TEXTURE_CACHE = new HashMap<>();
    private static int texCounter = 0;

    /**
     * Clear on resource reload (ModelEvent.BakingCompleted).
     * Also releases the old DynamicTexture objects from the texture manager
     * so GPU memory isn't leaked across reloads.
     */
    public static void clearCache() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : TEXTURE_CACHE.values()) {
            mc.getTextureManager().release(loc);
        }
        TEXTURE_CACHE.clear();
        texCounter = 0;
    }

    // -----------------------------------------------------------------------
    // Render
    // -----------------------------------------------------------------------

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context,
                             PoseStack poseStack, MultiBufferSource buffers,
                             int packedLight, int packedOverlay) {

        String woodType = CutWoodItem.getWoodTypeId(stack);
        if (woodType == null || woodType.isEmpty()) woodType = "minecraft:oak";

        final ResourceLocation texLoc = TEXTURE_CACHE.computeIfAbsent(
                woodType, CutWoodItemRenderer::buildTexture);

        RenderType dynType = RenderType.itemEntityTranslucentCull(texLoc);
        MultiBufferSource wrapped = rt -> buffers.getBuffer(dynType);

        BakedModel model = Minecraft.getInstance().getItemRenderer()
                .getItemModelShaper().getItemModel(stack);
        if (model == null) return;

        Minecraft.getInstance().getItemRenderer().render(
                stack, context, false,
                poseStack, wrapped, packedLight, packedOverlay, model);
    }

    // -----------------------------------------------------------------------
    // Texture generation
    // -----------------------------------------------------------------------

    /**
     * Builds a 16×16 NativeImage compositing the cross-section end grain in
     * the centre and bark around the border, then uploads it as a DynamicTexture.
     *
     * <p>Layout:
     * <ul>
     *   <li>Border (0–3 and 12–15 on both axes): samples from the stripped log
     *       side texture, tiling a 4-wide strip of bark.</li>
     *   <li>Centre (4–11 on both axes): samples from the log top texture,
     *       using the middle 8×8 region of the 16×16 top texture.</li>
     * </ul>
     */
    private static ResourceLocation buildTexture(String woodType) {
        TextureAtlasSprite sideSprite = WoodTextureResolver.getSideSprite(woodType);
        TextureAtlasSprite crossSprite = WoodTextureResolver.getCrossSprite(woodType);

        // NativeImage ownership transfers to DynamicTexture — do not close here
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 16, 16, true);

        for (int py = 0; py < 16; py++) {
            for (int px = 0; px < 16; px++) {
                boolean inCentre = px >= 4 && px < 12 && py >= 4 && py < 12;

                int argb;
                if (inCentre) {
                    // Sample from cross sprite's centre 8×8 region
                    // Map [4,12) → [4,12) within the 16×16 sprite
                    argb = sampleSprite(crossSprite, px, py, 16, 16);
                } else {
                    // Sample from side sprite — bark texture
                    // The side texture is 16-wide; we only need a 4-wide strip
                    // Map px,py → the bark texture using modular tiling
                    argb = sampleSprite(sideSprite, px % 16, py % 4, 16, 4);
                }

                // NativeImage.setPixelRGBA expects ABGR
                img.setPixelRGBA(px, py, argbToAbgr(argb));
            }
        }

        ResourceLocation loc = new ResourceLocation(
                "misanthrope_world", "dynamic/cut_wood_" + texCounter++);
        Minecraft.getInstance().getTextureManager()
                .register(loc, new DynamicTexture(img));
        return loc;
    }

    // -----------------------------------------------------------------------
    // Sprite sampling helpers
    // -----------------------------------------------------------------------

    /**
     * Samples a pixel from a sprite by mapping (px, py) within a virtual
     * (srcW × srcH) space into the sprite's actual atlas UV region.
     *
     * <p>Returns packed ARGB.
     */
    private static int sampleSprite(TextureAtlasSprite sprite,
                                    int px, int py, int srcW, int srcH) {
        try {
            NativeImage image = sprite.contents().byMipLevel[0];
            if (image == null) return 0xFF_888888;

            int spriteX = sprite.getX();
            int spriteY = sprite.getY();
            int spriteW = sprite.contents().width();
            int spriteH = sprite.contents().height();

            // Map px/py within srcW×srcH to sprite pixel coordinates
            int atlasX = spriteX + (px * spriteW / srcW);
            int atlasY = spriteY + (py * spriteH / srcH);

            // NativeImage.getPixelRGBA returns ABGR
            int abgr = image.getPixelRGBA(atlasX, atlasY);
            return abgrToArgb(abgr);

        } catch (Exception e) {
            return 0xFF_888888;
        }
    }

    /**
     * Convert packed ABGR (NativeImage format) to packed ARGB.
     */
    private static int abgrToArgb(int abgr) {
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = (abgr) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Convert packed ARGB to packed ABGR (NativeImage format).
     */
    private static int argbToAbgr(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = (argb) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
