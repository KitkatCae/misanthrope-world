package exp.CCnewmods.misanthrope_world.mixin.wet_sand;

import exp.CCnewmods.misanthrope_world.wet_sand.WettableFallingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SugarCaneBlock.class, remap = false)
public class SugarCaneBlockMixin {

    @Inject(
            method = "m_7898_",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void misanthrope$canSurviveOnWetSoil(
            BlockState state,
            LevelReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {

        if (level.getBlockState(pos.below()).getBlock() instanceof WettableFallingBlock wetBlock
                && wetBlock.allowsSugarCane()) {
            cir.setReturnValue(true);
        }
    }
}