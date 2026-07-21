package exp.CCnewmods.misanthrope_world.log_splitting;

import exp.CCnewmods.misanthrope_world.charcoal_pit.CharcoalPitRegistration;
import exp.CCnewmods.misanthrope_world.charcoal_pit.CutWoodItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles all player interactions for the Log Splitting mechanic.
 *
 * <h3>Setup — shift+right-click slab with log</h3>
 * {@link #onRightClickBlock} fires when a player shift+right-clicks a
 * bottom-half slab while holding any block in {@code #minecraft:logs}.
 * The slab is converted to a {@link LogSplittingSlabBlock}, the log is
 * consumed from the player's hand, and the required hit count is written
 * to the block entity.
 *
 * <h3>Splitting — left-click with axe</h3>
 * {@link #onLeftClickBlock} fires when a player left-clicks the
 * {@link LogSplittingSlabBlock} with an axe. Each click:
 * <ul>
 *   <li>Adds one hit to the block entity's progress counter.</li>
 *   <li>Damages the axe by 1 durability.</li>
 *   <li>Plays a chopping sound and spawns wood particles.</li>
 *   <li>On completion: drops 16 {@link CutWoodItem} of the log's type,
 *       replaces the {@link LogSplittingSlabBlock} with the original slab.</li>
 * </ul>
 *
 * <h3>Hit count formula</h3>
 * {@code hitsRequired = max(4, 32 - (miningLevel * 4) - (int)(miningSpeed - 1.5f) * 2)}
 * <ul>
 *   <li>Wood/gold axe (level 0, speed ~2): ~28 hits</li>
 *   <li>Stone axe (level 1, speed ~4): ~20 hits</li>
 *   <li>Iron axe (level 2, speed ~6): ~14 hits</li>
 *   <li>Diamond axe (level 3, speed ~8): ~8 hits</li>
 *   <li>Netherite axe (level 4, speed ~9): ~5 hits</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LogSplittingHandler {

    private LogSplittingHandler() {
    }

    // -----------------------------------------------------------------------
    // Setup: shift+right-click slab with log
    // -----------------------------------------------------------------------

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) return;

        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) return;

        BlockPos pos = event.getPos();
        BlockState clickedState = level.getBlockState(pos);

        // Must be a bottom-half slab (not top, not double)
        if (!(clickedState.getBlock() instanceof SlabBlock)) return;
        if (clickedState.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) return;

        // Player must be holding a log
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return;

        BlockState heldBlockState = getBlockStateFromItem(held);
        if (heldBlockState == null) return;
        if (!heldBlockState.is(BlockTags.LOGS)) return;

        // Get the log's full block ID for the renderer, and strip it down to a
        // wood-type ID for the CutWoodItem NBT tag.
        // e.g. "minecraft:oak_log" (renderer) -> "minecraft:oak" (item tag)
        String rawBlockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(heldBlockState.getBlock()).toString();
        String logBlockId = toWoodTypeId(rawBlockId);

        // Compute hits required based on held axe (if any) or default
        // At this point the player is holding a log, so we use default
        int hitsRequired = 32; // will be recalculated on first axe hit

        // Convert the slab
        level.setBlock(pos, LogSplittingRegistration.LOG_SPLITTING_SLAB.get()
                .defaultBlockState(), 3);

        LogSplittingSlabBlockEntity be = getSlabBE(level, pos);
        if (be == null) {
            // Failed to create BE — revert
            level.setBlock(pos, clickedState, 3);
            return;
        }

        be.setOriginalSlabState(clickedState);
        be.setRawLogBlockId(rawBlockId);
        be.setLogBlockId(logBlockId);
        be.setHitsRequired(hitsRequired);
        syncBE(level, pos, be);

        // Consume one log from the player's hand
        if (!player.isCreative()) held.shrink(1);

        level.playSound(null, pos,
                SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 0.8f);

        event.setCanceled(true);
    }

    // -----------------------------------------------------------------------
    // Splitting: left-click with axe
    // -----------------------------------------------------------------------

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) return;

        BlockPos pos = event.getPos();
        if (!(level.getBlockState(pos).getBlock() instanceof LogSplittingSlabBlock)) return;

        Player player = event.getEntity();
        ItemStack axe = player.getMainHandItem();
        if (!(axe.getItem() instanceof AxeItem axeItem)) return;

        LogSplittingSlabBlockEntity be = getSlabBE(level, pos);
        if (be == null) return;

        // Update hits required based on this axe (recalculate if first hit)
        if (be.getHitProgress() == 0) {
            be.setHitsRequired(calculateHits(axeItem));
        }

        // Damage the axe
        axe.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(
                player.getUsedItemHand()));

        // Spawn wood particles using the raw block ID stored at placement time
        if (level instanceof ServerLevel sl) {
            BlockState logVisual = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getOptional(new net.minecraft.resources.ResourceLocation(be.getRawLogBlockId()))
                    .map(net.minecraft.world.level.block.Block::defaultBlockState)
                    .orElseGet(() -> net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, logVisual),
                    pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                    6, 0.2, 0.1, 0.2, 0.05);
        }

        // Play chop sound — pitch rises as progress increases
        float pitch = 0.8f + be.getProgressFraction() * 0.4f;
        level.playSound(null, pos,
                SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 1f, pitch);

        // Add hit and check completion
        boolean done = be.addHit();
        syncBE(level, pos, be);

        if (done) {
            complete(level, pos, be);
        }

        // Cancel so the block doesn't start taking mining damage
        event.setCanceled(true);
    }

    // -----------------------------------------------------------------------
    // Break guard — prevents normal mining from ever completing
    // -----------------------------------------------------------------------

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getState().getBlock() instanceof LogSplittingSlabBlock)) return;

        // Allow our own programmatic setBlock() call inside complete() to proceed.
        // complete() sets markCompleting() on the BE before calling setBlock(),
        // so we can distinguish that from a real player-mining attempt.
        BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
        if (be instanceof LogSplittingSlabBlockEntity slab && slab.isCompletingNow()) {
            return;
        }

        event.setCanceled(true);
    }

    // -----------------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------------

    private static void complete(Level level, BlockPos pos,
                                 LogSplittingSlabBlockEntity be) {
        // Snapshot BE data NOW — setBlock will invalidate the block entity
        String logBlockId = be.getLogBlockId();
        BlockState originalSlab = be.getOriginalSlabState();

        // Play completion sound
        level.playSound(null, pos,
                SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1f, 0.7f);

        // Signal that this removal is intentional so onBlockBreak doesn't cancel it
        be.markCompleting();

        // Restore the original slab FIRST (invalidates BE — that's fine, we snapshotted)
        if (originalSlab != null) {
            level.setBlock(pos, originalSlab, 3);
        } else {
            level.removeBlock(pos, false);
        }

        // Drop 16 cut wood items AFTER the block is restored so spawn pos is clear
        if (level instanceof ServerLevel sl && !logBlockId.isEmpty()) {
            for (int i = 0; i < 16; i++) {
                ItemStack cutWood = CutWoodItem.create(
                        CharcoalPitRegistration.CUT_WOOD_ITEM.get(),
                        logBlockId);
                ItemEntity ie = new ItemEntity(level,
                        pos.getX() + 0.5,
                        pos.getY() + 0.75,
                        pos.getZ() + 0.5,
                        cutWood);
                ie.setDefaultPickUpDelay();
                ie.setDeltaMovement(
                        (sl.getRandom().nextDouble() - 0.5) * 0.3,
                        0.15 + sl.getRandom().nextDouble() * 0.2,
                        (sl.getRandom().nextDouble() - 0.5) * 0.3);
                level.addFreshEntity(ie);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Hit count formula
    // -----------------------------------------------------------------------

    /**
     * Calculates required hits based on the axe's tier.
     * Uses Forge's tool attributes via the item's destroy speed on wood.
     */
    private static int calculateHits(AxeItem axe) {
        // Get mining speed against wood — create a dummy oak log state to test
        BlockState oakLog = net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        float speed = axe.getDestroySpeed(new ItemStack(axe), oakLog);

        // Mining speed for axes: wood/gold ~2, stone ~4, iron ~6, diamond ~8, netherite ~9
        // Scale: 32 hits at speed 1, reduce by 2 for each point of speed above 1
        int hits = Math.max(4, 32 - (int) ((speed - 1.5f) * 3.5f));
        return hits;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static LogSplittingSlabBlockEntity getSlabBE(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof LogSplittingSlabBlockEntity s ? s : null;
    }

    private static void syncBE(Level level, BlockPos pos, LogSplittingSlabBlockEntity be) {
        be.setChanged();
        if (level instanceof ServerLevel sl) {
            var packet = be.getUpdatePacket();
            if (packet != null) {
                sl.getChunkSource().chunkMap
                        .getPlayers(new net.minecraft.world.level.ChunkPos(pos), false)
                        .forEach(p -> p.connection.send(packet));
            }
        }
    }

    /**
     * Converts a full log block ID to a wood-type ID by stripping the
     * {@code _log} or {@code _wood} suffix from the path.
     * e.g. {@code "minecraft:oak_log"} → {@code "minecraft:oak"},
     * {@code "biomesoplenty:fir_log"} → {@code "biomesoplenty:fir"}.
     * Falls back to the raw ID if the suffix isn't recognised.
     */
    private static String toWoodTypeId(String blockId) {
        int colon = blockId.indexOf(':');
        if (colon < 0) return blockId;
        String ns = blockId.substring(0, colon);
        String path = blockId.substring(colon + 1);
        if (path.endsWith("_log")) path = path.substring(0, path.length() - 4);
        else if (path.endsWith("_wood")) path = path.substring(0, path.length() - 5);
        return ns + ":" + path;
    }

    /**
     * Gets a BlockState from an ItemStack if it represents a placeable block.
     * Returns null if the item is not a block item or has no block state.
     */
    private static BlockState getBlockStateFromItem(ItemStack stack) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) return null;
        return bi.getBlock().defaultBlockState();
    }
}