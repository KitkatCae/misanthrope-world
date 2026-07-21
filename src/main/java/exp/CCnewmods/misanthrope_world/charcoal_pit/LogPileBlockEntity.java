package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores the contents of a {@link LogPileBlock} — up to 16 cut wood pieces,
 * each identified by the wood type ID of the cut wood item that was placed
 * (e.g. {@code "minecraft:oak"}, {@code "biomesoplenty:fir"}).
 *
 * <p>Slot order is insertion order: slot 0 is the first piece placed,
 * slot 15 is the last. Empty slots are stored as empty strings.
 *
 * <p>The renderer reads these IDs at render time and looks up the
 * corresponding stripped-log side texture and log top texture to display
 * in each of the 16 log elements of the block model.
 */
public class LogPileBlockEntity extends BlockEntity {

    public static final int CAPACITY = 16;
    private static final String SLOTS_TAG = "Slots";

    /**
     * Wood type IDs for each log slot. Empty string = empty slot.
     */
    private final String[] slots = new String[CAPACITY];
    private int count = 0;

    public LogPileBlockEntity(BlockPos pos, BlockState state) {
        super(CharcoalPitRegistration.LOG_PILE_BE.get(), pos, state);
        for (int i = 0; i < CAPACITY; i++) slots[i] = "";
    }

    // -----------------------------------------------------------------------
    // Slot access
    // -----------------------------------------------------------------------

    /**
     * Returns the wood type ID in slot {@code index}, or empty string if empty.
     */
    public String getSlot(int index) {
        return slots[index];
    }

    /**
     * Returns a snapshot copy of all 16 slots.
     */
    public String[] getSlotsSnapshot() {
        return slots.clone();
    }

    /**
     * How many slots are currently occupied.
     */
    public int getCount() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean isFull() {
        return count >= CAPACITY;
    }

    /**
     * Adds a cut wood piece of {@code woodTypeId} to the next available slot.
     * Returns true if added, false if full.
     */
    public boolean addLog(String woodTypeId) {
        if (isFull()) return false;
        slots[count] = woodTypeId;
        count++;
        setChanged();
        return true;
    }

    /**
     * Removes and returns the most recently added wood type ID (LIFO).
     * Returns null if empty.
     */
    public String removeTopLog() {
        if (isEmpty()) return null;
        count--;
        String id = slots[count];
        slots[count] = "";
        setChanged();
        return id;
    }

    // -----------------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------------

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        CompoundTag slotsTag = new CompoundTag();
        for (int i = 0; i < CAPACITY; i++) {
            slotsTag.putString(String.valueOf(i), slots[i]);
        }
        tag.put(SLOTS_TAG, slotsTag);
        tag.putInt("Count", count);
        tag.putInt("BurnProgress", burnProgress);
        tag.putInt("InvalidTicks", invalidTicks);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        count = tag.getInt("Count");
        if (tag.contains(SLOTS_TAG)) {
            CompoundTag slotsTag = tag.getCompound(SLOTS_TAG);
            for (int i = 0; i < CAPACITY; i++) {
                slots[i] = slotsTag.getString(String.valueOf(i));
            }
        }
        burnProgress = tag.getInt("BurnProgress");
        invalidTicks = tag.getInt("InvalidTicks");
    }

    /**
     * Sync tag sent to client for rendering.
     */
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
    // -----------------------------------------------------------------------
    // Burn logic (charcoal pit) — only active when BURNING=true
    // -----------------------------------------------------------------------

    public static final int BURN_TICKS = 18_000;
    private static final int VALIDATE_INTERVAL = 10;
    private static final int GRACE_TICKS = 80;

    private int burnProgress = 0;
    private int invalidTicks = 0;

    public static void tickBurn(net.minecraft.world.level.Level level, BlockPos pos,
                                BlockState state, LogPileBlockEntity be) {
        if (level.isClientSide) return;
        if (!state.getValue(LogPileBlock.BURNING)) return;

        net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) level;
        be.burnProgress++;

        if (be.burnProgress % VALIDATE_INTERVAL == 0) {
            if (CharcoalPitValidator.isPosSealed(level, pos)) {
                be.invalidTicks = 0;
            } else {
                be.invalidTicks += VALIDATE_INTERVAL;
                if (be.invalidTicks >= GRACE_TICKS) {
                    be.ruin(sl, pos);
                    return;
                }
            }
            be.setChanged();
        }

        if (be.burnProgress % 40 == 0) {
            boolean hasLogAbove = level.getBlockState(pos.above()).getBlock() instanceof LogPileBlock;
            if (!hasLogAbove) {
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE,
                        pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                        1, 0.15, 0.0, 0.15, 0.005);
            }
        }

        if (be.burnProgress >= BURN_TICKS) {
            be.finish(sl, pos);
        }
    }

    private void finish(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        CharcoalLayerBlock layerBlock = (CharcoalLayerBlock) CharcoalPitRegistration.CHARCOAL_LAYER.get();
        level.setBlock(pos, layerBlock.defaultBlockState().setValue(CharcoalLayerBlock.LAYERS, 2), 3);
        boolean hasLogAbove = level.getBlockState(pos.above()).getBlock() instanceof LogPileBlock;
        if (!hasLogAbove) {
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.5f,
                    0.9f + level.getRandom().nextFloat() * 0.2f);
        }
    }

    private void ruin(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                5, 0.3, 0.3, 0.3, 0.02);
    }

}

// NOTE: append this before the closing brace of the class
