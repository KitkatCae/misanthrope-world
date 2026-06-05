package exp.CCnewmods.misanthrope_world.config;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeBand;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Singleton that owns the parsed list of {@link AltitudeBand}s.
 * <p>
 * The bands file name is read from {@link MisWorldConfig#altitudeBandsFile()},
 * defaulting to {@code coldsweat_altitude-bands.toml}.
 * <p>
 * Hot-reload: {@code /misworld altitude reload}
 */
public class AltitudeConfig {

    private static final Logger LOGGER = LogManager.getLogger(Misanthrope_world.MODID);

    /** Fallback if MisWorldConfig isn't loaded yet (shouldn't happen in normal flow). */
    public static final String DEFAULT_BANDS_FILE = "coldsweat_altitude-bands.toml";

    public static final int MAX_SHELTER_CHECK_RADIUS = AltitudeBandConfig.MAX_SHELTER_CHECK_RADIUS;

    private static final AltitudeConfig INSTANCE = new AltitudeConfig();
    public static AltitudeConfig getInstance() { return INSTANCE; }

    private volatile List<AltitudeBand> bands = List.of();
    private AltitudeConfig() {}

    public List<AltitudeBand> getBands() { return bands; }

    /** The file name currently loaded from. Useful for status/debug output. */
    public String currentFileName() {
        try { return MisWorldConfig.altitudeBandsFile(); }
        catch (Exception e) { return DEFAULT_BANDS_FILE; }
    }

    public void reload() {
        String fileName = currentFileName();
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(fileName);
        ensureDefaultConfig(configPath);

        List<AltitudeBand> loaded;
        try {
            loaded = parseToml(configPath);
        } catch (Exception e) {
            LOGGER.error("[MisWorld Altitude] Failed to load {}. Keeping previous band set.", fileName, e);
            return;
        }

        bands = loaded.stream()
                .sorted(Comparator.comparingInt(AltitudeBand::priority).reversed())
                .toList();

        LOGGER.info("[MisWorld Altitude] Loaded {} altitude band(s) from {}.", bands.size(), fileName);
    }

    // ── TOML parsing ──────────────────────────────────────────────────────────

    private List<AltitudeBand> parseToml(Path configPath) {
        com.electronwill.nightconfig.core.file.CommentedFileConfig cfg =
                com.electronwill.nightconfig.core.file.CommentedFileConfig
                        .builder(configPath)
                        .build();
        cfg.load();

        List<AltitudeBand> result = new ArrayList<>();
        try {
            List<?> rawBands = cfg.get("bands");
            if (rawBands == null) return result;

            for (Object entry : rawBands) {
                if (!(entry instanceof com.electronwill.nightconfig.core.UnmodifiableConfig section)) {
                    LOGGER.warn("[MisWorld Altitude] Skipping malformed band entry.");
                    continue;
                }
                AltitudeBandConfig.fromSection(section,
                        msg -> LOGGER.warn("[MisWorld Altitude] {}", msg)
                ).ifPresent(bandCfg -> {
                    if (!bandCfg.enabled()) {
                        LOGGER.debug("[MisWorld Altitude] Band '{}' disabled, skipping.", bandCfg.id());
                        return;
                    }
                    result.add(AltitudeBand.fromConfig(bandCfg,
                            msg -> LOGGER.warn("[MisWorld Altitude] {}", msg)));
                });
            }
        } finally {
            cfg.close();
        }
        return result;
    }

    // ── Default config ────────────────────────────────────────────────────────

    private static void ensureDefaultConfig(Path configPath) {
        if (Files.exists(configPath)) return;
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, defaultConfigText());
            LOGGER.info("[MisWorld Altitude] Created default bands file at {}.", configPath);
        } catch (IOException e) {
            LOGGER.error("[MisWorld Altitude] Could not write default bands file.", e);
        }
    }

    private static String defaultConfigText() {
        return """
                # Misanthrope World — Altitude Temperature Bands
                # Loaded by the altitude temperature system (requires systems.altitudeTemperature = true
                # in misanthrope_world-server.toml).
                #
                # Band id: stable name used by commands, logs, and runtime state.
                # Leave maxY unset to make a band open-ended upward.
                # shelterCheckRadius is clamped to 16 at load time regardless of what you set here.
                #
                # MGE/Project Atmosphere integration is automatic:
                #   Wind from PA reduces shelter effectiveness (tunable via altitude.windSensitivity).
                #   Thin MGE atmosphere amplifies cold modifiers (tunable via altitude.atmosphereThinMaxFactor).
                #   Low O2 compounds the cold effect.
                #   protectionTag items counteract the atmosphere thinning penalty.

                [[bands]]
                id = "deep_caves"
                enabled = true
                dimensions = ["minecraft:overworld"]
                dimensionMode = "WHITELIST"
                minY = -64
                maxY = 0
                temperatureModifier = 0.08
                modifierMode = "ADD"
                priority = 10
                onEnterMessage = ""
                actionbarMessage = ""
                messageCooldownTicks = 100
                protectionTag = ""
                requiredPieces = 0
                protectionReductionPerPiece = 0.0
                fullProtectionPieces = 4
                enableShelterCheck = false
                shelterCheckRadius = 4
                shelterReduction = 0.0

                [[bands]]
                id = "underground"
                enabled = true
                dimensions = ["minecraft:overworld"]
                dimensionMode = "WHITELIST"
                minY = 1
                maxY = 63
                temperatureModifier = 0.03
                modifierMode = "ADD"
                priority = 5
                onEnterMessage = ""
                actionbarMessage = ""
                messageCooldownTicks = 100
                protectionTag = ""
                requiredPieces = 0
                protectionReductionPerPiece = 0.0
                fullProtectionPieces = 4
                enableShelterCheck = false
                shelterCheckRadius = 4
                shelterReduction = 0.0

                [[bands]]
                id = "surface"
                enabled = true
                dimensions = ["minecraft:overworld"]
                dimensionMode = "WHITELIST"
                minY = 64
                maxY = 191
                temperatureModifier = 0.0
                modifierMode = "ADD"
                priority = 0
                onEnterMessage = ""
                actionbarMessage = ""
                messageCooldownTicks = 100
                protectionTag = ""
                requiredPieces = 0
                protectionReductionPerPiece = 0.0
                fullProtectionPieces = 4
                enableShelterCheck = false
                shelterCheckRadius = 4
                shelterReduction = 0.0

                [[bands]]
                id = "high_peaks"
                enabled = true
                dimensions = ["minecraft:overworld"]
                dimensionMode = "WHITELIST"
                minY = 192
                maxY = 255
                temperatureModifier = -0.08
                modifierMode = "ADD"
                priority = 15
                onEnterMessage = ""
                actionbarMessage = ""
                messageCooldownTicks = 100
                protectionTag = ""
                requiredPieces = 0
                protectionReductionPerPiece = 0.0
                fullProtectionPieces = 4
                enableShelterCheck = true
                shelterCheckRadius = 4
                shelterReduction = 0.35

                [[bands]]
                id = "low_sky"
                enabled = true
                dimensions = ["minecraft:overworld"]
                dimensionMode = "WHITELIST"
                minY = 256
                maxY = 320
                temperatureModifier = -0.20
                modifierMode = "ADD"
                priority = 20
                onEnterMessage = "The air grows colder at this altitude."
                actionbarMessage = "Altitude chill"
                messageCooldownTicks = 100
                protectionTag = ""
                requiredPieces = 0
                protectionReductionPerPiece = 0.0
                fullProtectionPieces = 4
                enableShelterCheck = true
                shelterCheckRadius = 4
                shelterReduction = 0.50

                [[bands]]
                id = "extreme_sky"
                enabled = true
                dimensions = ["minecraft:overworld"]
                dimensionMode = "WHITELIST"
                minY = 321
                temperatureModifier = -0.45
                modifierMode = "ADD"
                priority = 30
                onEnterMessage = "The air grows thin and bitter."
                actionbarMessage = "Extreme altitude exposure"
                messageCooldownTicks = 100
                protectionTag = ""
                requiredPieces = 0
                protectionReductionPerPiece = 0.0
                fullProtectionPieces = 4
                enableShelterCheck = true
                shelterCheckRadius = 4
                shelterReduction = 0.75
                """;
    }
}
