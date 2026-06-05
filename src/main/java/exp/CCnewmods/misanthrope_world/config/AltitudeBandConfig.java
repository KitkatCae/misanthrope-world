package exp.CCnewmods.misanthrope_world.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Immutable record holding the raw configuration for one altitude band,
 * parsed directly from the TOML file.
 */
public record AltitudeBandConfig(
        String  id,
        boolean enabled,
        List<String> dimensions,
        DimensionMode dimensionMode,
        int     minY,
        Integer maxY,         // null = open-ended upward
        double  temperatureModifier,
        ModifierMode modifierMode,
        int     priority,
        String  onEnterMessage,
        String  actionbarMessage,
        int     messageCooldownTicks,
        String  protectionTag,
        int     requiredPieces,
        double  protectionReductionPerPiece,
        int     fullProtectionPieces,
        boolean enableShelterCheck,
        int     shelterCheckRadius,
        double  shelterReduction
) {
    public enum DimensionMode { WHITELIST, BLACKLIST }
    public enum ModifierMode  { ADD, MULTIPLY }

    public static final int MAX_SHELTER_CHECK_RADIUS = 16;

    public static Optional<AltitudeBandConfig> fromSection(
            com.electronwill.nightconfig.core.UnmodifiableConfig section,
            Consumer<String> warningSink) {

        String id = getString(section, "id", "");
        if (id.isBlank()) {
            warningSink.accept("Skipping altitude band with missing id.");
            return Optional.empty();
        }

        boolean enabled = getBool(section, "enabled", true);
        List<String> dimensions = getStringList(section, "dimensions");
        DimensionMode dimMode = getEnum(section, "dimensionMode", DimensionMode.WHITELIST, warningSink);
        int minY = getInt(section, "minY", 0);
        Integer maxY = getOptionalInt(section, "maxY");

        if (maxY != null && maxY < minY) {
            warningSink.accept("Skipping altitude band '" + id + "' because maxY is lower than minY.");
            return Optional.empty();
        }

        double tempMod = getDouble(section, "temperatureModifier", 0.0);
        ModifierMode modMode = getEnum(section, "modifierMode", ModifierMode.ADD, warningSink);
        int priority = getInt(section, "priority", 0);
        String onEnter = getString(section, "onEnterMessage", "");
        String actionbar = getString(section, "actionbarMessage", "");
        int cooldown = getInt(section, "messageCooldownTicks", 100);
        String protTag = getString(section, "protectionTag", "");
        int reqPieces = getInt(section, "requiredPieces", 0);
        double redPerPiece = getDouble(section, "protectionReductionPerPiece", 0.0);
        int fullPieces = getInt(section, "fullProtectionPieces", 4);
        boolean shelterEnabled = getBool(section, "enableShelterCheck", false);
        int shelterRadius = Math.min(getInt(section, "shelterCheckRadius", 4), MAX_SHELTER_CHECK_RADIUS);
        double shelterRed = getDouble(section, "shelterReduction", 0.0);

        return Optional.of(new AltitudeBandConfig(
                id, enabled, dimensions, dimMode, minY, maxY,
                tempMod, modMode, priority, onEnter, actionbar, cooldown,
                protTag, reqPieces, redPerPiece, fullPieces,
                shelterEnabled, shelterRadius, shelterRed));
    }

    private static String getString(com.electronwill.nightconfig.core.UnmodifiableConfig cfg, String key, String fallback) {
        Object v = cfg.get(key); return (v instanceof String s) ? s : fallback;
    }
    private static boolean getBool(com.electronwill.nightconfig.core.UnmodifiableConfig cfg, String key, boolean fallback) {
        Object v = cfg.get(key); return (v instanceof Boolean b) ? b : fallback;
    }
    private static int getInt(com.electronwill.nightconfig.core.UnmodifiableConfig cfg, String key, int fallback) {
        Object v = cfg.get(key); return (v instanceof Number n) ? n.intValue() : fallback;
    }
    private static Integer getOptionalInt(com.electronwill.nightconfig.core.UnmodifiableConfig cfg, String key) {
        Object v = cfg.get(key); return (v instanceof Number n) ? n.intValue() : null;
    }
    private static double getDouble(com.electronwill.nightconfig.core.UnmodifiableConfig cfg, String key, double fallback) {
        Object v = cfg.get(key); return (v instanceof Number n) ? n.doubleValue() : fallback;
    }
    @SuppressWarnings("unchecked")
    private static List<String> getStringList(com.electronwill.nightconfig.core.UnmodifiableConfig cfg, String key) {
        Object v = cfg.get(key);
        if (v instanceof List<?> list) return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        return List.of();
    }
    private static <E extends Enum<E>> E getEnum(com.electronwill.nightconfig.core.UnmodifiableConfig cfg,
                                                  String key, E fallback, Consumer<String> warn) {
        Object v = cfg.get(key);
        if (!(v instanceof String s)) return fallback;
        try { //noinspection unchecked
            return (E) Enum.valueOf(fallback.getDeclaringClass(), s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.accept("Unknown value '" + s + "' for key '" + key + "', using " + fallback);
            return fallback;
        }
    }
}
