package exp.CCnewmods.misanthrope_world.temperature.overlay;

import net.minecraft.world.item.ItemStack;

/**
 * Public API facade for ThermalMaterialRegistry.
 * <p>
 * ThermalMaterialRegistry is package-private (it's an implementation detail
 * of the overlay package). This class exposes the one method the mixin needs
 * without making the entire registry public.
 * <p>
 * Also owns the fallback tint ramp so the mixin doesn't need to reach into
 * ItemTemperatureColorHandler for it — one call covers both cases.
 */
public final class ThermalMaterialLookup {

    private ThermalMaterialLookup() {
    }

    /**
     * Returns the ARGB tint for {@code stack} at {@code celsius}.
     * <p>
     * Checks the material registry first (data-driven thermal_material JSONs).
     * Falls back to the generic heat/cold ramp if no material definition exists.
     * <p>
     * Returns 0 if the temperature is in the neutral range or no tint applies.
     *
     * @param stack   the item being rendered
     * @param celsius its current temperature
     * @return ARGB tint int, or 0 for no tint
     */
    public static int getTint(ItemStack stack, double celsius) {
        ThermalMaterialData material = ThermalMaterialRegistry.getForItem(stack);
        if (material != null) {
            int tint = material.getTintForTemperature(celsius);
            if (tint != 0) return tint;
        }
        return ItemTemperatureColorHandler.getFallbackTint(celsius);
    }
}
