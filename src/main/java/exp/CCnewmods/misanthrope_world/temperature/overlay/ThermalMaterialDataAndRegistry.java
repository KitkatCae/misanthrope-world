package exp.CCnewmods.misanthrope_world.temperature.overlay;

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
import java.util.concurrent.ConcurrentHashMap;

// ═══════════════════════════════════════════════════════════════════════════════
// ThermalMaterialData
// ═══════════════════════════════════════════════════════════════════════════════

@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
class ThermalMaterialRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/ThermalMaterial");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIR = "thermal_materials";

    private static final Map<ResourceLocation, ThermalMaterialData> BY_ID =
            new ConcurrentHashMap<>();

    /**
     * Cache: item registry ID → material data. Rebuilt after each reload.
     */
    private static final Map<ResourceLocation, ThermalMaterialData> ITEM_CACHE =
            new ConcurrentHashMap<>();

    ThermalMaterialRegistry() {
        super(GSON, DIR);
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ThermalMaterialRegistry());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loaded,
                         ResourceManager manager, ProfilerFiller profiler) {
        BY_ID.clear();
        ITEM_CACHE.clear();
        int ok = 0, fail = 0;

        for (var entry : loaded.entrySet()) {
            try {
                ThermalMaterialData data = parse(entry.getValue().getAsJsonObject());
                BY_ID.put(data.materialId(), data);
                ok++;
            } catch (Exception e) {
                LOGGER.error("Failed to load thermal material '{}': {}",
                        entry.getKey(), e.getMessage());
                fail++;
            }
        }

        LOGGER.info("[Misanthrope Core] Loaded {} thermal material definitions ({} failed).",
                ok, fail);
    }

    private static ThermalMaterialData parse(JsonObject j) {
        ResourceLocation id = new ResourceLocation(j.get("material_id").getAsString());

        List<ThermalMaterialData.TintStage> heat = parseStages(j.getAsJsonArray("heat_stages"));
        List<ThermalMaterialData.TintStage> cold = j.has("cold_stages")
                ? parseStages(j.getAsJsonArray("cold_stages"))
                : List.of();

        return new ThermalMaterialData(id, heat, cold);
    }

    private static List<ThermalMaterialData.TintStage> parseStages(JsonArray arr) {
        List<ThermalMaterialData.TintStage> stages = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            double minC = obj.get("min_celsius").getAsDouble();
            // Parse hex ARGB string: "0xAARRGGBB" or just an int
            int tint;
            JsonElement tintEl = obj.get("tint_argb");
            if (tintEl.isJsonPrimitive() && tintEl.getAsJsonPrimitive().isString()) {
                tint = (int) Long.parseLong(
                        tintEl.getAsString().replace("0x", "").replace("0X", ""), 16);
            } else {
                tint = tintEl.getAsInt();
            }
            stages.add(new ThermalMaterialData.TintStage(minC, tint));
        }
        // Sort ascending by minCelsius
        stages.sort(Comparator.comparingDouble(ThermalMaterialData.TintStage::minCelsius));
        return List.copyOf(stages);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Nullable
    public static ThermalMaterialData getForItem(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;

        // Check cache first
        if (ITEM_CACHE.containsKey(itemId)) return ITEM_CACHE.get(itemId);

        // Scan materials: check if item is in #misanthrope_core:thermal_material/<path>
        for (var entry : BY_ID.entrySet()) {
            ResourceLocation matId = entry.getKey();
            // Tag: misanthrope_core:thermal_material/<matId.path>
            ResourceLocation tagId = new ResourceLocation(
                    "misanthrope_core", "thermal_material/" + matId.getPath());
            boolean inTag = stack.is(
                    net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId));
            if (inTag) {
                ITEM_CACHE.put(itemId, entry.getValue());
                return entry.getValue();
            }
        }

        ITEM_CACHE.put(itemId, null); // cache negative result
        return null;
    }

    @Nullable
    public static ThermalMaterialData getById(ResourceLocation id) {
        return BY_ID.get(id);
    }
}
