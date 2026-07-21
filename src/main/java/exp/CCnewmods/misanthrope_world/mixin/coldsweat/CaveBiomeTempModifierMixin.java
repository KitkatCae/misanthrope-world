package exp.CCnewmods.misanthrope_world.mixin.coldsweat;

import com.momosoftworks.coldsweat.api.temperature.modifier.CaveBiomeTempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import exp.CCnewmods.misanthrope_world.compat.coldsweat.ColdSweatChunkSafety;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Bytecode-verified against cold-sweat 2.4.2 (mapped_parchment_2023_09_03, 1.20.1):
 * protected Function<Double, Double> calculate(LivingEntity, Temperature$Trait)
 *
 * Same rationale as BiomeTempModifierMixin — see ColdSweatChunkSafety. Cube sample here
 * is smaller (sampleRoot=6, interval=6 -> ~18 block radius) but uses the same shared
 * 32-block safety radius for a single, easy-to-audit constant across both modifiers.
 */
@Mixin(value = CaveBiomeTempModifier.class, remap = false)
public class CaveBiomeTempModifierMixin
{
    @Inject(method = "calculate", at = @At("HEAD"), cancellable = true)
    private void misanthrope_world$guardUnloadedChunks(LivingEntity entity, Temperature.Trait trait,
                                                         CallbackInfoReturnable<Function<Double, Double>> cir)
    {
        if (!ColdSweatChunkSafety.isSampleAreaLoaded(entity.level(), entity.blockPosition()))
        {
            cir.setReturnValue(temp -> temp);
        }
    }
}
