package exp.CCnewmods.misanthrope_world.wet_sand.client;

import exp.CCnewmods.misanthrope_world.wet_sand.WettableFallingBlock;
import exp.CCnewmods.misanthrope_world.wet_sand.WettableSoilBlock;
import exp.CCnewmods.misanthrope_world.wet_sand.WetnessLevel;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;

/**
 * Client-side BlockColor handler for all wettable soil blocks.
 *
 * Applies a multiplicative darkening tint calibrated from real sand texture
 * samples at each wetness level. The tint is multiplied channel-by-channel
 * against the base texture colour, so it darkens and shifts the hue of any
 * block type (sand, dirt, gravel, modded soils) proportionally.
 *
 * Tint values are sampled from the example moist/wet/soaked sand textures
 * and extrapolated to saturated:
 *   MOIST:     #CDBA8A  — subtle warm darkening
 *   WET:       #BAA475  — noticeably darker
 *   SOAKED:    #A98F5C  — distinctly dark, earthy
 *   SATURATED: #977F4E  — heavy darkening, water-saturated appearance
 *
 * Covers both WettableFallingBlock (sand, gravel) and WettableSoilBlock
 * (dirt, clay, loam, modded soils).
 *
 * Block models must have "tintindex": 0 on all faces. The generated JSON
 * files produced alongside this class set that up for all 312 registered
 * wet variants.
 */
@OnlyIn(Dist.CLIENT)
public class WetnessTintHandler {

    // Multiplicative tint RGB values — derived from example texture samples.
    // Minecraft computes: result = (texture_channel * tint_channel) / 255
    // These values produce the correct darkened appearance across all soil types.
    private static final int TINT_MOIST = 0xCDBA8A;
    private static final int TINT_WET = 0xBAA475;
    private static final int TINT_SOAKED = 0xA98F5C;
    private static final int TINT_SATURATED = 0x977F4E;

    /**
     * Returns the tint colour for a given wetness level, or white (no tint)
     * for DRY. Used by both block types.
     */
    private static int tintFor(WetnessLevel level) {
        return switch (level) {
            case MOIST -> TINT_MOIST;
            case WET -> TINT_WET;
            case SOAKED -> TINT_SOAKED;
            case SATURATED -> TINT_SATURATED;
            case DRY -> 0xFFFFFF;
        };
    }

    /**
     * BlockColor applied to all WettableFallingBlock instances (sand, gravel).
     */
    public static final BlockColor FALLING_WETNESS_COLOR = (state, level, pos, tintIndex) -> {
        if (tintIndex != 0) return 0xFFFFFF;
        if (state.getBlock() instanceof WettableFallingBlock b) return tintFor(b.wetnessLevel);
        return 0xFFFFFF;
    };

    /**
     * BlockColor applied to all WettableSoilBlock instances (dirt, clay, loam, etc.).
     */
    public static final BlockColor SOIL_WETNESS_COLOR = (state, level, pos, tintIndex) -> {
        if (tintIndex != 0) return 0xFFFFFF;
        if (state.getBlock() instanceof WettableSoilBlock b) return tintFor(b.wetnessLevel);
        return 0xFFFFFF;
    };

    /**
     * Registers block colour handlers for every wettable block in ForgeRegistries.
     * Called on the mod event bus during RegisterColorHandlersEvent.Block.
     */
    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValues().forEach(block -> {
            if (block instanceof WettableFallingBlock) {
                event.register(FALLING_WETNESS_COLOR, block);
            } else if (block instanceof WettableSoilBlock) {
                event.register(SOIL_WETNESS_COLOR, block);
            }
        });
    }
}
