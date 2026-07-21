package exp.CCnewmods.misanthrope_world.mixin.coldsweat;

import com.momosoftworks.coldsweat.api.temperature.modifier.BiomeTempModifier;
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
 * public Function<Double, Double> calculate(LivingEntity, Temperature$Trait)
 *
 * See {@link ColdSweatChunkSafety} for why this exists. If the sample area around the
 * entity isn't fully resident yet, cancel before Cold Sweat's own loop can reach
 * Level.getBiomeManager().getBiome(pos) on a chunk that isn't loaded, and hand back the
 * same "no contribution this tick" identity function Cold Sweat itself already uses as
 * its own null-result fallback (see CaveBiomeTempModifier's caveBiomeCount == 0 case) —
 * so this doesn't introduce new player-visible behavior, it just widens the existing
 * "nothing to sample yet" path to also cover "can't safely sample yet".
 */
@Mixin(value = BiomeTempModifier.class, remap = false)
public class BiomeTempModifierMixin
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
