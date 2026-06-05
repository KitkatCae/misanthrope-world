package exp.CCnewmods.misanthrope_world.wet_sand;

import net.minecraft.world.level.block.Block;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Binds a dry soil block to its four wet variant blocks (one per WetnessLevel
 * excluding DRY). Also provides quick lookups in both directions:
 * dry  → wet variant at a given level
 * wet  → dry block + WetnessLevel
 * <p>
 * Constructed by WetSandRegistry; never instantiated elsewhere.
 */
public class WettableSoilEntry {

    /**
     * The original vanilla/mod dry block, e.g. minecraft:sand.
     */
    public final Block dryBlock;

    /**
     * Map from WetnessLevel → the registered WettableFallingBlock for that level.
     * Only contains MOIST, WET, SOAKED, SATURATED — never DRY.
     */
    private final EnumMap<WetnessLevel, Block> wetVariants;

    WettableSoilEntry(Block dryBlock, EnumMap<WetnessLevel, Block> wetVariants) {
        this.dryBlock = dryBlock;
        this.wetVariants = wetVariants;
    }

    /**
     * Returns the wet-variant block for the given WetnessLevel, or empty if
     * DRY is passed (which has no wet block).
     */
    public Optional<Block> getWetVariant(WetnessLevel level) {
        if (level == WetnessLevel.DRY) return Optional.empty();
        return Optional.ofNullable(wetVariants.get(level));
    }

    /**
     * Given a wet-variant block, returns which WetnessLevel it represents,
     * or empty if the block is not part of this entry.
     */
    public Optional<WetnessLevel> getLevelOf(Block block) {
        for (Map.Entry<WetnessLevel, Block> e : wetVariants.entrySet()) {
            if (e.getValue() == block) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /**
     * True if the given block is any wet variant registered under this entry.
     */
    public boolean isWetVariant(Block block) {
        return wetVariants.containsValue(block);
    }

    /**
     * True if the given block is the dry source block of this entry.
     */
    public boolean isDryBlock(Block block) {
        return dryBlock == block;
    }
}
