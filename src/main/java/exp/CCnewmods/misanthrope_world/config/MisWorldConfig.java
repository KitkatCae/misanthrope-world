package exp.CCnewmods.misanthrope_world.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Unified server-side config for Misanthrope World.
 * <p>
 * File: {@code config/misanthrope_world-server.toml}
 * <p>
 * Managed via Forge's {@link ForgeConfigSpec} so Forge handles file creation,
 * hot-reload via {@code ModConfigEvent}, and value validation automatically.
 * <p>
 * <b>What belongs here vs. in their own files:</b>
 * <ul>
 *   <li>System on/off toggles → here. One place to turn features on/off.</li>
 *   <li>Altitude band definitions → {@code coldsweat_altitude-bands.toml}.
 *       That file is a TOML array that doesn't fit ForgeConfigSpec cleanly,
 *       and pack makers need to edit it freely without touching this file.</li>
 *   <li>Material properties JSON → data-pack, not config.</li>
 * </ul>
 * <p>
 * <b>Adding a new toggleable system in the future:</b>
 * <ol>
 *   <li>Add a {@code BooleanValue} field under the appropriate section.</li>
 *   <li>Add a public static getter (e.g. {@code isFooEnabled()}).</li>
 *   <li>Gate the system's init in {@code Misanthrope_world.commonSetup()}.</li>
 * </ol>
 */
public final class MisWorldConfig {

    private static final Logger LOGGER = LogManager.getLogger("misanthrope_world");

    // ── Spec and values ───────────────────────────────────────────────────────

    public static final ForgeConfigSpec SPEC;

    // [systems]
    private static final ForgeConfigSpec.BooleanValue CRACK_SYSTEM_ENABLED;
    private static final ForgeConfigSpec.BooleanValue COLLAPSE_SYSTEM_ENABLED;
    private static final ForgeConfigSpec.BooleanValue WET_SAND_ENABLED;
    private static final ForgeConfigSpec.BooleanValue ALTITUDE_TEMPERATURE_ENABLED;

    // [structural]
    private static final ForgeConfigSpec.IntValue STRUCTURAL_BACKGROUND_BLOCKS_PER_TICK;
    private static final ForgeConfigSpec.IntValue CRACK_PROPAGATION_INTERVAL_TICKS;

    // [altitude]
    private static final ForgeConfigSpec.ConfigValue<String> ALTITUDE_BANDS_FILE;
    private static final ForgeConfigSpec.DoubleValue ALTITUDE_WIND_SENSITIVITY;
    private static final ForgeConfigSpec.DoubleValue ALTITUDE_ATMOSPHERE_THIN_MAX_FACTOR;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // ── [systems] ─────────────────────────────────────────────────────────
        builder.comment(
                "Misanthrope World — system toggles.",
                "The temperature bridge (Thermodynamica → ColdSweat) is always active",
                "and cannot be disabled here — it is the core purpose of this mod.",
                "All other systems can be independently toggled."
        ).push("systems");

        CRACK_SYSTEM_ENABLED = builder
                .comment(
                        "Enable the structural crack propagation system.",
                        "When enabled, blocks under stress develop visible cracks that spread",
                        "over time and can lead to structural failure.",
                        "Requires the collapse system for failure events to cascade properly."
                )
                .define("crackSystem", true);

        COLLAPSE_SYSTEM_ENABLED = builder
                .comment(
                        "Enable the lattice collapse / structural failure system.",
                        "When enabled, critically stressed blocks undergo visual collapse",
                        "animations and eventually break via Minecollapse or VS2 fragment physics.",
                        "Can be enabled independently of the crack system (failures will still",
                        "fire, but without visual crack buildup beforehand)."
                )
                .define("collapseSystem", true);

        WET_SAND_ENABLED = builder
                .comment(
                        "Enable the wet sand / soil moisture system.",
                        "When enabled, sand, gravel, and other soils gain moisture levels",
                        "driven by adjacent water and rain. Wet soil behaves differently",
                        "when falling and affects crop growth on farmland above it.",
                        "Disabling this will prevent wet block variants from spawning naturally,",
                        "but existing wet blocks in the world will remain."
                )
                .define("wetSand", true);

        ALTITUDE_TEMPERATURE_ENABLED = builder
                .comment(
                        "Enable the altitude temperature band system (ColdSweat integration).",
                        "When enabled, Y-level zones apply temperature modifiers to players",
                        "via ColdSweat's WORLD trait. Bands are configured in the separate",
                        "file specified by altitude.bandsFile.",
                        "Requires ColdSweat to be installed; silently no-ops if absent."
                )
                .define("altitudeTemperature", true);

        builder.pop();

        // ── [structural] ──────────────────────────────────────────────────────
        builder.comment(
                "Structural integrity simulation tuning.",
                "Only relevant when crackSystem or collapseSystem is enabled."
        ).push("structural");

        STRUCTURAL_BACKGROUND_BLOCKS_PER_TICK = builder
                .comment(
                        "How many blocks the background structural scanner re-evaluates per tick.",
                        "Higher = naturally generated overhangs and stalactites fail faster",
                        "at the cost of more CPU. Reactive re-evaluation (triggered by block",
                        "changes) is unaffected by this value.",
                        "Range: 1–64"
                )
                .defineInRange("backgroundBlocksPerTick", 8, 1, 64);

        CRACK_PROPAGATION_INTERVAL_TICKS = builder
                .comment(
                        "How many ticks between crack propagation steps.",
                        "20 = once per second. Lower values spread cracks faster",
                        "but increase CPU cost.",
                        "Range: 1–200"
                )
                .defineInRange("crackPropagationIntervalTicks", 20, 1, 200);

        builder.pop();

        // ── [altitude] ────────────────────────────────────────────────────────
        builder.comment(
                "Altitude temperature system settings.",
                "Band definitions (Y ranges, modifiers, shelter, protection) are in",
                "the separate TOML file named by bandsFile.",
                "Only relevant when systems.altitudeTemperature is true."
        ).push("altitude");

        ALTITUDE_BANDS_FILE = builder
                .comment(
                        "Name of the TOML file in the config directory that contains altitude band",
                        "definitions. Change this to use a custom file name, e.g. for per-pack",
                        "presets. The file is created with defaults if it does not exist."
                )
                .define("bandsFile", "coldsweat_altitude-bands.toml");

        ALTITUDE_WIND_SENSITIVITY = builder
                .comment(
                        "Global multiplier on wind speed when calculating shelter reduction.",
                        "1.0 = realistic (PA wind values used directly).",
                        "0.0 = wind never reduces shelter.",
                        "2.0 = wind is twice as effective at penetrating shelter.",
                        "Range: 0.0–5.0"
                )
                .defineInRange("windSensitivity", 1.0, 0.0, 5.0);

        ALTITUDE_ATMOSPHERE_THIN_MAX_FACTOR = builder
                .comment(
                        "Maximum multiplier the atmosphere thinning system can apply to cold",
                        "altitude modifiers. At 1.0, thinning has no effect. At 3.0 (default),",
                        "a near-vacuum dimension triples the cold modifier.",
                        "Only applies when MGE is installed.",
                        "Range: 1.0–10.0"
                )
                .defineInRange("atmosphereThinMaxFactor", 3.0, 1.0, 10.0);

        builder.pop();

        SPEC = builder.build();
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Call once from the mod constructor.
     */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, "misanthrope_world-server.toml");
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    public static boolean isCrackSystemEnabled()         { return CRACK_SYSTEM_ENABLED.get(); }
    public static boolean isCollapseSystemEnabled()      { return COLLAPSE_SYSTEM_ENABLED.get(); }
    public static boolean isWetSandEnabled()             { return WET_SAND_ENABLED.get(); }
    public static boolean isAltitudeTemperatureEnabled() { return ALTITUDE_TEMPERATURE_ENABLED.get(); }

    public static int structuralBackgroundBlocksPerTick() { return STRUCTURAL_BACKGROUND_BLOCKS_PER_TICK.get(); }
    public static int crackPropagationIntervalTicks()     { return CRACK_PROPAGATION_INTERVAL_TICKS.get(); }

    public static String altitudeBandsFile()              { return ALTITUDE_BANDS_FILE.get(); }
    public static double altitudeWindSensitivity()        { return ALTITUDE_WIND_SENSITIVITY.get(); }
    public static double altitudeAtmosphereThinMaxFactor(){ return ALTITUDE_ATMOSPHERE_THIN_MAX_FACTOR.get(); }

    // ── Logging helper ────────────────────────────────────────────────────────

    /**
     * Logs the current system toggle state. Called during mod init.
     */
    public static void logSystemState() {
        LOGGER.info("[MisWorld Config] System state:");
        LOGGER.info("  crackSystem         = {}", isCrackSystemEnabled());
        LOGGER.info("  collapseSystem      = {}", isCollapseSystemEnabled());
        LOGGER.info("  wetSand             = {}", isWetSandEnabled());
        LOGGER.info("  altitudeTemperature = {}", isAltitudeTemperatureEnabled());
        LOGGER.info("  bandsFile           = {}", altitudeBandsFile());
        LOGGER.info("  windSensitivity     = {}", altitudeWindSensitivity());
        LOGGER.info("  thinMaxFactor       = {}", altitudeAtmosphereThinMaxFactor());
    }
}
