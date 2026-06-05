package exp.CCnewmods.misanthrope_world.temperature.melt;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ═══════════════════════════════════════════════════════════════════════════════
// MeltData
// ═══════════════════════════════════════════════════════════════════════════════

@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MeltRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/Melt");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIR = "melt_data";

    private static final Map<ResourceLocation, MeltData> BY_ITEM = new ConcurrentHashMap<>();

    MeltRegistry() {
        super(GSON, DIR);
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new MeltRegistry());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loaded,
                         ResourceManager manager, ProfilerFiller profiler) {
        BY_ITEM.clear();
        int ok = 0, fail = 0;
        for (var entry : loaded.entrySet()) {
            try {
                MeltData data = parse(entry.getValue().getAsJsonObject());
                BY_ITEM.put(data.itemId(), data);
                ok++;
            } catch (Exception e) {
                LOGGER.error("Failed to load melt data '{}': {}", entry.getKey(), e.getMessage());
                fail++;
            }
        }
        LOGGER.info("[Misanthrope Core] Loaded {} melt definitions ({} failed).", ok, fail);
    }

    private static MeltData parse(JsonObject j) {
        ResourceLocation itemId = new ResourceLocation(j.get("item").getAsString());
        ResourceLocation fluidId = new ResourceLocation(j.get("fluid").getAsString());
        double meltCelsius = j.get("melt_celsius").getAsDouble();
        int fluidMb = j.get("fluid_mb").getAsInt();

        ResourceLocation byproduct = null;
        float byproductChance = 0f;
        if (j.has("byproduct_item") && !j.get("byproduct_item").isJsonNull()) {
            byproduct = new ResourceLocation(j.get("byproduct_item").getAsString());
            byproductChance = j.has("byproduct_chance")
                    ? j.get("byproduct_chance").getAsFloat() : 0f;
        }

        return new MeltData(itemId, fluidId, meltCelsius, fluidMb, byproduct, byproductChance);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Nullable
    public static MeltData get(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return null;
        return BY_ITEM.get(id);
    }

    @Nullable
    public static MeltData get(ResourceLocation itemId) {
        return BY_ITEM.get(itemId);
    }

    public static boolean hasMeltData(ItemStack stack) {
        return get(stack) != null;
    }
}
