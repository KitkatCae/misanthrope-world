package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles shift+right-click on a {@link LogPileBlock} to retrieve the most
 * recently added cut wood piece.
 *
 * <p>Also handles breaking an empty log pile — if the last piece is removed
 * and the pile is empty, the block is automatically removed.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LogPileInteractionHandler {

    private LogPileInteractionHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        Player player = event.getEntity();

        if (!player.isShiftKeyDown()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        if (!(level.getBlockState(pos).getBlock() instanceof LogPileBlock)) return;

        // Don't interfere if player is holding a cut wood item (they might be adding)
        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof CutWoodItem) return;

        if (level.isClientSide) {
            event.setCanceled(true);
            return;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof LogPileBlockEntity pile)) return;

        String woodType = pile.removeTopLog();
        if (woodType == null) return;

        // Give back the cut wood item
        ItemStack cutWood = CutWoodItem.create(
                CharcoalPitRegistration.CUT_WOOD_ITEM.get(), woodType);
        if (!player.getInventory().add(cutWood)) {
            player.drop(cutWood, false);
        }

        // If now empty, remove the block
        if (pile.isEmpty()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        } else {
            pile.setChanged();
            ((ServerLevel) level).sendBlockUpdated(pos,
                    level.getBlockState(pos), level.getBlockState(pos), 3);
        }

        level.playSound(null, pos,
                SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.7f, 1.2f);

        event.setCanceled(true);
    }
}
