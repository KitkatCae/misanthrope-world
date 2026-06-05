package exp.CCnewmods.misanthrope_world.wet_sand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.List;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Base class for all wet soil variants (moist/wet/soaked/saturated).
 * <p>
 * Each instance represents ONE specific WetnessLevel for ONE specific soil type.
 * E.g. there will be a WettableFallingBlock(MOIST) for moist_sand,
 * a WettableFallingBlock(WET) for wet_sand, etc.
 * <p>
 * Behaviour:
 * - Extends FallingBlock so gravity still applies (sand falls).
 * - On randomTick: propagates wetness to adjacent dry wettable blocks, or
 * re-evaluates own wetness level from the BFS distance to nearest water.
 * - On neighborChanged: schedules a re-evaluation tick immediately.
 * - Drops the DRY block on mining (wet sand dries in your inventory).
 * - Counts as a moisture source for farmland (via FarmlandBlockMixin).
 * - Allows sugar cane placement (via SugarCaneBlockMixin + tag).
 * <p>
 * The visual darkening tint is handled client-side by WetnessTintHandler via
 * BlockColors, using the WetnessLevel's tintARGB value.
 */
public class WettableFallingBlock extends FallingBlock {

    /**
     * Which wetness level this block instance represents.
     */
    public final WetnessLevel wetnessLevel;

    public WettableFallingBlock(WetnessLevel level, Properties properties) {
        super(properties);
        this.wetnessLevel = level;
    }

    // -------------------------------------------------------------------------
    // Ticking — propagation and re-evaluation
    // -------------------------------------------------------------------------

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        // All wet variants tick randomly so they can spread wetness and
        // periodically re-validate their own level.
        return true;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // 1. Spread wetness outward to adjacent dry wettable blocks
        spreadWetnessToNeighbors(state, level, pos);

        // 2. Re-evaluate our own wetness level via BFS from water
        reEvaluateWetness(state, level, pos);
    }

    /**
     * Spread wetness to horizontally adjacent dry wettable blocks that are at
     * the correct distance to receive this level's dryer neighbour.
     * E.g. a SATURATED block (distance 1) spreads SOAKED (distance 2) to its
     * neighbours; a SOAKED block spreads WET, etc.
     */
    private void spreadWetnessToNeighbors(BlockState state, ServerLevel level, BlockPos pos) {
        WetnessLevel spreadLevel = wetnessLevel.dryer();
        if (spreadLevel == WetnessLevel.DRY) return; // MOIST blocks don't spread further

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);
            Block neighborBlock = neighborState.getBlock();

            // Only spread to dry wettable blocks, not to already-wet ones
            // (those manage themselves via their own re-evaluation tick)
            if (WetSandRegistry.INSTANCE.isDryWettable(neighborBlock)) {
                WetSandRegistry.INSTANCE.getWetVariant(neighborBlock, spreadLevel)
                        .ifPresent(wetVariant -> {
                            level.setBlock(neighborPos, wetVariant.defaultBlockState(),
                                    Block.UPDATE_ALL);
                        });
            }
        }
    }

    /**
     * BFS re-evaluation: find the closest water source within MAX_DISTANCE
     * blocks and update this block to the correct wetness level, or revert to
     * dry if no water is found.
     */
    private void reEvaluateWetness(BlockState state, ServerLevel level, BlockPos pos) {
        int distance = WetnessGraph.findDistanceToWater(level, pos);
        WetnessLevel correctLevel = WetnessLevel.forDistance(distance);

        if (correctLevel == wetnessLevel) return; // already correct, nothing to do

        // Look up the dry base block for this wet variant
        WetSandRegistry.INSTANCE.getDryBlock(this)
                .flatMap(dryBlock -> {
                    if (correctLevel == WetnessLevel.DRY) {
                        return java.util.Optional.of(dryBlock);
                    }
                    return WetSandRegistry.INSTANCE.getWetVariant(dryBlock, correctLevel);
                })
                .ifPresent(targetBlock -> {
                    level.setBlock(pos, targetBlock.defaultBlockState(), Block.UPDATE_ALL);
                });
    }

    // -------------------------------------------------------------------------
    // Neighbor updates — immediate re-evaluation on water placement/removal
    // -------------------------------------------------------------------------

    @Override
    public BlockState updateShape(BlockState state,
                                  Direction direction,
                                  BlockState neighborState,
                                  LevelAccessor level,
                                  BlockPos pos,
                                  BlockPos neighborPos) {
        // Schedule a random tick immediately so we re-evaluate soon after a
        // neighbour water block appears or disappears.
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.scheduleTick(pos, this, 2);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    // -------------------------------------------------------------------------
    // Water/moisture queries (used by FarmlandBlockMixin)
    // -------------------------------------------------------------------------

    /**
     * Returns true — this block is considered a moisture source for adjacent
     * farmland, just like water. Called from FarmlandBlockMixin.
     */
    public boolean isMoistureSource() {
        return true;
    }

    /**
     * Returns true if this block should count as "water adjacent" for crops
     * like sugar cane. Used by SugarCaneBlockMixin and the block tag.
     * Only WET, SOAKED, SATURATED count — MOIST is too dry.
     */
    public boolean allowsSugarCane() {
        return wetnessLevel == WetnessLevel.WET
                || wetnessLevel == WetnessLevel.SOAKED
                || wetnessLevel == WetnessLevel.SATURATED;
    }

    // -------------------------------------------------------------------------
    // Loot / drops — wet sand drops its dry equivalent
    // -------------------------------------------------------------------------

    /**
     * Override the FallingBlock item drop to return the dry block's item.
     * This means mining wet sand gives vanilla sand, not a wet_sand item.
     * (Wet sand items could be added later if desired, but for now it's simpler.)
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        Block dryBlock = WetSandRegistry.INSTANCE.getDryBlock(this).orElse(this);
        return List.of(new ItemStack(dryBlock));
    }
}
