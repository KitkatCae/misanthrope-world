package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A cut wood piece — 1/16th of a log, produced by the Log Splitting mechanic.
 *
 * <h3>NBT</h3>
 * Stores the wood type as {@code WoodType: "namespace:wood_name"} — e.g.
 * {@code "minecraft:oak"}, {@code "biomesoplenty:fir"}. The renderer uses
 * this to look up the stripped log and log top textures.
 *
 * <h3>Placement</h3>
 * Right-clicking on the ground creates a new {@link LogPileBlock} with this
 * piece in slot 0. Right-clicking on an existing log pile adds this piece to
 * the next available slot (up to 16). If the pile is full, does nothing.
 *
 * <h3>Retrieval</h3>
 * Shift+right-clicking on a log pile removes the most recently added piece
 * and gives it back to the player (handled in {@link LogPileInteractionHandler}).
 */
public class CutWoodItem extends Item {

    private static final String WOOD_TYPE_TAG = "WoodType";

    public CutWoodItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    // -----------------------------------------------------------------------
    // NBT helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the wood type ID stored in this stack, or null if absent.
     */
    @Nullable
    public static String getWoodTypeId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(WOOD_TYPE_TAG)) return null;
        return tag.getString(WOOD_TYPE_TAG);
    }

    /**
     * Creates an ItemStack for the given wood type ID.
     */
    public static ItemStack create(Item cutWoodItem, String woodTypeId) {
        ItemStack stack = new ItemStack(cutWoodItem);
        stack.getOrCreateTag().putString(WOOD_TYPE_TAG, woodTypeId);
        return stack;
    }

    // -----------------------------------------------------------------------
    // Right-click placement
    // -----------------------------------------------------------------------

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        net.minecraft.core.BlockPos clickedPos = ctx.getClickedPos();
        net.minecraft.core.BlockPos placePos = clickedPos.relative(ctx.getClickedFace());
        net.minecraft.world.entity.player.Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();

        if (player == null) return InteractionResult.PASS;

        String woodType = getWoodTypeId(stack);
        if (woodType == null) return InteractionResult.FAIL;

        // Shift+right-click on existing log pile → handled elsewhere (LogPileInteractionHandler)
        // Plain right-click on existing log pile → add to pile
        BlockState clickedState = level.getBlockState(clickedPos);
        if (clickedState.getBlock() instanceof LogPileBlock) {
            if (player.isShiftKeyDown()) return InteractionResult.PASS; // let handler deal with removal
            return addToPile(level, clickedPos, woodType, stack, player);
        }

        // Right-click on ground → place new pile
        BlockState atPlace = level.getBlockState(placePos);
        if (!atPlace.isAir() && !atPlace.canBeReplaced()) return InteractionResult.FAIL;

        if (!level.isClientSide) {
            level.setBlock(placePos,
                    CharcoalPitRegistration.LOG_PILE.get().defaultBlockState(), 3);
            LogPileBlockEntity be = getLogPileBE(level, placePos);
            if (be != null) {
                be.addLog(woodType);
                syncBE(level, placePos, be);
            }
            if (!player.isCreative()) stack.shrink(1);
            level.playSound(null, placePos,
                    SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 0.9f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private InteractionResult addToPile(Level level, net.minecraft.core.BlockPos pos,
                                        String woodType, ItemStack stack,
                                        net.minecraft.world.entity.player.Player player) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        LogPileBlockEntity be = getLogPileBE(level, pos);
        if (be == null || be.isFull()) return InteractionResult.FAIL;

        be.addLog(woodType);
        syncBE(level, pos, be);
        if (!player.isCreative()) stack.shrink(1);
        level.playSound(null, pos,
                SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1.1f);
        return InteractionResult.CONSUME;
    }

    // -----------------------------------------------------------------------
    // Tooltip
    // -----------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> lines, TooltipFlag flag) {
        String woodType = getWoodTypeId(stack);
        if (woodType != null) {
            // Show "Oak", "Fir", etc. by prettifying the wood type path
            String[] parts = woodType.split(":");
            String name = parts.length > 1 ? parts[1] : woodType;
            name = name.replace('_', ' ');
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            lines.add(Component.literal(name).withStyle(ChatFormatting.GRAY));
        }
    }

    // -----------------------------------------------------------------------
    // BEWLR
    // -----------------------------------------------------------------------

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(
            java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(CutWoodItemRenderer.CLIENT_EXTENSIONS);
    }

    // -----------------------------------------------------------------------
    // Stacking — only stacks with same wood type
    // -----------------------------------------------------------------------

    @Override
    public boolean canBeDepleted() {
        return false;
    }

    // Two cut wood items only merge if they have the same WoodType tag
    // This is handled automatically by ItemStack.isSameItemSameTags

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @Nullable
    private static LogPileBlockEntity getLogPileBE(Level level, net.minecraft.core.BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof LogPileBlockEntity ? (LogPileBlockEntity) be : null;
    }

    private static void syncBE(Level level, net.minecraft.core.BlockPos pos,
                               LogPileBlockEntity be) {
        be.setChanged();
        if (level instanceof ServerLevel sl) {
            sl.sendBlockUpdated(pos, be.getBlockState(), be.getBlockState(), 3);
        }
    }
}
