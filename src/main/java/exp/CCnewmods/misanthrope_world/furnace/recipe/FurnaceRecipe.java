package exp.CCnewmods.misanthrope_world.furnace.recipe;

import exp.CCnewmods.misanthrope_world.furnace.environment.FurnaceEnvironmentSampler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A data-driven transformation inside a furnace multiblock.
 * <p>
 * ── JSON format ────────────────────────────────────────────────────────────────
 * data/misanthrope_core/recipes/furnace_recipes/<n>.json
 * <p>
 * {
 * "input":          "minecraft:iron_ore",
 * "input_tag":      "forge:ores/iron",
 * "input_count":    1,
 * "min_celsius":    900.0,
 * "max_celsius":    1600.0,
 * "ticks_required": 1200,
 * <p>
 * "requires_gas": [
 * { "gas": "mge:oxygen",  "min_mbar": 50.0 },
 * { "gas": "mge:methane", "min_mbar": 5.0  }
 * ],
 * "forbids_gas": [
 * { "gas": "mge:oxygen" },
 * { "gas": "mge:chlorine" }
 * ],
 * <p>
 * "output_item":    "misanthrope_core:bloom_iron",
 * "output_fluid":   "misanthrope_core:iron_fluid",
 * "output_count":   1,
 * <p>
 * "byproduct_item":   "misanthrope_core:slag",
 * "byproduct_chance": 0.3,
 * <p>
 * "emits_gas": [
 * { "gas": "mge:carbon_monoxide", "mbar": 30.0 },
 * { "gas": "mge:carbon_dioxide",  "mbar": 15.0 }
 * ],
 * <p>
 * "furnace_type": "bloomery",
 * "priority": 0
 * }
 * <p>
 * ── Gas constraint semantics ───────────────────────────────────────────────────
 * All requires_gas entries must be satisfied simultaneously.
 * All forbids_gas entries must read 0 mbar.
 * Gas is read from the live GasComposition stored in the Sample — if MGE is
 * absent all readings are 0f, so requires_gas recipes never fire and no-gas
 * recipes fire unconditionally.
 * <p>
 * Common patterns:
 * Normal smelting:       requires_gas: [{gas:"mge:oxygen", min_mbar:50}]
 * Anoxic carbonization:  forbids_gas:  [{gas:"mge:oxygen"}]
 * Fluorine sintering:    requires_gas: [{gas:"mge:fluorine", min_mbar:10}]
 * No gas check:          (omit both fields)
 */
public record FurnaceRecipe(
        ResourceLocation recipeId,

        @Nullable ResourceLocation inputItemId,
        @Nullable ResourceLocation inputTagId,
        int inputCount,

        double minCelsius,
        double maxCelsius,
        int ticksRequired,

        List<GasRequirement> requiresGas,
        List<ResourceLocation> forbidsGas,

        @Nullable ResourceLocation outputItemId,
        @Nullable ResourceLocation outputFluidId,
        int outputCount,

        @Nullable ResourceLocation byproductItemId,
        float byproductChance,

        List<GasEmission> emitsGas,

        @Nullable String furnaceType,
        int priority
) {

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * A gas that must be present at or above min_mbar. 0 means any trace > 0.
     */
    public record GasRequirement(ResourceLocation gasId, float minMbar) {
    }

    /**
     * A gas emitted into MGE atmosphere when this recipe completes.
     */
    public record GasEmission(ResourceLocation gasId, float mbar) {
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final double PROGRESS_RESET_HYSTERESIS = 100.0;

    // ── Input matching ────────────────────────────────────────────────────────

    public boolean matchesInput(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() < inputCount) return false;
        if (inputItemId != null)
            return inputItemId.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        if (inputTagId != null)
            return stack.is(TagKey.create(Registries.ITEM, inputTagId));
        return false;
    }

    public boolean matchesFurnaceType(@Nullable String type) {
        if (furnaceType == null || furnaceType.equals("any")) return true;
        return furnaceType.equals(type);
    }

    // ── Gas context check ─────────────────────────────────────────────────────

    /**
     * Returns true if the gas environment satisfies all constraints.
     * Uses {@link FurnaceEnvironmentSampler.Sample#gasPresenceMbar} which reads
     * the live GasComposition captured from the MGE AtmosphereBlock at the intake.
     */
    public boolean gasContextAllows(FurnaceEnvironmentSampler.Sample sample) {
        for (GasRequirement req : requiresGas) {
            float present = sample.gasPresenceMbar(req.gasId());
            float threshold = req.minMbar() > 0f ? req.minMbar() : 0.001f;
            if (present < threshold) return false;
        }
        for (ResourceLocation forbidden : forbidsGas) {
            if (sample.gasPresenceMbar(forbidden) > 0f) return false;
        }
        return true;
    }

    // ── Temperature check ─────────────────────────────────────────────────────

    public boolean temperatureAllows(double celsius) {
        return celsius >= minCelsius && celsius <= maxCelsius;
    }

    public boolean shouldResetProgress(double celsius) {
        return celsius < (minCelsius - PROGRESS_RESET_HYSTERESIS);
    }

    // ── Output construction ───────────────────────────────────────────────────

    public ItemStack buildOutput() {
        if (outputItemId == null) return ItemStack.EMPTY;
        Item item = ForgeRegistries.ITEMS.getValue(outputItemId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, outputCount);
    }

    public boolean isFluidOutput() {
        return outputFluidId != null && outputItemId == null;
    }

    public ItemStack rollByproduct(net.minecraft.util.RandomSource rng) {
        if (byproductItemId == null || byproductChance <= 0f) return ItemStack.EMPTY;
        if (rng.nextFloat() > byproductChance) return ItemStack.EMPTY;
        Item item = ForgeRegistries.ITEMS.getValue(byproductItemId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    // ── Convenience ───────────────────────────────────────────────────────────

    /**
     * True if this recipe requires mge:oxygen at any level.
     * Used by FurnaceRecipeProcessor for automatic CO/CO₂ injection on completion.
     */
    public boolean requiresOxygen() {
        var o2 = new ResourceLocation("mge", "oxygen");
        return requiresGas.stream().anyMatch(r -> r.gasId().equals(o2));
    }
}
