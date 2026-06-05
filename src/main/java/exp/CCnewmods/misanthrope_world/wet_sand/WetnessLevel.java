package exp.CCnewmods.misanthrope_world.wet_sand;

import net.minecraft.util.StringRepresentable;

/**
 * Four wetness levels for wettable soil blocks, plus DRY (no wetness).
 * <p>
 * Ordered from least to most wet:
 * DRY → MOIST (distance 4) → WET (distance 3) → SOAKED (distance 2) → SATURATED (distance 1, adjacent to source)
 * <p>
 * The numeric "distance" corresponds to how many blocks away from a water
 * source a soil block is. Distance 0 = inside water = not applicable.
 * <p>
 * These names are used as the block ID suffix, e.g.:
 * misanthrope_core:moist_sand, misanthrope_core:wet_sand, etc.
 */
public enum WetnessLevel implements StringRepresentable {

    DRY("dry", 0, 0x00000000),   // not used as a registered block, represents vanilla block
    MOIST("moist", 4, 0x18000000),   // farthest from water, very slight darkening
    WET("wet", 3, 0x30000000),   // mid distance
    SOAKED("soaked", 2, 0x48000000),   // close to water
    SATURATED("saturated", 1, 0x60000000);   // directly adjacent to water, darkest

    /**
     * Block ID prefix used in registration, e.g. "moist" → "moist_sand".
     */
    public final String prefix;

    /**
     * BFS distance from water source that produces this level.
     * A block at exactly this distance gets this wetness level.
     * Distance 1 = directly adjacent to water → SATURATED.
     * Distance 4 = four blocks away → MOIST.
     * Distance 5+ = DRY (no wet block placed).
     */
    public final int waterDistance;

    /**
     * ARGB tint overlay applied by the BlockColor handler on the client.
     * Alpha controls how strongly the dark tint blends over the base texture.
     * 0x00 = no tint (dry), 0x60 = heavy dark tint (saturated).
     */
    public final int tintARGB;

    WetnessLevel(String prefix, int waterDistance, int tintARGB) {
        this.prefix = prefix;
        this.waterDistance = waterDistance;
        this.tintARGB = tintARGB;
    }

    /**
     * Returns the next drier level (one step toward DRY), or DRY if already
     * at the driest wet level (MOIST).
     */
    public WetnessLevel dryer() {
        return switch (this) {
            case SATURATED -> SOAKED;
            case SOAKED -> WET;
            case WET -> MOIST;
            case MOIST -> DRY;
            case DRY -> DRY;
        };
    }

    /**
     * Returns the next wetter level (one step toward SATURATED), or
     * SATURATED if already at the wettest level.
     */
    public WetnessLevel wetter() {
        return switch (this) {
            case DRY -> MOIST;
            case MOIST -> WET;
            case WET -> SOAKED;
            case SOAKED -> SATURATED;
            case SATURATED -> SATURATED;
        };
    }

    /**
     * Given a BFS distance from a water source, return the appropriate
     * WetnessLevel. Distance 0 means inside water (not applicable).
     * Distance > 4 means out of range → DRY.
     */
    public static WetnessLevel forDistance(int distance) {
        return switch (distance) {
            case 1 -> SATURATED;
            case 2 -> SOAKED;
            case 3 -> WET;
            case 4 -> MOIST;
            default -> DRY;
        };
    }

    /**
     * Maximum distance at which wetness propagates. Blocks further than this stay DRY.
     */
    public static final int MAX_DISTANCE = 4;

    @Override
    public String getSerializedName() {
        return prefix;
    }
}
