package exp.CCnewmods.misanthrope_world.physics.structural.phasing;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Per-tick handler for Misanthrope's own phasing/engulfing (driven by
 * {@link BlockPhysicsData.PhasingData}, see {@code PhaseableCollisionMixin} for
 * the collision-suppression half) plus the reimplemented dripstone-graze
 * mechanic (previously FullStop's {@code kineticBlockBreaking}, now
 * force-disabled — see {@code FullStopConfigLock} — so this replaces the one
 * piece of that feature Caelan wanted kept).
 *
 * <p>Mirrors FullStop's own {@code BlockPhasing.onLivingTick} approach
 * deliberately (same box-scan technique) for consistency, but reads our own
 * material data instead of FullStop's tags, and reuses
 * {@link FullStopCapability} for velocity where available (see
 * {@code MWorld_FullStop_Integration_v1.md} §3.1) since it's more reliable
 * than raw {@code getDeltaMovement()} (teleport-safe, client-authoritative
 * for players).
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KineticBlockEnvironmentHandler {

    /** Native blocks/tick, squared. Below this, nothing here can possibly trigger — skip the block scan entirely. */
    private static final double MIN_ACTIVE_SPEED_SQR = 0.04;

    /**
     * Native blocks/tick horizontal speed, squared, required for a dripstone graze.
     * Matches FullStop's original {@code handleDripstoneSideImpact} threshold
     * (0.3 blocks/tick ≈ 6 m/s) — kept identical since it was a reasonable value,
     * not something that needed re-tuning.
     */
    private static final double DRIPSTONE_GRAZE_MIN_HORIZONTAL_SPEED_SQR = 0.09;

    private KineticBlockEnvironmentHandler() {
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();
        if (level.isClientSide) return; // server-authoritative only — this breaks blocks and moves entities, not cosmetic
        if (!(level instanceof ServerLevel serverLevel)) return;

        Vec3 velocity = currentVelocityNative(entity);
        if (velocity.lengthSqr() < MIN_ACTIVE_SPEED_SQR) return;

        AABB box = entity.getBoundingBox().deflate(0.01);

        BlockPhysicsData.PhasingData engulfingHit = null;
        BlockPos engulfingPos = null;
        BlockPhysicsData.PhasingData phasingHit = null;

        boolean dripstoneGrazeEligible = velocity.horizontalDistanceSqr() >= DRIPSTONE_GRAZE_MIN_HORIZONTAL_SPEED_SQR
                && serverLevel.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);

        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {

            BlockState state = level.getBlockState(pos);

            if (dripstoneGrazeEligible && state.getBlock() instanceof PointedDripstoneBlock) {
                // NOTE: this is an AABB-overlap approximation, not a true swept
                // raycast like FullStop's original CommonCollisionDetector — we
                // don't have a hit face/direction here, just "the entity's box
                // currently overlaps a dripstone block at meaningful horizontal
                // speed". Good enough for the common case; revisit with a real
                // raycast if it proves too eager/unreliable in testing.
                grazeDripstone(serverLevel, pos.immutable(), state);
                // Block is now air (or was already handled) — don't also treat
                // it as a phase/engulf candidate this tick.
                continue;
            }

            BlockPhysicsData.PhasingData phasing = BlockPhysicsRegistry.get(state).phasing;
            if (phasing == null) continue;

            if (phasing.engulfing()) {
                engulfingHit = phasing;
                engulfingPos = pos.immutable();
                break; // engulfing takes priority the moment we find one, matching FullStop's own break-on-first-match
            }
            if (phasing.phaseable() && phasingHit == null) {
                phasingHit = phasing; // remember the first phaseable block's own drag value
            }
        }

        if (engulfingHit != null) {
            entity.setDeltaMovement(velocity.scale(engulfingHit.engulfDragPerTick()));
            if (engulfingHit.engulfParticle()) {
                burrowEffects(serverLevel, entity, level.getBlockState(engulfingPos), engulfingPos);
            }
        } else if (phasingHit != null) {
            entity.setDeltaMovement(velocity.scale(phasingHit.phaseDragPerTick()));
        }
    }

    private static Vec3 currentVelocityNative(LivingEntity entity) {
        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        return cap != null ? cap.getCurrentNativeVelocity() : entity.getDeltaMovement();
    }

    private static void burrowEffects(ServerLevel level, LivingEntity entity, BlockState state, BlockPos pos) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                entity.getX(), entity.getY(entity.isVisuallySwimming() ? 0.5 : 0.8), entity.getZ(),
                12, entity.getBbWidth() * 0.5, entity.getBbHeight() * 0.4, entity.getBbWidth() * 0.5, 0.05);
        if (level.random.nextFloat() < 0.4f) {
            level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS,
                    0.8f, 0.8f + level.random.nextFloat() * 0.4f);
        }
    }

    /**
     * Reimplementation of FullStop's {@code handleDripstoneSideImpact}: a
     * horizontal graze against a stalactite/stalagmite breaks it and drops it
     * as a falling spike that hurts whatever it lands on.
     */
    private static void grazeDripstone(ServerLevel level, BlockPos pos, BlockState state) {
        level.playSound(null, pos, SoundEvents.POINTED_DRIPSTONE_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
        FallingBlockEntity fallingSpike = FallingBlockEntity.fall(level, pos, state);
        fallingSpike.setHurtsEntities(1.0f, 40);
    }
}
