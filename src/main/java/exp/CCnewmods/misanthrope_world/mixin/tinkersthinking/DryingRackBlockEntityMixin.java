package exp.CCnewmods.misanthrope_world.mixin.tinkersthinking;

import com.creeping_creeper.tinkers_thinking.common.things.block.entity.DryingRackBlockEntity;
import exp.CCnewmods.misanthrope_world.drying.EnvironmentalDryingRecipe;
import exp.CCnewmods.misanthrope_world.drying.EnvironmentalDryingRecipeType;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Replaces TinkersThinking's drying rack tick logic entirely with our
 * {@code misanthrope_world:environmental_drying} recipe type.
 *
 * <p>TT's {@code hasRecipe}, {@code craftItem}, and {@code resetProgress}
 * helpers are bypassed — we own the full tick from HEAD and cancel TT's path.
 *
 * <p>Add to {@code misanthrope_world.mixins.json} under {@code "mixins"}:
 * <pre>{@code "tinkersthinking.DryingRackBlockEntityMixin"}</pre>
 */
@Mixin(value = DryingRackBlockEntity.class, remap = false)
public class DryingRackBlockEntityMixin {

    // These field names are confirmed from TT's bytecode constant pool
    @Shadow
    private int progress;
    @Shadow
    private int maxProgress;
    @Shadow
    private ItemStackHandler itemStackHandler;

    @Inject(
            method = "tick(Lnet/minecraft/world/level/Level;" +
                    "Lnet/minecraft/core/BlockPos;" +
                    "Lnet/minecraft/world/level/block/state/BlockState;" +
                    "Lcom/creeping_creeper/tinkers_thinking/common/things/block/entity/DryingRackBlockEntity;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void mis$replaceTick(Level level, BlockPos pos, BlockState state,
                                        DryingRackBlockEntity entity, CallbackInfo ci) {
        ci.cancel(); // We own all drying — TT's path never runs
        if (level.isClientSide()) return;

        DryingRackBlockEntityMixin self = (DryingRackBlockEntityMixin) (Object) entity;

        ItemStack stack = self.itemStackHandler.getStackInSlot(0);
        if (stack.isEmpty()) {
            self.progress = 0;
            return;
        }

        // ── Recipe lookup ─────────────────────────────────────────────────────
        SimpleContainer container = new SimpleContainer(stack);
        Optional<EnvironmentalDryingRecipe> recipeOpt = level.getRecipeManager()
                .getRecipeFor(EnvironmentalDryingRecipeType.INSTANCE, container, level);

        if (recipeOpt.isEmpty()) {
            self.progress = 0;
            return;
        }

        EnvironmentalDryingRecipe recipe = recipeOpt.get();

        // ── Environment sampling ──────────────────────────────────────────────
        double ambientCelsius = MisTemperatureAPI.getAmbientCelsius(level, pos);
        double humidityMbar = mis$getHumidity(level, pos);
        boolean hasAirflow = mis$hasAirflow(level, pos);

        if (!recipe.conditionsMet(ambientCelsius, humidityMbar, hasAirflow)) {
            self.progress = 0;
            return;
        }

        // ── Progress advance ──────────────────────────────────────────────────
        self.maxProgress = recipe.getBaseTicks();
        double quality = recipe.conditionQuality(ambientCelsius, humidityMbar);
        double speedMult = recipe.getRackSpeedMultiplier();
        // Minimum increment of 1 so we always finish if conditions are met
        int increment = (int) Math.max(1, Math.round(speedMult * quality));
        self.progress += increment;

        if (self.progress >= self.maxProgress) {
            ItemStack result = recipe.assemble(container, level.registryAccess());
            self.itemStackHandler.setStackInSlot(0, result);
            self.progress = 0;
            entity.setChanged();
            mis$sendSyncPacket(level, pos, self.itemStackHandler);
        } else {
            entity.setChanged();
        }
    }

    @Unique
    private static double mis$getHumidity(Level level, BlockPos pos) {
        try {
            Class<?> g = Class.forName("mge.api.EnvironmentGrid");
            Object grid = g.getMethod("get", Level.class).invoke(null, level);
            if (grid == null) return 10.0;
            return (double) grid.getClass()
                    .getMethod("getHumidityMbar", BlockPos.class).invoke(grid, pos);
        } catch (Exception ignored) {
            return 10.0;
        }
    }

    @Unique
    private static boolean mis$hasAirflow(Level level, BlockPos pos) {
        for (Direction dir : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP})
            if (level.isEmptyBlock(pos.relative(dir))) return true;
        return false;
    }

    @Unique
    private static void mis$sendSyncPacket(Level level, BlockPos pos, ItemStackHandler handler) {
        try {
            Class<?> pkt = Class.forName(
                    "com.creeping_creeper.tinkers_thinking.common.networking.packet.packet.ItemStackSyncS2CPacket");
            Class<?> msg = Class.forName(
                    "com.creeping_creeper.tinkers_thinking.common.networking.ModMessages");
            Object packet = pkt.getConstructor(ItemStackHandler.class, BlockPos.class)
                    .newInstance(handler, pos);
            msg.getMethod("sendToClients", Object.class).invoke(null, packet);
        } catch (Exception ignored) {
        } // Non-critical; client syncs on next chunk save
    }
}
