package exp.CCnewmods.misanthrope_world.temperature.storage;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 * Loads {@link ThermalStorageData} from
 * {@code data/misanthrope_core/thermal_storage/<name>.json}.
 * <p>
 * The JSON no longer carries {@code insulation_thickness} or
 * {@code insulation_material_resistance} — those are read from
 * {@link exp.CCnewmods.misanthrope_core.physics.BlockPhysicsRegistry} at
 * runtime via {@link ThermalStorageData#resolveEffectiveR(BlockState)}.
 * Use {@code insulation_r_override} in the JSON to override when the
 * container's walls differ from a full block of the same material.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ThermalStorageRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/ThermalStorage");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIR = "thermal_storage";

    private static final Map<ResourceLocation, ThermalStorageData> BY_BLOCK =
            new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, IDynamicTemperatureProvider> DYNAMIC_PROVIDERS =
            new ConcurrentHashMap<>();

    ThermalStorageRegistry() {
        super(GSON, DIR);
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ThermalStorageRegistry());
    }

    // ── Load / parse ──────────────────────────────────────────────────────────

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loaded,
                         ResourceManager manager, ProfilerFiller profiler) {
        BY_BLOCK.clear();
        int ok = 0, fail = 0;

        for (var entry : loaded.entrySet()) {
            try {
                ThermalStorageData data = parse(entry.getValue().getAsJsonObject());
                BY_BLOCK.put(data.blockId(), data);
                ok++;
            } catch (Exception e) {
                LOGGER.error("[ThermalStorage] Failed to load '{}': {}",
                        entry.getKey(), e.getMessage());
                fail++;
            }
        }
        LOGGER.info("[Misanthrope Core] Loaded {} thermal storage definitions ({} failed).",
                ok, fail);
    }

    private static ThermalStorageData parse(JsonObject j) {
        ResourceLocation blockId = new ResourceLocation(j.get("block").getAsString());

        // Optional static internal temperature
        Double staticTemp = null;
        if (j.has("internal_temperature") && !j.get("internal_temperature").isJsonNull())
            staticTemp = j.get("internal_temperature").getAsDouble();

        // Optional dynamic provider id
        ResourceLocation providerId = null;
        if (j.has("dynamic_temperature_provider")
                && !j.get("dynamic_temperature_provider").isJsonNull())
            providerId = new ResourceLocation(
                    j.get("dynamic_temperature_provider").getAsString());

        // New: insulation_r_override (NaN = read from BlockPhysicsRegistry)
        double rOverride = Double.NaN;
        if (j.has("insulation_r_override") && !j.get("insulation_r_override").isJsonNull())
            rOverride = j.get("insulation_r_override").getAsDouble();

        double rMultiplier = 1.0;
        if (j.has("insulation_multiplier") && !j.get("insulation_multiplier").isJsonNull())
            rMultiplier = j.get("insulation_multiplier").getAsDouble();

        // Legacy support: if old-format keys are present, derive an r_override from them
        // so existing thermal_storage JSON files keep working without edits.
        if (Double.isNaN(rOverride)
                && j.has("insulation_thickness") && j.has("insulation_material_resistance")) {
            double thickness = j.get("insulation_thickness").getAsDouble();
            double resistance = j.get("insulation_material_resistance").getAsDouble();
            // Old formula: effectiveR = thickness × resistance
            // Map to new single override so behaviour is identical.
            rOverride = thickness * resistance;
            LOGGER.debug("[ThermalStorage] '{}' uses legacy insulation fields — " +
                    "consider migrating to insulation_r_override.", blockId);
        }

        boolean active = j.has("active") && j.get("active").getAsBoolean();
        boolean nearby = j.has("affects_nearby_blocks")
                && j.get("affects_nearby_blocks").getAsBoolean();

        return new ThermalStorageData(blockId, staticTemp, providerId,
                rOverride, rMultiplier, active, nearby);
    }

    // ── Dynamic provider registration ─────────────────────────────────────────

    /**
     * Register a dynamic temperature provider.
     * <p>
     * Example (call from FMLCommonSetupEvent / block entity static init):
     * <pre>
     * ThermalStorageRegistry.registerDynamicProvider(
     *     new ResourceLocation("misanthrope_core", "crucible"),
     *     be -> be instanceof CrucibleBlockEntity c ? c.getCurrentCelsius() : Double.NaN
     * );
     * </pre>
     */
    public static void registerDynamicProvider(ResourceLocation id,
                                               IDynamicTemperatureProvider provider) {
        DYNAMIC_PROVIDERS.put(id, provider);
        LOGGER.debug("[Misanthrope Core] Registered dynamic temp provider: {}", id);
    }

    @Nullable
    static IDynamicTemperatureProvider getDynamicProvider(ResourceLocation id) {
        return DYNAMIC_PROVIDERS.get(id);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Nullable
    public static ThermalStorageData getForBlock(BlockState state) {
        var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) return null;
        return BY_BLOCK.get(id);
    }

    @Nullable
    public static ThermalStorageData getForBlockPos(Level level, BlockPos pos) {
        return getForBlock(level.getBlockState(pos));
    }

    public static boolean hasData(BlockState state) {
        return getForBlock(state) != null;
    }
}
