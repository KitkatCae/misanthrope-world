package exp.CCnewmods.misanthrope_world.mixin.farmersdelight;

import exp.CCnewmods.misanthrope_world.physics.burned.BurnedFoodRegistry;
import exp.CCnewmods.misanthrope_world.physics.field.ThermalField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Scales FD's cooking pot speed linearly with temperature and applies burning.
 *
 * Updated for FarmersDelight 1.3.x: the static tick method was renamed from
 * "tick" to "cookingTick". All three injections target the new name.
 */
@Mixin(value = CookingPotBlockEntity.class, remap = false)
public abstract class CookingPotTickMixin {

    private static final double FD_HEAT_THRESHOLD = 80.0;
    private static final double REFERENCE_TEMP = 400.0;
    private static final double MAX_MULTIPLIER = 4.0;

    private static final ThreadLocal<Double> CURRENT_TEMP = new ThreadLocal<>();

    // ── Capture temperature at tick start ─────────────────────────────────────

    @Inject(
            method = "cookingTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
                    "Lnet/minecraft/world/level/block/state/BlockState;" +
                    "Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;)V",
            at = @At("HEAD"),
            remap = false
    )
    private static void misanthrope$captureTemp(Level level, BlockPos pos, BlockState state,
                                                CookingPotBlockEntity be, CallbackInfo ci) {
        if (!(level instanceof ServerLevel sl)) {
            CURRENT_TEMP.remove();
            return;
        }
        double temp = ThermalField.getTemperatureAt(sl, pos);
        if (Double.isNaN(temp)) temp = ThermalField.getTemperatureAt(sl, pos.below());
        CURRENT_TEMP.set(Double.isNaN(temp) ? FD_HEAT_THRESHOLD : temp);
    }

    // ── Scale cook speed at tick tail ─────────────────────────────────────────

    @Inject(
            method = "cookingTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
                    "Lnet/minecraft/world/level/block/state/BlockState;" +
                    "Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;)V",
            at = @At("TAIL"),
            remap = false
    )
    private static void misanthrope$scaleCookSpeed(Level level, BlockPos pos, BlockState state,
                                                   CookingPotBlockEntity be, CallbackInfo ci) {
        Double temp = CURRENT_TEMP.get();
        CURRENT_TEMP.remove();
        if (temp == null || temp < FD_HEAT_THRESHOLD) return;

        double multiplier = Math.min(MAX_MULTIPLIER,
                (temp - FD_HEAT_THRESHOLD) / (REFERENCE_TEMP - FD_HEAT_THRESHOLD));
        double extra = Math.max(0.0, multiplier - 1.0);
        int extraWhole = (int) extra;
        double extraFrac = extra - extraWhole;

        try {
            var field = CookingPotBlockEntity.class.getDeclaredField("cookTime");
            field.setAccessible(true);
            int current = field.getInt(be);
            current += extraWhole;
            if (Math.random() < extraFrac) current++;
            field.setInt(be, current);
        } catch (Exception ignored) {
        }
    }

    // ── Burning check at tick start (every 10 ticks) ──────────────────────────

    @Inject(
            method = "cookingTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
                    "Lnet/minecraft/world/level/block/state/BlockState;" +
                    "Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;)V",
            at = @At("HEAD"),
            remap = false
    )
    private static void misanthrope$checkBurning(Level level, BlockPos pos, BlockState state,
                                                 CookingPotBlockEntity be, CallbackInfo ci) {
        if (!(level instanceof ServerLevel sl)) return;
        if (level.getGameTime() % 10 != 0) return;

        double temp = ThermalField.getTemperatureAt(sl, pos);
        if (Double.isNaN(temp)) return;

        try {
            var capField = CookingPotBlockEntity.class.getDeclaredField("inventory");
            capField.setAccessible(true);
            var handler = (net.minecraftforge.items.IItemHandler) capField.get(be);
            if (handler == null) return;

            for (int i = 0; i < Math.min(handler.getSlots(), 6); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (stack.isEmpty()) continue;
                ItemStack result = BurnedFoodRegistry.applyBurning(stack, temp);
                if (result != stack) {
                    if (handler instanceof net.minecraftforge.items.ItemStackHandler ish) {
                        ish.setStackInSlot(i, result);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
