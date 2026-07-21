package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;


/**
 * The Log Pile block — holds up to 16 cut wood pieces of any wood type.
 *
 * <p>Each log pile stores its contents in a {@link LogPileBlockEntity}.
 * The {@link LogPileRenderer} (BEWLR) reads the BE's slot data and renders
 * each of the 16 log elements with the correct wood's textures.
 *
 * <p>When {@code BURNING=true} the block entity also ticks the charcoal pit
 * burn logic via {@link LogPileBurnBlockEntity} — but wait, we've merged:
 * the single {@link LogPileBlockEntity} handles both storage and burning.
 */
public class LogPileBlock extends BaseEntityBlock {

    public static final BooleanProperty BURNING = BooleanProperty.create("burning");

    public LogPileBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BURNING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BURNING);
    }

    // -----------------------------------------------------------------------
    // Block entity — LogPileBlockEntity handles both storage AND burning
    // -----------------------------------------------------------------------

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LogPileBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // Only tick for burn logic, only on server, only when burning
        if (level.isClientSide || !state.getValue(BURNING)) return null;
        return createTickerHelper(type,
                CharcoalPitRegistration.LOG_PILE_BE.get(),
                LogPileBlockEntity::tickBurn);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // ENTITYBLOCK_ANIMATED disables the standard model — BEWLR handles rendering
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }


    // -----------------------------------------------------------------------
    // Chain ignition via neighborChanged
    // -----------------------------------------------------------------------

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                Block fromBlock, BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) return;
        if (state.getValue(BURNING)) return;

        BlockState from = level.getBlockState(fromPos);
        if (from.getBlock() instanceof LogPileBlock && from.getValue(BURNING)) {
            if (CharcoalPitValidator.isPosSealed(level, pos)) {
                level.setBlock(pos, state.setValue(BURNING, true), 3);
                level.updateNeighborsAt(pos, state.getBlock());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Fire source
    // -----------------------------------------------------------------------

    @Override
    public boolean isFireSource(BlockState state, LevelReader level,
                                BlockPos pos, Direction side) {
        return state.getValue(BURNING);
    }

    // -----------------------------------------------------------------------
    // Drop contents when broken
    // -----------------------------------------------------------------------

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            // Drop all stored cut wood pieces
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof LogPileBlockEntity pile && !level.isClientSide) {
                for (int i = 0; i < pile.getCount(); i++) {
                    String woodType = pile.getSlot(i);
                    if (!woodType.isEmpty()) {
                        net.minecraft.world.item.ItemStack stack =
                                CutWoodItem.create(
                                        CharcoalPitRegistration.CUT_WOOD_ITEM.get(),
                                        woodType);
                        net.minecraft.world.entity.item.ItemEntity ie =
                                new net.minecraft.world.entity.item.ItemEntity(
                                        level,
                                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                        stack);
                        ie.setDefaultPickUpDelay();
                        level.addFreshEntity(ie);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
