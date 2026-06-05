package exp.CCnewmods.misanthrope_world.physics.collapse;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Invisible structural block that acts as the anchor for a
 * {@link LatticeCollapseBlockEntity} during the collapse animation.
 *
 * <p>This block:
 * <ul>
 *   <li>Renders as {@link RenderShape#ENTITYBLOCK_ANIMATED} — the BER drives
 *       all geometry via the marching cubes renderer, nothing comes from the
 *       block model JSON.</li>
 *   <li>Has a full-cube collision shape at first, shrinking isn't done here
 *       (the visual shape is purely from the BER). Collision is removed at
 *       the end of the animation when the block is replaced.</li>
 *   <li>Drops nothing — any items from the original block are dropped by
 *       {@link exp.CCnewmods.misanthrope_core.physics.structural.FailureDispatcher}
 *       before placement.</li>
 *   <li>Hardness is effectively infinite — the block shouldn't be broken
 *       during animation.</li>
 * </ul>
 *
 * <p>Registration goes in {@link exp.CCnewmods.misanthrope_core.objects.Blocks}
 * and {@link exp.CCnewmods.misanthrope_core.objects.MisanthropeBlockEntityRegistry}
 * — see patch instructions in the accompanying text file.
 */
public class LatticeCollapseBlock extends BaseEntityBlock {

    public static final BlockBehaviour.Properties PROPS = BlockBehaviour.Properties.of()
            .strength(-1f, 3600000f)  // unbreakable during animation
            .noCollission()           // collision handled by remaining solid
            .noOcclusion()
            .isViewBlocking((s, l, p) -> false)
            .noLootTable();

    public LatticeCollapseBlock(Properties props) {
        super(props);
    }

    // ── Block entity ──────────────────────────────────────────────────────────

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LatticeCollapseBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return LatticeCollapseBlockEntity.serverTicker();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // ENTITYBLOCK_ANIMATED tells the chunk renderer to skip baked model
        // and let the BER handle all geometry.
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext ctx) {
        return Shapes.block(); // full cube for now; BER scales visually
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext ctx) {
        return Shapes.empty(); // no collision — let gravity handle entities
    }

    @Override
    public boolean isCollisionShapeFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0f; // full brightness — don't darken
    }
}