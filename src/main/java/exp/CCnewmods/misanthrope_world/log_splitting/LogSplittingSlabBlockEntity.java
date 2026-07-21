package exp.CCnewmods.misanthrope_world.log_splitting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Block entity for {@link LogSplittingSlabBlock}.
 *
 * <p>Stores:
 * <ul>
 *   <li>{@code originalSlabState} — the {@link BlockState} of the slab that
 *       was converted, used for rendering and for dropping the correct slab
 *       item on break.</li>
 *   <li>{@code rawLogBlockId} — the full registry ID of the log block,
 *       e.g. {@code "minecraft:oak_log"}, used by the renderer and particles
 *       to look up the block model and texture.</li>
 *   <li>{@code logBlockId} — the wood-type ID derived from the log block,
 *       e.g. {@code "minecraft:oak"}, stored in the {@link net.minecraft.world.item.ItemStack}
 *       NBT of the produced {@link exp.CCnewmods.misanthrope_world.charcoal_pit.CutWoodItem}
 *       and used for texture resolution.</li>
 *   <li>{@code hitProgress} — how many effective hits have been made toward
 *       completion. Persists across sessions.</li>
 *   <li>{@code hitsRequired} — computed on placement from the axe's stats.</li>
 * </ul>
 */
public class LogSplittingSlabBlockEntity extends BlockEntity {

    // The original slab's block state — used to render the slab face
    // and to produce the correct drop item
    private BlockState originalSlabState = null;

    // Full registry ID of the log block e.g. "minecraft:oak_log" — used by
    // the renderer and particle effects to look up the block model/texture.
    private String rawLogBlockId = "";

    // Wood-type ID e.g. "minecraft:oak" — stored in the CutWoodItem NBT tag.
    private String logBlockId = "";

    // Hit progress
    private int hitProgress = 0;
    private int hitsRequired = 32; // default, overwritten on placement

    // Set to true just before complete() calls setBlock() so onBlockBreak
    // can distinguish our programmatic removal from player mining.
    private boolean completingNow = false;

    public LogSplittingSlabBlockEntity(BlockPos pos, BlockState state) {
        super(LogSplittingRegistration.LOG_SPLITTING_SLAB_BE.get(), pos, state);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public BlockState getOriginalSlabState() {
        return originalSlabState;
    }

    public void setOriginalSlabState(BlockState state) {
        this.originalSlabState = state;
        setChanged();
    }

    public String getRawLogBlockId() {
        return rawLogBlockId;
    }

    public void setRawLogBlockId(String id) {
        this.rawLogBlockId = id;
        setChanged();
    }

    public String getLogBlockId() {
        return logBlockId;
    }

    public void setLogBlockId(String id) {
        this.logBlockId = id;
        setChanged();
    }

    public int getHitProgress() {
        return hitProgress;
    }

    public int getHitsRequired() {
        return hitsRequired;
    }

    public void setHitsRequired(int n) {
        this.hitsRequired = n;
        setChanged();
    }

    /**
     * Records one effective hit. Returns true if the log is now fully split.
     */
    public boolean addHit() {
        hitProgress++;
        setChanged();
        return hitProgress >= hitsRequired;
    }

    /**
     * Called by {@link LogSplittingHandler} just before it calls
     * {@code level.setBlock()} to restore the original slab. Lets
     * {@code onBlockBreak} distinguish our programmatic removal from player mining.
     */
    public void markCompleting() {
        this.completingNow = true;
    }

    public boolean isCompletingNow() {
        return completingNow;
    }

    /**
     * Progress as a fraction 0.0–1.0 for rendering the crack overlay.
     */
    public float getProgressFraction() {
        if (hitsRequired <= 0) return 1f;
        return Math.min(1f, (float) hitProgress / hitsRequired);
    }

    // -----------------------------------------------------------------------
    // Drop helpers
    // -----------------------------------------------------------------------

    /**
     * Returns an ItemStack for the original slab (for dropping on break).
     */
    public ItemStack getOriginalSlabDrop() {
        if (originalSlabState == null) return ItemStack.EMPTY;
        return new ItemStack(originalSlabState.getBlock().asItem());
    }

    @Override
    @Nullable
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    // -----------------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------------


    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (originalSlabState != null) {
            tag.put("OriginalSlab", NbtUtils.writeBlockState(originalSlabState));
        }
        tag.putString("RawLogBlockId", rawLogBlockId);
        tag.putString("LogBlockId", logBlockId);
        tag.putInt("HitProgress", hitProgress);
        tag.putInt("HitsRequired", hitsRequired);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("OriginalSlab")) {
            originalSlabState = NbtUtils.readBlockState(
                    BuiltInRegistries.BLOCK.asLookup(),
                    tag.getCompound("OriginalSlab"));
        }
        rawLogBlockId = tag.getString("RawLogBlockId");
        logBlockId = tag.getString("LogBlockId");
        hitProgress = tag.getInt("HitProgress");
        hitsRequired = tag.getInt("HitsRequired");
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
}
