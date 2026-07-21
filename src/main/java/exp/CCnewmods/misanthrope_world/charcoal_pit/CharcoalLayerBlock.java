package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Charcoal Layer — produced when a Charcoal Pit completes.
 *
 * <p>Layers 1–8, 2px per layer (like snow). Falls with gravity.
 * Breaking drops exactly {@code LAYERS} charcoal, no loot table involved
 * — the drop count is computed directly in {@link #getDrops}.
 * Layers stack when placed on top of existing charcoal layers.
 */
public class CharcoalLayerBlock extends FallingBlock implements SimpleWaterloggedBlock {

    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;

    private static final VoxelShape[] SHAPES = new VoxelShape[9];

    static {
        for (int i = 1; i <= 8; i++)
            SHAPES[i] = Block.box(0, 0, 0, 16, i * 2, 16);
    }

    public CharcoalLayerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(LAYERS, 1)
                .setValue(BlockStateProperties.WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, BlockStateProperties.WATERLOGGED);
    }

    // -----------------------------------------------------------------------
    // Shape
    // -----------------------------------------------------------------------

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext ctx) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext ctx) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public boolean isCollisionShapeFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(LAYERS) == 8;
    }

    // -----------------------------------------------------------------------
    // Placement — stack on existing layers
    // -----------------------------------------------------------------------

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState existing = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (existing.is(this)) {
            int current = existing.getValue(LAYERS);
            if (current >= 8) return null;
            return existing.setValue(LAYERS, current + 1);
        }
        FluidState fluid = ctx.getLevel().getFluidState(ctx.getClickedPos());
        return defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext ctx) {
        return ctx.getItemInHand().is(asItem()) && state.getValue(LAYERS) < 8;
    }

    // -----------------------------------------------------------------------
    // Survival
    // -----------------------------------------------------------------------

    @Override
    public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level,
                              BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState facingState,
                                  LevelAccessor level, BlockPos pos, BlockPos facingPos) {
        if (state.getValue(BlockStateProperties.WATERLOGGED))
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        if (dir == Direction.DOWN && !canSurvive(state, level, pos))
            return Blocks.AIR.defaultBlockState();
        return state;
    }

    // -----------------------------------------------------------------------
    // Gravity
    // -----------------------------------------------------------------------

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos,
                        BlockState oldState, boolean isMoving) {
        level.scheduleTick(pos, this, getDelayAfterPlace());
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                Block block, BlockPos fromPos, boolean isMoving) {
        level.scheduleTick(pos, this, getDelayAfterPlace());
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!canSurvive(state, level, pos))
            FallingBlockEntity.fall(level, pos, state);
    }

    // -----------------------------------------------------------------------
    // Waterlogging
    // -----------------------------------------------------------------------

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED)
                ? Fluids.WATER.getSource(false)
                : super.getFluidState(state);
    }

    // -----------------------------------------------------------------------
    // Drops — 1 charcoal per layer, computed here (no loot table needed)
    // -----------------------------------------------------------------------

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        int layers = state.getValue(LAYERS);
        return List.of(new ItemStack(Items.CHARCOAL, layers));
    }

    // -----------------------------------------------------------------------
    // Falling block dust color
    // -----------------------------------------------------------------------

    @Override
    public int getDustColor(BlockState state, BlockGetter level, BlockPos pos) {
        return 0x2A2A2A;
    }
}
