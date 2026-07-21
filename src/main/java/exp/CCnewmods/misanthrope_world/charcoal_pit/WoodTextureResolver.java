package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves bark (side) and end-grain (cross) textures for a log block,
 * given a wood-type ID e.g. {@code "minecraft:oak"} (NOT the full block ID).
 *
 * <h3>Side texture</h3>
 * Tries {@code <namespace>:block/<wood>_log} (the log's side texture),
 * then the stripped variant {@code <namespace>:block/stripped_<wood>_log} as a fallback.
 *
 * <h3>Cross texture</h3>
 * Tries {@code <namespace>:block/<wood>_log_top}, falls back to side.
 *
 * <p>Cache cleared on resource reload via {@link #clearCache()}.
 */
@OnlyIn(Dist.CLIENT)
public final class WoodTextureResolver {

    private WoodTextureResolver() {
    }

    private static final Map<String, TextureAtlasSprite> SIDE_CACHE = new HashMap<>();
    private static final Map<String, TextureAtlasSprite> CROSS_CACHE = new HashMap<>();

    public static void clearCache() {
        SIDE_CACHE.clear();
        CROSS_CACHE.clear();
    }

    public static TextureAtlasSprite getSideSprite(String logBlockId) {
        return SIDE_CACHE.computeIfAbsent(logBlockId, WoodTextureResolver::resolveSide);
    }

    public static TextureAtlasSprite getCrossSprite(String logBlockId) {
        return CROSS_CACHE.computeIfAbsent(logBlockId, WoodTextureResolver::resolveCross);
    }

    private static TextureAtlasSprite resolveSide(String woodTypeId) {
        ResourceLocation id = new ResourceLocation(woodTypeId);
        String ns = id.getNamespace();
        String wood = id.getPath(); // e.g. "oak"

        // Primary: <wood>_log side texture
        TextureAtlasSprite sprite = trySprite(ns, wood + "_log");
        if (!isMissing(sprite)) return sprite;

        // Fallback: stripped_<wood>_log
        sprite = trySprite(ns, "stripped_" + wood + "_log");
        if (!isMissing(sprite)) return sprite;

        // Last resort: bare wood name (some mods use non-standard naming)
        sprite = trySprite(ns, wood);
        if (!isMissing(sprite)) return sprite;

        return getMissing();
    }

    private static TextureAtlasSprite resolveCross(String woodTypeId) {
        ResourceLocation id = new ResourceLocation(woodTypeId);
        String ns = id.getNamespace();
        String wood = id.getPath(); // e.g. "oak"

        // Primary: <wood>_log_top
        TextureAtlasSprite sprite = trySprite(ns, wood + "_log_top");
        if (!isMissing(sprite)) return sprite;

        return resolveSide(woodTypeId);
    }

    private static TextureAtlasSprite trySprite(String namespace, String path) {
        ResourceLocation loc = new ResourceLocation(namespace, "block/" + path);
        return Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(loc);
    }

    private static TextureAtlasSprite getMissing() {
        return Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(new ResourceLocation("minecraft", "missingno"));
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        return sprite.contents().name()
                .equals(new ResourceLocation("minecraft", "missingno"));
    }
}
