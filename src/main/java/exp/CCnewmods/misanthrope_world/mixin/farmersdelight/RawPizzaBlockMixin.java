package exp.CCnewmods.misanthrope_world.mixin.farmersdelight;

import com.tiviacz.pizzadelight.blocks.RawPizzaBlock;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes pizza bake based on thermal field temperature instead of FD heat_sources tag.
 *
 * Updated for pizzadelight 1.0.2 (1.20.1):
 * RawPizzaBlock.m_214162_ (Block.randomTick) takes a plain Level, not ServerLevel.
 * Confirmed from bytecode:
 *   m_214162_(BlockState, Level, BlockPos, RandomSource)V
 *
 * ── Why MisTemperatureAPI instead of ThermalField.getTemperatureAt ────────────
 * ThermalField.getTemperatureAt requires ServerLevel (it keys into the STATES map).
 * We only have Level here. MisTemperatureAPI.getAmbientCelsius(Level, BlockPos) is
 * the correct Level-safe equivalent — it reads from EnvironmentGrid first (which
 * accepts plain Level) and falls back to biome/dimension ambient, which is exactly
 * what ThermalField.getTemperatureAt does internally anyway.
 *
 * randomTick only fires server-side, so level.isClientSide() is always false here
 * in practice, but the guard is kept for safety.
 */
@Mixin(value = RawPizzaBlock.class, remap = false)
public abstract class RawPizzaBlockMixin {

    private static final double PIZZA_BAKE_THRESHOLD = 180.0;

    @Inject(
            method = "m_214162_(Lnet/minecraft/world/level/block/state/BlockState;" +
                    "Lnet/minecraft/world/level/Level;" +
                    "Lnet/minecraft/core/BlockPos;" +
                    "Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void misanthrope$checkHeat(BlockState state, Level level,
                                       BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (level.isClientSide()) return;

        double temp = MisTemperatureAPI.getAmbientCelsius(level, pos);
        if (Double.isNaN(temp)) temp = MisTemperatureAPI.getAmbientCelsius(level, pos.below());

        if (!Double.isNaN(temp) && temp >= PIZZA_BAKE_THRESHOLD) {
            // Warm enough — let the original baking tick run
            return;
        }

        // Too cold — suppress baking entirely
        ci.cancel();
    }
}