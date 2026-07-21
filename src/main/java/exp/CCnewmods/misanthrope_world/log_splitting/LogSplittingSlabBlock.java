package exp.CCnewmods.misanthrope_world.log_splitting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The active log-splitting slab.
 *
 * <p>Created when a player shift+right-clicks a bottom-half slab while holding
 * a log. It stores the original slab's {@link BlockState} and the log's block
 * ID in its {@link LogSplittingSlabBlockEntity}, which handles:
 * <ul>
 *   <li>Rendering the log standing upright on the slab via BEWLR.</li>
 *   <li>Tracking hit progress toward splitting.</li>
 * </ul>
 *
 * <p>The block itself uses {@link RenderShape#ENTITYBLOCK_ANIMATED} so the
 * BEWLR renders the visual slab (copied from the original slab's model) and
 * the log on top. If the block is broken, it drops the original slab item.
 */
public class LogSplittingSlabBlock extends BaseEntityBlock {

    public LogSplittingSlabBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    // -----------------------------------------------------------------------
    // Block entity
    // -----------------------------------------------------------------------

    @Override
    public float getDestroyProgress(BlockState state, net.minecraft.world.entity.player.Player player,
                                    net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return 0.002f;
    }
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LogSplittingSlabBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // No server tick needed — progress is event-driven (attack events)
        return null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    // -----------------------------------------------------------------------
    // Drop original slab when broken
    // -----------------------------------------------------------------------

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof LogSplittingSlabBlockEntity slab && !level.isClientSide) {
                // Drop the original slab
                ItemStack slabDrop = slab.getOriginalSlabDrop();
                if (!slabDrop.isEmpty()) {
                    ItemEntity ie = new ItemEntity(level,
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            slabDrop);
                    ie.setDefaultPickUpDelay();
                    level.addFreshEntity(ie);
                }
                // Note: the log is consumed on placement — it is not returned
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
