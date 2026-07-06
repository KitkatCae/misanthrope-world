package exp.CCnewmods.misanthrope_world.physics.pressure.creature;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads {@code data/<namespace>/creature_pressure/<file>.json} from every
 * data-pack and mod JAR on the server, building a registry of
 * {@link CreaturePressureProfile} indexed by entity registry key.
 *
 * <h3>JSON schema</h3>
 * <pre>{@code
 * {
 *   "entity": "minecraft:cod",
 *
 *   // Crush (over-pressure) — omit or set very high to disable
 *   "crush_threshold_mbar":              2000.0,
 *   "crush_instant_kill_threshold_mbar": 8000.0,   // optional, default MAX_VALUE
 *   "crush_damage_per_interval":         2.0,       // half-hearts, default 2
 *   "crush_instant_kill_damage":         999.0,     // default 999
 *
 *   // Vacuum (under-pressure) — omit or set to -1 to disable
 *   "vacuum_threshold_mbar":              200.0,
 *   "vacuum_instant_kill_threshold_mbar": 50.0,    // optional, default -1
 *   "vacuum_damage_per_interval":         1.0,     // half-hearts, default 1
 *   "vacuum_instant_kill_damage":         999.0,   // default 999
 *
 *   // Timing
 *   "damage_interval_ticks": 40,                   // default 40 (2 s)
 *
 *   // Armour interaction
 *   "respect_pressure_armour": true                // default true
 * }
 * }</pre>
 *
 * All fields except {@code entity} are optional. Multiple JSON files may define
 * the same entity — last writer wins (data-pack override semantics via Forge's
 * reload listener merge map).
 */
public final class CreaturePressureLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisWorld/CreaturePressure");
    private static final Gson   GSON   = new Gson();

    public static final CreaturePressureLoader INSTANCE = new CreaturePressureLoader();

    // entity resource-key string → profile
    private volatile Map<String, CreaturePressureProfile> profiles = Collections.emptyMap();

    private CreaturePressureLoader() {
        super(GSON, "creature_pressure");
    }

    // ── SimpleJsonResourceReloadListener ─────────────────────────────────────

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> objects,
            ResourceManager manager,
            ProfilerFiller profiler) {

        Map<String, CreaturePressureProfile> loaded = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try {
                CreaturePressureProfile profile = parse(entry.getValue().getAsJsonObject());
                if (profile != null) {
                    loaded.put(profile.entityId, profile);
                    LOGGER.debug("[MisWorld/CreaturePressure] Loaded profile for {} from {}",
                            profile.entityId, fileId);
                }
            } catch (JsonParseException | IllegalStateException e) {
                LOGGER.error("[MisWorld/CreaturePressure] Failed to parse {}: {}", fileId, e.getMessage());
            }
        }

        this.profiles = Collections.unmodifiableMap(loaded);
        LOGGER.info("[MisWorld/CreaturePressure] Loaded {} creature pressure profile(s).", loaded.size());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the pressure profile for the given entity registry key string
     * (e.g. {@code "minecraft:cod"}), or {@code null} if no profile exists.
     */
    public CreaturePressureProfile get(String entityId) {
        return profiles.get(entityId);
    }

    /** Returns an unmodifiable view of all registered profiles. */
    public Map<String, CreaturePressureProfile> all() {
        return profiles;
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private static CreaturePressureProfile parse(JsonObject obj) {
        if (!obj.has("entity")) {
            LOGGER.warn("[MisWorld/CreaturePressure] Skipping entry without 'entity' field.");
            return null;
        }

        String entityId = obj.get("entity").getAsString().trim();
        if (entityId.isEmpty()) {
            LOGGER.warn("[MisWorld/CreaturePressure] Skipping entry with empty 'entity' field.");
            return null;
        }

        // ── Crush ─────────────────────────────────────────────────────────────
        float crushThreshold    = getFloat(obj, "crush_threshold_mbar",            Float.MAX_VALUE);
        float crushIKThreshold  = getFloat(obj, "crush_instant_kill_threshold_mbar", Float.MAX_VALUE);
        float crushDmg          = getFloat(obj, "crush_damage_per_interval",       2.0f);
        float crushIKDmg        = getFloat(obj, "crush_instant_kill_damage",       999.0f);

        // ── Vacuum ────────────────────────────────────────────────────────────
        float vacuumThreshold   = getFloat(obj, "vacuum_threshold_mbar",           -1f);
        float vacuumIKThreshold = getFloat(obj, "vacuum_instant_kill_threshold_mbar", -1f);
        float vacuumDmg         = getFloat(obj, "vacuum_damage_per_interval",      1.0f);
        float vacuumIKDmg       = getFloat(obj, "vacuum_instant_kill_damage",      999.0f);

        // ── Timing ────────────────────────────────────────────────────────────
        int intervalTicks = getInt(obj, "damage_interval_ticks", 40);

        // ── Armour ────────────────────────────────────────────────────────────
        boolean respectArmour = getBool(obj, "respect_pressure_armour", true);

        return new CreaturePressureProfile(
                entityId,
                crushThreshold,   crushIKThreshold,
                vacuumThreshold,  vacuumIKThreshold,
                intervalTicks,
                crushDmg,         vacuumDmg,
                crushIKDmg,       vacuumIKDmg,
                respectArmour);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float getFloat(JsonObject obj, String key, float def) {
        return obj.has(key) ? obj.get(key).getAsFloat() : def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }
}
