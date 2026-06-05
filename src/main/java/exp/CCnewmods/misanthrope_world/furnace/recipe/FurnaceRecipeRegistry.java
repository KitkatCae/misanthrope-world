package exp.CCnewmods.misanthrope_world.furnace.recipe;

import com.google.gson.*;
import exp.CCnewmods.misanthrope_world.furnace.environment.FurnaceEnvironmentSampler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and indexes {@link FurnaceRecipe} definitions from
 * {@code data/misanthrope_core/furnace_recipes/}.
 * <p>
 * ── Indexing ──────────────────────────────────────────────────────────────────
 * Recipes are sorted by priority (descending) at load time.  When matching an
 * item + temperature + gas context, the first matching recipe wins.
 * <p>
 * ── Registration ──────────────────────────────────────────────────────────────
 * The static field INSTANCE is initialised at class load.  The reload listener
 * is registered on the FORGE event bus via {@code @SubscribeEvent}.
 * Call {@code FurnaceRecipeRegistry.register(eventBus)} from your common setup
 * if you need explicit registration — otherwise the annotation handles it.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_core", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FurnaceRecipeRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/FurnaceRecipe");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIR = "recipes/furnace_recipes";

    /**
     * All loaded recipes sorted priority-descending.
     */
    private static final List<FurnaceRecipe> ALL = new ArrayList<>();

    // Static INSTANCE so the @SubscribeEvent can register it
    static final FurnaceRecipeRegistry INSTANCE = new FurnaceRecipeRegistry();

    private FurnaceRecipeRegistry() {
        super(GSON, DIR);
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    // ── Load / parse ──────────────────────────────────────────────────────────

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loaded,
                         ResourceManager manager, ProfilerFiller profiler) {
        ALL.clear();
        int ok = 0, fail = 0;

        for (var entry : loaded.entrySet()) {
            try {
                FurnaceRecipe recipe = parse(entry.getKey(), entry.getValue().getAsJsonObject());
                ALL.add(recipe);
                ok++;
            } catch (Exception e) {
                LOGGER.error("[FurnaceRecipe] Failed to load '{}': {}", entry.getKey(), e.getMessage());
                fail++;
            }
        }

        // Sort priority descending — higher priority checked first
        ALL.sort(Comparator.comparingInt(FurnaceRecipe::priority).reversed());
        LOGGER.info("[Misanthrope Core] Loaded {} furnace recipes ({} failed).", ok, fail);
    }

    private static FurnaceRecipe parse(ResourceLocation id, JsonObject j) {
        // Input — either "input" item ID or "input_tag" tag ID
        ResourceLocation inputItemId = null;
        ResourceLocation inputTagId = null;
        if (j.has("input") && !j.get("input").isJsonNull())
            inputItemId = new ResourceLocation(j.get("input").getAsString());
        if (j.has("input_tag") && !j.get("input_tag").isJsonNull())
            inputTagId = new ResourceLocation(j.get("input_tag").getAsString());
        if (inputItemId == null && inputTagId == null)
            throw new IllegalArgumentException("FurnaceRecipe must specify 'input' or 'input_tag'");

        int inputCount = j.has("input_count") ? j.get("input_count").getAsInt() : 1;

        // Temperature
        double minC = j.get("min_celsius").getAsDouble();
        double maxC = j.has("max_celsius") ? j.get("max_celsius").getAsDouble() : Double.MAX_VALUE;
        int ticks = j.get("ticks_required").getAsInt();

        // Gas constraints
        List<FurnaceRecipe.GasRequirement> requiresGas = new ArrayList<>();
        if (j.has("requires_gas") && j.get("requires_gas").isJsonArray()) {
            for (var el : j.getAsJsonArray("requires_gas")) {
                var obj = el.getAsJsonObject();
                ResourceLocation gasId = new ResourceLocation(obj.get("gas").getAsString());
                float minMbar = obj.has("min_mbar") ? obj.get("min_mbar").getAsFloat() : 0f;
                requiresGas.add(new FurnaceRecipe.GasRequirement(gasId, minMbar));
            }
        }
        List<ResourceLocation> forbidsGas = new ArrayList<>();
        if (j.has("forbids_gas") && j.get("forbids_gas").isJsonArray()) {
            for (var el : j.getAsJsonArray("forbids_gas")) {
                var obj = el.getAsJsonObject();
                forbidsGas.add(new ResourceLocation(obj.get("gas").getAsString()));
            }
        }

        // Output
        ResourceLocation outputItemId = null;
        ResourceLocation outputFluidId = null;
        if (j.has("output_item") && !j.get("output_item").isJsonNull())
            outputItemId = new ResourceLocation(j.get("output_item").getAsString());
        if (j.has("output_fluid") && !j.get("output_fluid").isJsonNull())
            outputFluidId = new ResourceLocation(j.get("output_fluid").getAsString());
        int outputCount = j.has("output_count") ? j.get("output_count").getAsInt() : 1;

        // Byproduct
        ResourceLocation byproductId = null;
        float byproductChance = 0f;
        if (j.has("byproduct_item") && !j.get("byproduct_item").isJsonNull()) {
            byproductId = new ResourceLocation(j.get("byproduct_item").getAsString());
            byproductChance = j.has("byproduct_chance") ? j.get("byproduct_chance").getAsFloat() : 0f;
        }

        // Gas emissions (list)
        List<FurnaceRecipe.GasEmission> emitsGasList = new ArrayList<>();
        if (j.has("emits_gas")) {
            var emitElem = j.get("emits_gas");
            if (emitElem.isJsonArray()) {
                for (var el : emitElem.getAsJsonArray()) {
                    var obj = el.getAsJsonObject();
                    ResourceLocation gid = new ResourceLocation(obj.get("gas").getAsString());
                    float mb = obj.has("mbar") ? obj.get("mbar").getAsFloat() : 10f;
                    emitsGasList.add(new FurnaceRecipe.GasEmission(gid, mb));
                }
            } else if (emitElem.isJsonObject()) {
                // Single-object shorthand
                var obj = emitElem.getAsJsonObject();
                ResourceLocation gid = new ResourceLocation(obj.get("gas").getAsString());
                float mb = obj.has("mbar") ? obj.get("mbar").getAsFloat() : 10f;
                emitsGasList.add(new FurnaceRecipe.GasEmission(gid, mb));
            }
        }

        // Furnace type + priority
        String furnaceType = j.has("furnace_type") ? j.get("furnace_type").getAsString() : null;
        int priority = j.has("priority") ? j.get("priority").getAsInt() : 0;

        return new FurnaceRecipe(
                id,
                inputItemId, inputTagId, inputCount,
                minC, maxC, ticks,
                requiresGas, forbidsGas,
                outputItemId, outputFluidId, outputCount,
                byproductId, byproductChance,
                emitsGasList,
                furnaceType,
                priority
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Find the highest-priority recipe that matches the given item, temperature,
     * gas context, and furnace type.
     *
     * @param stack       the item being processed
     * @param celsius     the item's current temperature
     * @param sample      environment sample carrying live GasComposition
     * @param furnaceType e.g. "bloomery", "hearth", "forge", or null for any
     * @return the first matching recipe, or null if none
     */
    @Nullable
    public static FurnaceRecipe findRecipe(ItemStack stack,
                                           double celsius,
                                           FurnaceEnvironmentSampler.Sample sample,
                                           @Nullable String furnaceType) {
        for (FurnaceRecipe r : ALL) {
            if (!r.matchesInput(stack)) continue;
            if (!r.temperatureAllows(celsius)) continue;
            if (!r.gasContextAllows(sample)) continue;
            if (!r.matchesFurnaceType(furnaceType)) continue;
            return r;
        }
        return null;
    }

    /**
     * Returns true if any recipe (regardless of temperature/gas) matches this item.
     * Used for quick filtering to avoid scanning unprocessable items.
     */
    public static boolean hasAnyRecipe(ItemStack stack) {
        for (FurnaceRecipe r : ALL)
            if (r.matchesInput(stack)) return true;
        return false;
    }

    /**
     * All loaded recipes (priority-sorted). Unmodifiable view.
     */
    public static List<FurnaceRecipe> getAll() {
        return Collections.unmodifiableList(ALL);
    }
}
