package exp.CCnewmods.misanthrope_world.mixin.fullstop;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses collision for blocks whose {@link BlockPhysicsData.PhasingData} marks
 * them phaseable/engulfing, for entities whose speed falls in the active band.
 *
 * <p>This is the Misanthrope-owned counterpart to FullStop's own
 * {@code PhaseableBlockMixin} — same injection point, same overall approach
 * (mirrored deliberately for consistency), but keyed off our own
 * {@code material_properties} data instead of the {@code fullstop:phaseable}/
 * {@code fullstop:engulfing} tags. FullStop's own mixin and tags keep running
 * unmodified for vanilla blocks (leaves, sand, etc.); this only fires for
 * blocks that have a non-null {@code phasing} entry, so the two never overlap.
 * See {@code MWorld_FullStop_Integration_v1.md} §4–5.
 *
 * <p><b>Both ends of the speed range fail back to solid collision</b> — below
 * {@code phaseMinSpeedMps}/{@code effectiveEngulfMinSpeedMps()} the block is
 * solid (too slow to bother), and above {@code phaseMaxSpeedMps}/
 * {@code effectiveEngulfMaxSpeedMps()} it's also solid (too fast to trust
 * tunneling through cleanly) — confirmed explicitly, this is not a "no
 * ceiling" design like FullStop's original.
 *
 * <h3>⚠ SRG name not yet verified</h3>
 * The method target below uses the mapped (parchment) name
 * {@code getCollisionShape(BlockGetter, BlockPos, CollisionContext)} with
 * {@code remap=false}. Per the project's established convention for
 * vanilla-method-target mixins (see {@code FarmlandBlockMixin}), this should
 * use the literal SRG identifier (an {@code m_XXXXX_}-style name) instead —
 * I don't have {@code output.srg}/{@code output.tsrg} in this session to look
 * it up. <b>Substitute the correct SRG id for {@code getCollisionShape}
 * (3-argument overload, taking {@code CollisionContext}) before this
 * compiles against the real build</b> — the descriptor below is
 * bytecode-verified against the mapped jar, only the method name string needs
 * the swap.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class, remap = false)
public abstract class PhaseableCollisionMixin {

    @Inject(
            method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", // TODO: replace with SRG id, see class javadoc
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void misanthrope$phaseableCollision(BlockGetter level, BlockPos pos, CollisionContext context,
                                                 CallbackInfoReturnable<VoxelShape> cir) {
        if (!(context instanceof EntityCollisionContext entityContext)) return;
        Entity entity = entityContext.getEntity();
        if (!(entity instanceof LivingEntity living)) return;

        BlockState state = (BlockState) (Object) this;
        BlockPhysicsData.PhasingData phasing = BlockPhysicsRegistry.get(state).phasing;
        if (phasing == null || !phasing.passable()) return;

        double minSpeedMps = phasing.engulfing() ? phasing.effectiveEngulfMinSpeedMps() : phasing.phaseMinSpeedMps();
        double maxSpeedMps = phasing.engulfing() ? phasing.effectiveEngulfMaxSpeedMps() : phasing.phaseMaxSpeedMps();

        double speedMps = currentSpeedMps(living);
        if (speedMps < minSpeedMps || speedMps > maxSpeedMps) return; // solid outside the band, both ends

        cir.setReturnValue(Shapes.empty());
    }

    private static double currentSpeedMps(LivingEntity living) {
        FullStopCapability cap = FullStopCapability.grabCapability(living);
        Vec3 nativeVelocity = cap != null ? cap.getCurrentNativeVelocity() : living.getDeltaMovement();
        return nativeVelocity.length() * 20.0; // blocks/tick -> m/s
    }
}
