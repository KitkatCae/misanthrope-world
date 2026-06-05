package exp.CCnewmods.misanthrope_world.temperature.melt;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ═══════════════════════════════════════════════════════════════════════════════
// MeltData
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Defines what happens when an item reaches its melting temperature.
 * <p>
 * ── JSON format ───────────────────────────────────────────────────────────────
 * data/misanthrope_core/melt_data/<name>.json
 * <p>
 * {
 * "item":              "minecraft:iron_ingot",
 * "fluid":             "minecraft:lava",
 * "melt_celsius":      1538.0,
 * "fluid_mb":          144,
 * "byproduct_item":    null,
 * "byproduct_chance":  0.0
 * }
 * <p>
 * ── fluid_mb ─────────────────────────────────────────────────────────────────
 * How many millibuckets of fluid one item produces when melted.
 * Reference values:
 * minecraft:ice         → minecraft:water,   1000mB (full block)
 * minecraft:snow_block  → minecraft:water,    250mB
 * minecraft:iron_ingot  → minecraft:lava,     144mB  (TConstruct standard)
 * minecraft:gold_ingot  → minecraft:lava,     144mB
 * minecraft:copper_ingot→ minecraft:lava,     144mB
 * misanthrope_core:wax_block → misanthrope_core:liquid_wax, 1000mB
 * <p>
 * ── byproduct ─────────────────────────────────────────────────────────────────
 * Optional item produced alongside the fluid (e.g. slag, ash).
 * byproduct_chance: 0.0–1.0, rolled per melt event.
 */

public record MeltData(
        ResourceLocation itemId,
        ResourceLocation fluidId,
        double meltCelsius,
        int fluidMb,
        @Nullable ResourceLocation byproductItemId,
        float byproductChance
) {
    /**
     * How many FlowingFluids "levels" this item produces.
     * FlowingFluids uses 8 levels per full block.
     * fluid_mb / 125 = levels (1000mB = 8 levels, 125mB = 1 level)
     */
    public int flowingFluidsLevels() {
        return Math.max(1, Math.round(fluidMb / 125f));
    }

    @Nullable
    public Fluid getFluid() {
        return ForgeRegistries.FLUIDS.getValue(fluidId);
    }

    @Nullable
    public net.minecraft.world.item.Item getByproductItem() {
        if (byproductItemId == null) return null;
        return ForgeRegistries.ITEMS.getValue(byproductItemId);
    }
}
