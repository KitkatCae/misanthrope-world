package exp.CCnewmods.misanthrope_world.physics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads {@link BlockPhysicsData} from
 * {@code data/<ns>/material_properties/<path>.json}.
 *
 * <p>This is the single source of truth for all block physics. It replaces
 * {@code InsulationRegistry} (now a thin shim that delegates here) and
 * eventually replaces {@code HeatSourceRegistry} for blocks that have full
 * material-properties definitions.
 *
 * <h3>Lookup priority</h3>
 * <ol>
 *   <li>Exact block-ID match in {@code BY_BLOCK}</li>
 *   <li>Block-tag match in {@code BY_TAG} (first match in load order)</li>
 *   <li>Solid-block heuristic → {@link BlockPhysicsData#GENERIC_SOLID}</li>
 *   <li>Air / non-solid fallback → {@link BlockPhysicsData#AIR}</li>
 * </ol>
 *
 * <h3>JSON format (material_properties/)</h3>
 * Every field is optional except {@code "block"} or {@code "tag"}.
 * Arrays of objects in a single file are supported for convenience.
 * Ousia-related keys ({@code ousia_affinity}, {@code ousia_capacity}, etc.) are
 * silently ignored — they will be loaded by the forthcoming ousia mod.
 *
 * <h3>Legacy insulation/ files</h3>
 * The old {@code thermal/insulation/} JSON files are still loaded by the
 * legacy {@code InsulationRegistry} shim on the old path so that existing
 * datapacks keep working. Any block defined in <em>both</em> places uses the
 * {@code material_properties/} definition (higher priority).
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlockPhysicsRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER =
            LogManager.getLogger("MisanthropeCore/BlockPhysics");
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Singleton — loaded via {@code INSTANCE.getClass()} in mod init.
     */
    public static final BlockPhysicsRegistry INSTANCE = new BlockPhysicsRegistry();

    // ── Loaded data ───────────────────────────────────────────────────────────

    // Block registry ID → data
    private static final Map<ResourceLocation, BlockPhysicsData> BY_BLOCK =
            new ConcurrentHashMap<>();
    // Tag key → data  (LinkedHashMap preserves file-load order for priority)
    private static final Map<TagKey<Block>, BlockPhysicsData> BY_TAG =
            Collections.synchronizedMap(new LinkedHashMap<>());

    // ─────────────────────────────────────────────────────────────────────────

    private BlockPhysicsRegistry() {
        super(GSON, "material_properties");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loaded,
                         ResourceManager manager, ProfilerFiller profiler) {
        BY_BLOCK.clear();
        BY_TAG.clear();

        int ok = 0, fail = 0;
        for (var entry : loaded.entrySet()) {
            try {
                JsonElement el = entry.getValue();
                if (el.isJsonArray()) {
                    for (JsonElement item : el.getAsJsonArray()) {
                        parseAndStore(item.getAsJsonObject());
                        ok++;
                    }
                } else {
                    parseAndStore(el.getAsJsonObject());
                    ok++;
                }
            } catch (Exception e) {
                LOGGER.error("[BlockPhysicsRegistry] Failed to load '{}': {}",
                        entry.getKey(), e.getMessage());
                fail++;
            }
        }
        LOGGER.info("[Misanthrope Core] Loaded {} material_properties definitions ({} failed).",
                ok, fail);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static void parseAndStore(JsonObject j) {
        BlockPhysicsData data = parse(j);
        if (j.has("block") && !j.get("block").isJsonNull()) {
            BY_BLOCK.put(new ResourceLocation(j.get("block").getAsString()), data);
        } else if (j.has("tag") && !j.get("tag").isJsonNull()) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK,
                    new ResourceLocation(j.get("tag").getAsString()));
            BY_TAG.put(tag, data);
        } else {
            throw new IllegalArgumentException(
                    "material_properties entry must specify 'block' or 'tag'");
        }
    }

    /**
     * Parses one JSON object into a {@link BlockPhysicsData}.
     * Unknown keys are silently ignored (forward-compatible, ousia keys ignored).
     */
    public static BlockPhysicsData parse(JsonObject j) {
        BlockPhysicsData.Builder b = BlockPhysicsData.builder();

        // ── Core thermal ──────────────────────────────────────────────────
        if (j.has("thermal_mass")) b.thermalMass(j.get("thermal_mass").getAsDouble());
        if (j.has("conductivity")) b.conductivity(j.get("conductivity").getAsDouble());
        if (j.has("insulation_r")) b.insulationR(j.get("insulation_r").getAsDouble());
        if (j.has("dissipation_rate")) b.dissipationRate(j.get("dissipation_rate").getAsDouble());
        if (j.has("is_airtight")) b.isAirtight(j.get("is_airtight").getAsBoolean());
        if (j.has("porousness")) b.porousness(j.get("porousness").getAsDouble());
        if (j.has("adhesion")) b.adhesion(j.get("adhesion").getAsDouble());
        if (j.has("thickness_fraction")) b.thicknessFraction(j.get("thickness_fraction").getAsDouble());
        if (j.has("electrically_conductive"))
            b.electricallyConductive(j.get("electrically_conductive").getAsBoolean());

        // ── Thermal cracking ──────────────────────────────────────────────
        if (j.has("thermal_crack_threshold"))
            b.thermalCrackThreshold(j.get("thermal_crack_threshold").getAsDouble());
        if (j.has("thermal_crack_rate"))
            b.thermalCrackRate(j.get("thermal_crack_rate").getAsDouble());

        // ── Phase transitions ─────────────────────────────────────────────
        List<PhaseTransition> transitions = new ArrayList<>();
        addLegacyPhaseTransition(j, "on_melt", PhaseTransitionTrigger.ON_MELT, transitions);
        addLegacyPhaseTransition(j, "on_freeze", PhaseTransitionTrigger.ON_FREEZE, transitions);
        addLegacyPhaseTransition(j, "on_fire", PhaseTransitionTrigger.ON_FIRE, transitions);
        addLegacyPhaseTransition(j, "on_char", PhaseTransitionTrigger.ON_CHAR, transitions);
        addLegacyPhaseTransition(j, "on_heat_above", PhaseTransitionTrigger.ON_HEAT_ABOVE, transitions);
        addLegacyPhaseTransition(j, "on_cool_below", PhaseTransitionTrigger.ON_COOL_BELOW, transitions);
        addLegacyPhaseTransition(j, "on_soften", PhaseTransitionTrigger.ON_SOFTEN, transitions);
        addLegacyPhaseTransition(j, "on_water_contact", PhaseTransitionTrigger.ON_WATER_CONTACT, transitions);
        addLegacyPhaseTransition(j, "on_sublimate", PhaseTransitionTrigger.ON_SUBLIMATE, transitions);
        addLegacyPhaseTransition(j, "on_fire_smelt", PhaseTransitionTrigger.ON_FIRE_SMELT, transitions);
        addLegacyPhaseTransition(j, "on_exceed_temp", PhaseTransitionTrigger.ON_EXCEED_TEMP, transitions);
        addLegacyPhaseTransition(j, "on_impact", PhaseTransitionTrigger.ON_IMPACT, transitions);
        addLegacyPhaseTransition(j, "on_ignite", PhaseTransitionTrigger.ON_IGNITE, transitions);
        // Also handle the shorthand melt_temp / freeze_temp top-level keys
        if (j.has("melt_temp") && !j.has("on_melt")) {
            double t = j.get("melt_temp").getAsDouble();
            transitions.add(new PhaseTransition(PhaseTransitionTrigger.ON_MELT,
                    t, Double.NaN, new ResourceLocation("minecraft:lava"),
                    null, false, null, null, false, null));
        }
        if (j.has("freeze_temp") && !j.has("on_freeze")) {
            double t = j.get("freeze_temp").getAsDouble();
            transitions.add(new PhaseTransition(PhaseTransitionTrigger.ON_FREEZE,
                    t, Double.NaN, new ResourceLocation("minecraft:water"),
                    null, false, null, null, true, null));
        }
        if (!transitions.isEmpty()) b.phaseTransitions = transitions;

        // ── Heat emission ─────────────────────────────────────────────────
        if (j.has("emission") && j.get("emission").isJsonObject()) {
            b.emission(parseEmission(j.getAsJsonObject("emission")));
        }

        // ── Off-gassing ───────────────────────────────────────────────────
        if (j.has("thermal_offgas") && j.get("thermal_offgas").isJsonObject()) {
            b.thermalOffGas = parseOffGas(j.getAsJsonObject("thermal_offgas"), true);
        }
        if (j.has("ambient_offgas") && j.get("ambient_offgas").isJsonObject()) {
            b.ambientOffGas = parseOffGas(j.getAsJsonObject("ambient_offgas"), false);
        }

        // ── Radiation ─────────────────────────────────────────────────────
        if (j.has("radiation") && j.get("radiation").isJsonObject()) {
            JsonObject r = j.getAsJsonObject("radiation");
            b.radiation = new RadiationData(
                    gs(r, "type", "gamma"),
                    gf(r, "intensity", 0.5f),
                    gs(r, "half_life", "geological"),
                    j.has("color") ? r.get("color").getAsString() : null,
                    gf(r, "cancer_risk", 0f)
            );
        }

        // ── Light emission ────────────────────────────────────────────────
        if (j.has("light_emission") && j.get("light_emission").isJsonObject()) {
            JsonObject le = j.getAsJsonObject("light_emission");
            b.lightEmission = new LightEmissionData(
                    gs(le, "type", "static"),
                    gi(le, "level", 0),
                    le.has("color") ? le.get("color").getAsString() : null,
                    le.has("source") ? le.get("source").getAsString() : null
            );
        }

        // ── Structural ────────────────────────────────────────────────────
        if (j.has("structural") && j.get("structural").isJsonObject()) {
            b.structural(parseStructural(j.getAsJsonObject("structural")));
        }

        // ── Flags ─────────────────────────────────────────────────────────
        if (j.has("biological")) b.biological = gb(j, "biological");
        if (j.has("passive_heat_output")) b.passiveHeatOutput = gb(j, "passive_heat_output");
        if (j.has("heat_decoupled")) b.heatDecoupled = gb(j, "heat_decoupled");
        if (j.has("lava_immune")) b.lavaImmune = gb(j, "lava_immune");
        if (j.has("lava_contact_stable")) b.lavaContactStable = gb(j, "lava_contact_stable");
        if (j.has("fatigue_immune")) b.fatiguImmune = gb(j, "fatigue_immune");
        if (j.has("wither_immune")) b.witherImmune = gb(j, "wither_immune");
        if (j.has("glitch")) b.glitch = gb(j, "glitch");
        if (j.has("self_repair_rate")) b.selfRepairRate = j.get("self_repair_rate").getAsDouble();
        if (j.has("decay_into") && !j.get("decay_into").isJsonNull())
            b.decayInto = new ResourceLocation(j.get("decay_into").getAsString());
        if (j.has("custom_behavior"))
            b.customBehavior = j.get("custom_behavior").getAsString();
        if (j.has("note"))
            b.note = j.get("note").getAsString();
        if (j.has("disambiguation"))
            b.disambiguation = j.get("disambiguation").getAsString();
        if (j.has("stub"))
            b.stub = gb(j, "stub");

        // Silently ignore: ousia_affinity, ousia_capacity, ousia_state,
        // ousia_suppression_*, and any other unknown keys.

        return b.build();
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    /**
     * Converts a legacy on_X block into a PhaseTransition and appends it.
     */
    private static void addLegacyPhaseTransition(JsonObject j, String key,
                                                 PhaseTransitionTrigger trigger, List<PhaseTransition> out) {
        if (!j.has(key) || j.get(key).isJsonNull()) return;
        JsonObject o = j.getAsJsonObject(key);
        double temp = gd(o, "temp", Double.NaN);
        ResourceLocation result = null;
        if (o.has("result") && !o.get("result").isJsonNull())
            result = new ResourceLocation(o.get("result").getAsString());
        String releases = o.has("releases") ? o.get("releases").getAsString() : null;
        boolean reqO2 = gb(o, "requires_oxygen");
        String note = o.has("note") ? o.get("note").getAsString() : null;
        out.add(new PhaseTransition(trigger, temp, Double.NaN,
                result, null, reqO2, null, releases, false, note));
    }

    private static HeatEmission parseEmission(JsonObject e) {
        return new HeatEmission(
                gd(e, "peak_celsius", 400.0),
                gd(e, "watts_per_block", 350.0),
                gb(e, "requires_oxygen"),
                gb(e, "is_induction"),
                gb(e, "is_continuous"),
                e.has("active_when_property") ? e.get("active_when_property").getAsString() : null,
                e.has("active_when_value") ? e.get("active_when_value").getAsString() : null,
                gd(e, "oxygen_consumption_rate", 0.0),
                gd(e, "co2_emission_rate", 0.0),
                gs(e, "heat_profile_type", "constant"),
                gd(e, "structural_stress_above", Double.MAX_VALUE),
                gb(e, "induce_pressure_check")
        );
    }

    private static OffGas parseOffGas(JsonObject o, boolean isThermal) {
        return new OffGas(
                gs(o, "gas", "mge:carbon_dioxide"),
                gf(o, "rate_mbar_per_tick", 0.01f),
                gb(o, "rate_scales_with_temp"),
                gd(o, "threshold_celsius", isThermal ? 100.0 : Double.NaN),
                gd(o, "sustained_above_celsius", Double.NaN),
                gi(o, "sustained_ticks", 200),
                o.has("sustained_result") && !o.get("sustained_result").isJsonNull()
                        ? new ResourceLocation(o.get("sustained_result").getAsString()) : null,
                gb(o, "requires_oxygen"),
                o.has("condition") && !o.get("condition").isJsonNull()
                        ? o.get("condition").getAsString() : null,
                gd(o, "stops_above_celsius", Double.NaN)
        );
    }

    private static StructuralData parseStructural(JsonObject s) {
        // Strength retention curve
        List<StrengthPoint> curve = new ArrayList<>();
        if (s.has("strength_retention_curve") && s.get("strength_retention_curve").isJsonArray()) {
            for (JsonElement el : s.getAsJsonArray("strength_retention_curve")) {
                JsonObject sp = el.getAsJsonObject();
                curve.add(new StrengthPoint(
                        gd(sp, "celsius", 20.0),
                        gd(sp, "fraction", 1.0)));
            }
        } else {
            // Default: no temperature weakening
            curve.add(new StrengthPoint(20.0, 1.0));
            curve.add(new StrengthPoint(1200.0, 0.05));
        }

        // Lattice collapse sub-config
        LatticeCollapseData lc = null;
        if (s.has("lattice_collapse") && s.get("lattice_collapse").isJsonObject()) {
            JsonObject l = s.getAsJsonObject("lattice_collapse");
            lc = new LatticeCollapseData(
                    gs(l, "mode", "IMPLODE"),
                    l.has("result_block") && !l.get("result_block").isJsonNull()
                            ? new ResourceLocation(l.get("result_block").getAsString()) : null,
                    l.has("black_hole_entity") && !l.get("black_hole_entity").isJsonNull()
                            ? new ResourceLocation(l.get("black_hole_entity").getAsString()) : null,
                    gi(l, "implode_duration_ticks", 40),
                    gb(l, "chain_to_neighbors"),
                    gd(l, "chain_chance", 0.4)
            );
        }

        FailureMode mode;
        try {
            mode = FailureMode.valueOf(gs(s, "failure_mode", "CRUMBLE").toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = FailureMode.CRUMBLE;
        }

        return new StructuralData(
                gd(s, "compressive_strength_kpa", 40000.0),
                gd(s, "tensile_strength_kpa", 4000.0),
                gd(s, "shear_strength_kpa", 8000.0),
                gd(s, "fracture_toughness", 0.6),
                curve,
                gd(s, "density_kg_m3", 2400.0),
                gb(s, "is_structural_frame"),
                gi(s, "load_transfer_range", 1),
                mode,
                gd(s, "failure_threshold_fraction", 1.0),
                gd(s, "crack_threshold_fraction", 0.6),
                gi(s, "fragment_min_size", 4),
                gd(s, "fragment_initial_velocity_scale", 1.0),
                gi(s, "cave_in_radius_override", -1),
                lc,
                s.has("blast_resistance_override") && !s.get("blast_resistance_override").isJsonNull()
                        ? s.get("blast_resistance_override").getAsDouble() : null,
                gd(s, "shockwave_amplification", 1.0),
                gd(s, "shockwave_absorption", 0.0)
        );
    }

    // ── Tiny JSON getter helpers ──────────────────────────────────────────────

    private static double gd(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static float gf(JsonObject o, String k, float def) {
        return o.has(k) ? o.get(k).getAsFloat() : def;
    }

    private static int gi(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private static boolean gb(JsonObject o, String k) {
        return o.has(k) && o.get(k).getAsBoolean();
    }

    private static String gs(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    // ── Public query API ──────────────────────────────────────────────────────

    /**
     * Returns the {@link BlockPhysicsData} for a block state.
     * Never returns null — falls back to heuristics and then
     * {@link BlockPhysicsData#AIR}.
     */
    public static BlockPhysicsData get(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());

        // 1. Exact block ID
        if (id != null) {
            BlockPhysicsData exact = BY_BLOCK.get(id);
            if (exact != null) return exact;
        }

        // 2. Tag match (ordered by load order)
        for (var entry : BY_TAG.entrySet()) {
            if (state.is(entry.getKey())) return entry.getValue();
        }

        // 3. Heuristics
        if (state.isAir()) return BlockPhysicsData.AIR;
        if (state.isSolidRender(null, null)) return BlockPhysicsData.GENERIC_SOLID;

        // 4. Partial blocks (slabs, stairs, fences) — half insulation, not airtight
        return BlockPhysicsData.GENERIC_SOLID.asSlab();
    }

    /**
     * Returns the data registered for exactly this block ID, or {@code null}.
     * Fast path used by the structure scanner.
     */
    @Nullable
    public static BlockPhysicsData getByBlock(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null ? BY_BLOCK.get(id) : null;
    }

    /**
     * Injects a {@link BlockPhysicsData} entry at runtime (e.g. from the legacy
     * {@code InsulationRegistry} shim after it loads its own JSON files).
     * Does NOT overwrite entries already loaded from {@code material_properties/}.
     */
    public static void injectLegacy(ResourceLocation blockId, BlockPhysicsData data) {
        BY_BLOCK.putIfAbsent(blockId, data);
    }

    /**
     * Injects a tag-based legacy entry. Does NOT overwrite existing tag entries.
     */
    public static void injectLegacyTag(TagKey<Block> tag, BlockPhysicsData data) {
        BY_TAG.putIfAbsent(tag, data);
    }
}
