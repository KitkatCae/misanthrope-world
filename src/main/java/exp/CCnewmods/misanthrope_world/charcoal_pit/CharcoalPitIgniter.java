package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Detects fire being placed next to a {@link LogPileBlock} and starts ignition.
 *
 * <h3>Ignition rules</h3>
 * <ul>
 *   <li>A fire block must be placed adjacent to a log pile.</li>
 *   <li>Walking downward through the pile, there must be a gravel block
 *       at the bottom (the floor requirement).</li>
 *   <li>All faces of the ignited log pile must be sealed, with one exception:
 *       exactly one face may be open — the face the fire is on. This gives
 *       the player a {@value LogPileBurnBlockEntity#BURN_TICKS}-tick grace
 *       window to cover that face back up.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CharcoalPitIgniter {

    private CharcoalPitIgniter() {
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (level.isClientSide) return;

        BlockState placed = event.getPlacedBlock();
        if (!(placed.getBlock() instanceof BaseFireBlock)) return;

        BlockPos firePos = event.getPos();

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = firePos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);

            if (neighborState.getBlock() instanceof LogPileBlock
                    && !neighborState.getValue(LogPileBlock.BURNING)) {
                tryIgnite(level, neighbor, neighborState);
            }
        }
    }

    /**
     * Attempts to ignite the log pile at {@code pos}.
     * <p>
     * Requires gravel below (walking down through log piles) and at most one
     * unsealed face (the fire face). If those conditions hold, sets BURNING=true
     * and notifies neighbors so the chain reaction can spread.
     */
    private static void tryIgnite(Level level, BlockPos pos, BlockState state) {
        // 1. Gravel floor check — walk down through log pile column
        if (!CharcoalPitValidator.hasGravelFloor(level, pos)) return;

        // 2. Count unsealed faces — allow exactly one (the fire face)
        int unsealedCount = 0;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!CharcoalPitValidator.isSealingBlock(level, neighborState, neighbor, dir)) {
                unsealedCount++;
            }
        }

        if (unsealedCount > 1) return;

        // Ignite this block and notify neighbors for chain reaction
        level.setBlock(pos, state.setValue(LogPileBlock.BURNING, true), 3);
        level.updateNeighborsAt(pos, state.getBlock());
    }
}
