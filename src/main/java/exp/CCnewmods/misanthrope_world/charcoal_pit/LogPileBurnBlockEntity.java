package exp.CCnewmods.misanthrope_world.charcoal_pit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for {@link LogPileBlock} while it is burning.
 *
 * <p>Every log pile in the pit has one of these. They are completely
 * independent — each validates its own 6 neighbors and counts down
 * its own timer. No central controller is needed.
 *
 * <h3>Validation</h3>
 * Every {@value #VALIDATE_INTERVAL} ticks, checks that all 6 faces
 * are still sealed (dirt, gravel, or another log pile). If not, starts
 * counting {@code invalidTicks}. After {@value #GRACE_TICKS} consecutive
 * invalid ticks without recovery, the block destroys itself and drops nothing.
 *
 * <h3>Completion</h3>
 * At {@value #BURN_TICKS} ticks, replaces itself with a charcoal layer block
 * at {@code layers=2} (two layers per log).
 */
public class LogPileBurnBlockEntity extends BlockEntity {

    /**
     * 18 MC hours × 1000 ticks/hour.
     */
    public static final int BURN_TICKS = 18_000;

    /**
     * How often to re-validate the seal (ticks).
     */
    private static final int VALIDATE_INTERVAL = 10;

    /**
     * How many ticks the pit can remain unsealed before it is ruined.
     * ~4 seconds — enough time to quickly cover a face you accidentally opened.
     */
    private static final int GRACE_TICKS = 80;

    private int burnProgress = 0;
    private int invalidTicks = 0;

    public LogPileBurnBlockEntity(BlockPos pos, BlockState state) {
        super(CharcoalPitRegistration.LOG_PILE_BE.get(), pos, state);
    }

    // -----------------------------------------------------------------------
    // Tick
    // -----------------------------------------------------------------------

    public static void tick(Level level, BlockPos pos, BlockState state,
                            LogPileBurnBlockEntity be) {
        if (level.isClientSide) return;
        if (!state.getValue(LogPileBlock.BURNING)) return;

        ServerLevel serverLevel = (ServerLevel) level;
        be.burnProgress++;

        // --- Periodic seal validation ---
        if (be.burnProgress % VALIDATE_INTERVAL == 0) {
            if (CharcoalPitValidator.isPosSealed(level, pos)) {
                be.invalidTicks = 0; // reset grace
            } else {
                be.invalidTicks += VALIDATE_INTERVAL;
                if (be.invalidTicks >= GRACE_TICKS) {
                    be.ruin(serverLevel, pos);
                    return;
                }
            }
            be.setChanged();
        }

        // --- Smoke particles every 40 ticks from top face ---
        if (be.burnProgress % 40 == 0) {
            boolean hasLogAbove = level.getBlockState(pos.above())
                    .getBlock() instanceof LogPileBlock;
            if (!hasLogAbove) {
                serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                        pos.getX() + 0.5,
                        pos.getY() + 1.1,
                        pos.getZ() + 0.5,
                        1, 0.15, 0.0, 0.15, 0.005);
            }
        }

        // --- Completion ---
        if (be.burnProgress >= BURN_TICKS) {
            be.finish(serverLevel, pos);
        }
    }

    // -----------------------------------------------------------------------
    // Completion — replace self with charcoal layers
    // -----------------------------------------------------------------------

    private void finish(ServerLevel level, BlockPos pos) {
        CharcoalLayerBlock layerBlock =
                (CharcoalLayerBlock) CharcoalPitRegistration.CHARCOAL_LAYER.get();

        level.setBlock(pos,
                layerBlock.defaultBlockState()
                        .setValue(CharcoalLayerBlock.LAYERS, 2),
                3);

        // Only play sound if no log pile above (avoid spamming for large pits)
        boolean hasLogAbove = level.getBlockState(pos.above())
                .getBlock() instanceof LogPileBlock;
        if (!hasLogAbove) {
            level.playSound(null, pos,
                    SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
                    0.5f, 0.9f + level.getRandom().nextFloat() * 0.2f);
            level.sendParticles(ParticleTypes.ASH,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    8, 0.3, 0.1, 0.3, 0.02);
        }
    }

    // -----------------------------------------------------------------------
    // Ruin — exposed too long, destroy without drops
    // -----------------------------------------------------------------------

    private void ruin(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        // Brief smoke puff to signal failure
        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                5, 0.3, 0.3, 0.3, 0.02);
    }

    // -----------------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------------

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("BurnProgress", burnProgress);
        tag.putInt("InvalidTicks", invalidTicks);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        burnProgress = tag.getInt("BurnProgress");
        invalidTicks = tag.getInt("InvalidTicks");
    }
}
