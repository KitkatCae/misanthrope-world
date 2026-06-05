package exp.CCnewmods.misanthrope_world.mixin.temperature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import exp.CCnewmods.misanthrope_world.temperature.capability.ItemTemperatureCapability;
import exp.CCnewmods.misanthrope_world.temperature.overlay.ItemTemperatureColorHandler;
import exp.CCnewmods.misanthrope_world.temperature.overlay.ThermalMaterialData;
import exp.CCnewmods.misanthrope_world.temperature.overlay.ThermalMaterialLookup;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * Injects temperature-based colour tints into every item render call,
 * covering all items in the game with zero load-time registration overhead.
 * <p>
 * ── Why string target ─────────────────────────────────────────────────────
 * ItemRenderer is a client-only class. Using @Mixin(ItemRenderer.class)
 * causes a compile error on dedicated server builds and in any environment
 * where the client classes aren't on the compile classpath. The string form
 *
 * @Mixin(targets = "...") defers resolution to Mixin's runtime agent,
 * which only runs on the client where the class exists.
 * <p>
 * ── Injection point ───────────────────────────────────────────────────────
 * renderQuadList (SRG: m_115220_) computes a `color` int from ItemColors
 * (defaulting to 0xFFFFFFFF) and uses it to tint every BakedQuad in the list.
 * We @ModifyVariable that local to multiply our temperature tint on top.
 * <p>
 * ── Coverage ─────────────────────────────────────────────────────────────
 * Every item in every context: held in hand, inventory, dropped entity,
 * item frame, tooltip icon — anything ItemRenderer draws.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(targets = "net.minecraft.client.renderer.ItemRenderer", remap = false)
public class ItemTemperatureTintMixin {

    @ModifyVariable(
            method = "m_115162_(Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lcom/mojang/blaze3d/vertex/VertexConsumer;" +
                    "Ljava/util/List;" +
                    "Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At("STORE"),
            ordinal = 0,
            remap = false
    )
    private int misanthrope_applyTemperatureTint(
            int color,
            PoseStack pose,
            VertexConsumer vc,
            List<BakedQuad> quads,
            ItemStack stack,
            int light,
            int overlay) {

        if (stack.isEmpty()) return color;

        var capOpt = stack.getCapability(ItemTemperatureCapability.CAPABILITY);
        if (!capOpt.isPresent()) return color;

        double celsius = capOpt.map(ItemTemperatureCapability::getCelsius).orElse(20.0);

        if (celsius >= -10.0 && celsius <= 50.0) return color;

        // Resolve tint via the public lookup facade
        int tintArgb = ThermalMaterialLookup.getTint(stack, celsius);
        if (tintArgb == 0) return color;

        return ItemTemperatureColorHandler.multiplyArgb(color, tintArgb);
    }
}
