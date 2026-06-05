package exp.CCnewmods.misanthrope_world.mixin.wet_sand;

import exp.CCnewmods.misanthrope_world.wet_sand.WettableFallingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FarmBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes wet soil variants count as moisture sources for farmland.
 * <p>
 * remap=false with full descriptor is required because isNearWater is not in
 * Parchment's obfuscation mapping table. Signature confirmed from mapped jar:
 * isNearWater(LevelReader, BlockPos)Z
 */
@Mixin(value = FarmBlock.class, remap = false)
public class FarmlandBlockMixin {

    @Inject(
            method = "m_53258_",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void misanthrope$checkWetSoilMoisture(
            LevelReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {

        // Check the block at pos
        if (level.getBlockState(pos).getBlock() instanceof WettableFallingBlock wetBlock
                && wetBlock.isMoistureSource()) {
            cir.setReturnValue(true);
            return;
        }

        // Also check direct horizontal neighbours from pos
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(dir)).getBlock() instanceof WettableFallingBlock wetBlock
                    && wetBlock.isMoistureSource()) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
