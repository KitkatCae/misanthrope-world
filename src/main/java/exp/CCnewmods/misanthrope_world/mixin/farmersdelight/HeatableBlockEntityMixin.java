package exp.CCnewmods.misanthrope_world.mixin.farmersdelight;

import exp.CCnewmods.misanthrope_world.physics.field.ThermalField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Replaces FD's heat detection with our thermal field.
 *
 * Updated for FarmersDelight 1.3.x:
 * - isHeated(Level, BlockPos) is now isHeated() — a no-arg instance method.
 *   The BE already holds its own level and pos; FD queries those internally.
 * - We cancel early (returning true) if thermal field says it's hot enough,
 *   otherwise fall through to FD's original block-tag check as a fallback.
 *
 * Because the method is now instance (not static), the injector runs on 'this'
 * which is the CookingPotBlockEntity instance — we need getLevel() and getBlockPos()
 * to get the context, both of which are available on BlockEntity.
 */
@Mixin(value = CookingPotBlockEntity.class, remap = false)
public abstract class HeatableBlockEntityMixin {

    private static final double FD_HEAT_THRESHOLD_CELSIUS = 80.0;

    @Inject(
            method = "isHeated()Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void misanthrope$isHeated(CallbackInfoReturnable<Boolean> cir) {
        // Cast to BlockEntity to access getLevel() and getBlockPos()
        net.minecraft.world.level.block.entity.BlockEntity self =
                (net.minecraft.world.level.block.entity.BlockEntity) (Object) this;

        Level level = self.getLevel();
        if (!(level instanceof ServerLevel sl)) return;

        BlockPos pos = self.getBlockPos();

        double temp = ThermalField.getTemperatureAt(sl, pos);
        if (!Double.isNaN(temp) && temp >= FD_HEAT_THRESHOLD_CELSIUS) {
            cir.setReturnValue(true);
            return;
        }

        double belowTemp = ThermalField.getTemperatureAt(sl, pos.below());
        if (!Double.isNaN(belowTemp) && belowTemp >= FD_HEAT_THRESHOLD_CELSIUS) {
            cir.setReturnValue(true);
        }
        // Fall through to FD's original tag check if neither position is warm enough.
    }
}
