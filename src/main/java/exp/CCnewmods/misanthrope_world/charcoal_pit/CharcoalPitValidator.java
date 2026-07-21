package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-block validation for the Charcoal Pit.
 *
 * <h3>Seal check</h3>
 * Every face of a log pile must be touching another log pile, gravel (floor
 * only), or a dirt-family block. Air and fire always break the seal.
 * The generic solid-block fallback uses a safe non-null call.
 *
 * <h3>Floor check</h3>
 * The gravel floor rule requires that somewhere below the lit log pile (walking
 * downward through a column of log piles) there is a gravel block. This
 * handles multi-layer piles where the ignited block may not be the bottom one.
 */
public final class CharcoalPitValidator {

    private CharcoalPitValidator() {
    }

    // -----------------------------------------------------------------------
    // Per-block seal check (used every VALIDATE_INTERVAL ticks)
    // -----------------------------------------------------------------------

    /**
     * Returns true if all 6 faces of {@code pos} are sealed — i.e. no
     * adjacent block is air or fire.
     */
    public static boolean isPosSealed(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!isSealingBlock(level, neighborState, neighbor, dir)) return false;
        }
        return true;
    }

    /**
     * Returns true if {@code state} at {@code neighborPos} counts as sealing
     * a log pile face coming from {@code faceDir}.
     *
     * @param level       the world (needed for safe solid-render check)
     * @param state       the neighbor block state
     * @param neighborPos the neighbor position
     * @param faceDir     direction FROM the log pile TOWARD the neighbor
     */
    public static boolean isSealingBlock(Level level, BlockState state,
                                         BlockPos neighborPos, Direction faceDir) {
        // Another log pile always seals
        if (state.getBlock() instanceof LogPileBlock) return true;

        // Air or fire always breaks the seal
        if (state.isAir()) return false;
        if (state.getBlock() instanceof BaseFireBlock) return false;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return false;

        // Bottom face: must be gravel (the floor)
        if (faceDir == Direction.DOWN) {
            return state.is(Blocks.GRAVEL);
        }

        // All other faces: #minecraft:dirt, gravel, sand, or any solid non-flammable block
        if (state.is(BlockTags.DIRT)) return true;
        if (state.is(Blocks.GRAVEL)) return true;
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return true;

        // Generic fallback: solid render AND not ignitable
        // Pass real level + pos to avoid NPE inside isSolidRender
        return state.isSolidRender(level, neighborPos) && !state.ignitedByLava();
    }

    // -----------------------------------------------------------------------
    // Floor walk (used during ignition only)
    // -----------------------------------------------------------------------

    /**
     * Walks downward from {@code pos} through any column of log piles and
     * returns true if the first non-log-pile block at the bottom is gravel.
     * <p>
     * Handles multi-layer piles where the player lights a log pile that isn't
     * on the bottom row. Maximum depth of 32 to avoid loops.
     */
    public static boolean hasGravelFloor(Level level, BlockPos pos) {
        BlockPos current = pos.below();
        int depth = 0;
        while (depth < 32) {
            BlockState state = level.getBlockState(current);
            if (state.getBlock() instanceof LogPileBlock) {
                current = current.below();
                depth++;
            } else {
                return state.is(Blocks.GRAVEL);
            }
        }
        return false;
    }
}
