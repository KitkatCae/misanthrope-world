package exp.CCnewmods.misanthrope_world.physics.burned;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps food and cookable items to their burned/charred variants.
 * <p>
 * ── JSON format ────────────────────────────────────────────────────────────────
 * data/misanthrope_core/thermal/burned_food/<n>.json
 * <p>
 * Single object or array:
 * {
 * "input":              "minecraft:beef",        // OR "input_tag": "forge:raw_meat"
 * <p>
 * // Temperature above which the item starts burning (replaces with burned_item).
 * // Should be above the normal cooking temperature for that item.
 * "burn_threshold_celsius":  220.0,
 * <p>
 * // Temperature above which the item is completely charred (inedible).
 * // Replaced with charred_item. If omitted, defaults to burn_threshold + 150.
 * "char_threshold_celsius":  400.0,
 * <p>
 * // Item to replace with when burning. If omitted, defaults to charcoal.
 * "burned_item":        "farmersdelight:burnt_stew",  // optional
 * <p>
 * // Item to replace with when charred. Always inedible.
 * // If omitted, defaults to charcoal (edible charcoal from Misanthrope).
 * "charred_item":       "minecraft:charcoal"          // optional
 * }
 * <p>
 * ── Integration ────────────────────────────────────────────────────────────────
 * Called by FurnaceRecipeProcessor when an item's temperature exceeds its cook
 * output temperature (i.e. the recipe completed but heat kept rising).
 * Also called by the FD cooking pot mixin when temperature exceeds FD's normal
 * cook range.
 * <p>
 * Items not in this registry don't burn — metals, ceramics, etc. are safe
 * at any temperature within their material limits.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BurnedFoodRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/BurnedFood");
    private static final Gson GSON = new GsonBuilder().create();
    static final BurnedFoodRegistry INSTANCE = new BurnedFoodRegistry();

    private static final Map<ResourceLocation, BurnEntry> BY_ITEM = new ConcurrentHashMap<>();

    private BurnedFoodRegistry() {
        super(GSON, "thermal/burned_food");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loaded,
                         ResourceManager manager, ProfilerFiller profiler) {
        BY_ITEM.clear();
        int ok = 0, fail = 0;
        for (var entry : loaded.entrySet()) {
            try {
                JsonElement el = entry.getValue();
                if (el.isJsonArray()) {
                    for (JsonElement item : el.getAsJsonArray()) {
                        parse(item.getAsJsonObject());
                        ok++;
                    }
                } else {
                    parse(el.getAsJsonObject());
                    ok++;
                }
            } catch (Exception e) {
                LOGGER.error("[BurnedFoodRegistry] Failed to load '{}': {}",
                        entry.getKey(), e.getMessage());
                fail++;
            }
        }
        LOGGER.info("[Misanthrope Core] Loaded {} burned food entries ({} failed).", ok, fail);
    }

    private static void parse(JsonObject j) {
        ResourceLocation inputId = j.has("input")
                ? new ResourceLocation(j.get("input").getAsString()) : null;

        if (inputId == null) return; // tag support deferred — add later if needed

        double burnThresh = j.has("burn_threshold_celsius")
                ? j.get("burn_threshold_celsius").getAsDouble() : 220.0;
        double charThresh = j.has("char_threshold_celsius")
                ? j.get("char_threshold_celsius").getAsDouble() : burnThresh + 150.0;

        ResourceLocation burnedId = j.has("burned_item") && !j.get("burned_item").isJsonNull()
                ? new ResourceLocation(j.get("burned_item").getAsString()) : null;
        ResourceLocation charredId = j.has("charred_item") && !j.get("charred_item").isJsonNull()
                ? new ResourceLocation(j.get("charred_item").getAsString()) : null;

        BY_ITEM.put(inputId, new BurnEntry(inputId, burnThresh, charThresh, burnedId, charredId));
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Nullable
    public static BurnEntry get(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null ? BY_ITEM.get(id) : null;
    }

    public static boolean canBurn(ItemStack stack) {
        return get(stack) != null;
    }

    /**
     * Check if a stack should be replaced due to burning, and return the
     * replacement. Returns the original stack if no burning occurs.
     *
     * @param stack       the item being cooked
     * @param tempCelsius its current temperature
     * @return the replacement stack, or the original if no change
     */
    public static ItemStack applyBurning(ItemStack stack, double tempCelsius) {
        BurnEntry entry = get(stack);
        if (entry == null) return stack;

        if (tempCelsius >= entry.charThresholdCelsius()) {
            return entry.buildCharred();
        }
        if (tempCelsius >= entry.burnThresholdCelsius()) {
            return entry.buildBurned();
        }
        return stack;
    }

    // ── BurnEntry record ──────────────────────────────────────────────────────

    public record BurnEntry(
            ResourceLocation inputId,
            double burnThresholdCelsius,
            double charThresholdCelsius,
            @Nullable ResourceLocation burnedItemId,
            @Nullable ResourceLocation charredItemId
    ) {
        public ItemStack buildBurned() {
            if (burnedItemId != null) {
                Item item = ForgeRegistries.ITEMS.getValue(burnedItemId);
                if (item != null && item != Items.AIR) return new ItemStack(item);
            }
            // Default burned = charcoal (smoky but still has uses)
            return new ItemStack(Items.CHARCOAL);
        }

        public ItemStack buildCharred() {
            if (charredItemId != null) {
                Item item = ForgeRegistries.ITEMS.getValue(charredItemId);
                if (item != null && item != Items.AIR) return new ItemStack(item);
            }
            // Default charred = charcoal
            return new ItemStack(Items.CHARCOAL);
        }
    }
}
