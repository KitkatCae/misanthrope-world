package exp.CCnewmods.misanthrope_world.wet_sand;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.List;

/**
 * Non-falling wet soil variant — used for dirt, clay, loam, silt, peat etc.
 * that do NOT have gravity. Behaviour is identical to WettableFallingBlock
 * except this extends Block instead of FallingBlock.
 *
 * Both classes share the same wetness propagation logic, moisture-source
 * behaviour, and sugar-cane support; the only difference is the superclass.
 */
public class WettableSoilBlock extends Block {

    public final WetnessLevel wetnessLevel;

    public WettableSoilBlock(WetnessLevel level, Properties properties) {
        super(properties);
        this.wetnessLevel = level;
    }

    // -------------------------------------------------------------------------
    // Ticking
    // -------------------------------------------------------------------------

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        spreadWetnessToNeighbors(state, level, pos);
        reEvaluateWetness(state, level, pos);
    }

    private void spreadWetnessToNeighbors(BlockState state, ServerLevel level, BlockPos pos) {
        WetnessLevel spreadLevel = wetnessLevel.dryer();
        if (spreadLevel == WetnessLevel.DRY) return;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(dir);
            Block neighborBlock = level.getBlockState(neighborPos).getBlock();

            if (WetSandRegistry.INSTANCE.isDryWettable(neighborBlock)) {
                WetSandRegistry.INSTANCE.getWetVariant(neighborBlock, spreadLevel)
                        .ifPresent(wetVariant ->
                                level.setBlock(neighborPos, wetVariant.defaultBlockState(),
                                        Block.UPDATE_ALL));
            }
        }
    }

    private void reEvaluateWetness(BlockState state, ServerLevel level, BlockPos pos) {
        int distance = WetnessGraph.findDistanceToWater(level, pos);
        WetnessLevel correctLevel = WetnessLevel.forDistance(distance);

        if (correctLevel == wetnessLevel) return;

        WetSandRegistry.INSTANCE.getDryBlock(this)
                .flatMap(dryBlock -> {
                    if (correctLevel == WetnessLevel.DRY) return java.util.Optional.of(dryBlock);
                    return WetSandRegistry.INSTANCE.getWetVariant(dryBlock, correctLevel);
                })
                .ifPresent(targetBlock ->
                        level.setBlock(pos, targetBlock.defaultBlockState(), Block.UPDATE_ALL));
    }

    // -------------------------------------------------------------------------
    // Neighbor update
    // -------------------------------------------------------------------------

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.scheduleTick(pos, this, 2);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    // -------------------------------------------------------------------------
    // Moisture / sugar cane support
    // -------------------------------------------------------------------------

    public boolean isMoistureSource() {
        return true;
    }

    public boolean allowsSugarCane() {
        return wetnessLevel == WetnessLevel.WET
                || wetnessLevel == WetnessLevel.SOAKED
                || wetnessLevel == WetnessLevel.SATURATED;
    }

    // -------------------------------------------------------------------------
    // Loot — drops the dry block
    // -------------------------------------------------------------------------

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        Block dryBlock = WetSandRegistry.INSTANCE.getDryBlock(this).orElse(this);
        return List.of(new ItemStack(dryBlock));
    }
}
