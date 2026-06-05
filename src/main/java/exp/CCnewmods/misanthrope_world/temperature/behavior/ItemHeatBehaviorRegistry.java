package exp.CCnewmods.misanthrope_world.temperature.behavior;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads {@link ItemHeatBehavior} definitions from
 * {@code data/misanthrope_core/item_heat_behaviors/<name>.json}.
 * <p>
 * Lookup order:
 * 1. Exact item ID match
 * 2. Item tag match (first registered tag that contains the item)
 * 3. null (caller applies generic defaults)
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ItemHeatBehaviorRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/ItemHeatBehavior");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIR = "item_heat_behaviors";

    public static final ItemHeatBehaviorRegistry INSTANCE = new ItemHeatBehaviorRegistry();

    // Item-ID → behavior (fast path)
    private static final Map<ResourceLocation, ItemHeatBehavior> BY_ITEM = new HashMap<>();
    // Tag-based entries (checked only if BY_ITEM misses)
    private static final List<ItemHeatBehavior> BY_TAG = new CopyOnWriteArrayList<>();

    private ItemHeatBehaviorRegistry() {
        super(GSON, DIR);
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loaded,
                         ResourceManager manager, ProfilerFiller profiler) {
        BY_ITEM.clear();
        BY_TAG.clear();
        int ok = 0, fail = 0;

        for (var entry : loaded.entrySet()) {
            try {
                var root = entry.getValue();
                // Support both single object and array of objects in one file
                if (root.isJsonArray()) {
                    for (var el : root.getAsJsonArray()) {
                        parseOne(el.getAsJsonObject());
                        ok++;
                    }
                } else {
                    parseOne(root.getAsJsonObject());
                    ok++;
                }
            } catch (Exception e) {
                LOGGER.error("[ItemHeatBehavior] Failed to load '{}': {}",
                        entry.getKey(), e.getMessage());
                fail++;
            }
        }
        LOGGER.info("[Misanthrope Core] Loaded {} item heat behaviors ({} failed).", ok, fail);
    }

    private static void parseOne(JsonObject j) {
        ResourceLocation itemId = j.has("item") && !j.get("item").isJsonNull()
                ? new ResourceLocation(j.get("item").getAsString()) : null;
        ResourceLocation tagId = j.has("item_tag") && !j.get("item_tag").isJsonNull()
                ? new ResourceLocation(j.get("item_tag").getAsString()) : null;
        if (itemId == null && tagId == null)
            throw new IllegalArgumentException("item_heat_behavior must specify 'item' or 'item_tag'");

        var type = ItemHeatBehavior.BehaviorType.valueOf(
                j.has("behavior_type") ? j.get("behavior_type").getAsString() : "GENERIC");

        // ── Cooking chain ──────────────────────────────────────────────────────
        var cookResult = rl(j, "cook_result");
        double cookMin = d(j, "cook_min_celsius", 100.0);
        int cookTicks = i(j, "cook_ticks", 600);

        var burnResult = rl(j, "burn_result");
        double burnMin = d(j, "burn_min_celsius", 220.0);
        int burnTicks = i(j, "burn_ticks", 200);

        var charResult = rl(j, "char_result");
        double charMin = d(j, "char_min_celsius", 400.0);
        int charTicks = i(j, "char_ticks", 100);

        // ── Freezing chain ─────────────────────────────────────────────────────
        var freezeResult = rl(j, "freeze_result");
        double freezeMax = d(j, "freeze_max_celsius", 0.0);
        int freezeTicks = i(j, "freeze_ticks", 1200);

        // ── Rot rates ──────────────────────────────────────────────────────────
        double rotCold = d(j, "rot_rate_cold_multiplier", 0.25);
        double rotFrozen = d(j, "rot_rate_frozen_multiplier", 0.0);

        // ── Damage overrides (NaN = use default) ──────────────────────────────
        double dmgVH = d(j, "damage_per_second_very_hot", Double.NaN);
        double dmgRH = d(j, "damage_per_second_red_hot", Double.NaN);
        double dmgOH = d(j, "damage_per_second_orange_hot", Double.NaN);
        double dmgYH = d(j, "damage_per_second_yellow_hot", Double.NaN);
        double dmgWH = d(j, "damage_per_second_white_hot", Double.NaN);
        double dmgBH = d(j, "damage_per_second_blue_hot", Double.NaN);
        double dmgDF = d(j, "damage_per_second_deep_frozen", Double.NaN);

        var behavior = new ItemHeatBehavior(
                itemId, tagId, type,
                cookResult, cookMin, cookTicks,
                burnResult, burnMin, burnTicks,
                charResult, charMin, charTicks,
                freezeResult, freezeMax, freezeTicks,
                rotCold, rotFrozen,
                dmgVH, dmgRH, dmgOH, dmgYH, dmgWH, dmgBH, dmgDF
        );

        if (itemId != null) BY_ITEM.put(itemId, behavior);
        else BY_TAG.add(behavior);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Look up the heat behavior for the given item stack.
     * Returns null if no behavior is registered (caller uses generic defaults).
     */
    @Nullable
    public static ItemHeatBehavior get(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null) {
            var exact = BY_ITEM.get(id);
            if (exact != null) return exact;
        }
        // Tag scan
        for (var behavior : BY_TAG) {
            if (behavior.itemTagId() != null) {
                var tag = net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.ITEM, behavior.itemTagId());
                if (stack.is(tag)) return behavior;
            }
        }
        return null;
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    @Nullable
    private static ResourceLocation rl(JsonObject j, String key) {
        if (!j.has(key) || j.get(key).isJsonNull()) return null;
        return new ResourceLocation(j.get(key).getAsString());
    }

    private static double d(JsonObject j, String key, double def) {
        if (!j.has(key) || j.get(key).isJsonNull()) return def;
        try {
            return j.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    private static int i(JsonObject j, String key, int def) {
        if (!j.has(key) || j.get(key).isJsonNull()) return def;
        try {
            return j.get(key).getAsInt();
        } catch (Exception e) {
            return def;
        }
    }
}
