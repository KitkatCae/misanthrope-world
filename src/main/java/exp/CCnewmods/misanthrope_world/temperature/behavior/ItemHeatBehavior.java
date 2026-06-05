package exp.CCnewmods.misanthrope_world.temperature.behavior;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Data-driven per-item heat behavior definition.
 * <p>
 * Loaded from {@code data/misanthrope_core/item_heat_behaviors/<name>.json}.
 * <p>
 * ── JSON format ───────────────────────────────────────────────────────────────
 * {
 * "item":  "minecraft:porkchop",        // OR "item_tag": "forge:raw_meat"
 * <p>
 * "behavior_type": "FOOD",              // FOOD | WOOD | METAL | GENERIC | FROZEN_FOOD
 * <p>
 * // ── Cooking chain (FOOD / WOOD only) ────────────────────────────────────
 * "cook_result":        "minecraft:cooked_porkchop",   // optional
 * "cook_min_celsius":   100.0,          // temperature required to start cooking
 * "cook_ticks":         600,            // ticks at cook_min_celsius to complete
 * <p>
 * "burn_result":        "farmersdelight:burnt_porkchop", // optional
 * "burn_min_celsius":   220.0,          // temperature at which burn starts (replaces cook result)
 * "burn_ticks":         200,            // ticks at burn_min_celsius before burning
 * <p>
 * "char_result":        "minecraft:charcoal", // optional; defaults to charcoal
 * "char_min_celsius":   400.0,          // temperature at which item chars (fully inedible)
 * "char_ticks":         100,
 * <p>
 * // ── Freezing chain (FOOD / FROZEN_FOOD) ──────────────────────────────────
 * "freeze_result":      "misanthrope_core:frozen_porkchop",  // optional
 * "freeze_max_celsius": 0.0,            // temperature at/below which freezing can start
 * "freeze_ticks":       1200,           // ticks at freeze_max_celsius to complete
 * <p>
 * // ── Food preservation ─────────────────────────────────────────────────────
 * // (requires a rot system — these are read by the rot handler, not this registry)
 * "rot_rate_cold_multiplier":   0.25,   // at CHILLED: rot 25% of normal speed
 * "rot_rate_frozen_multiplier": 0.0,    // at FROZEN/DEEP_FROZEN: no rot
 * <p>
 * // ── Player damage (all types) ─────────────────────────────────────────────
 * // Overrides defaults. If absent, uses HeatDamageDefaults for the heat state.
 * "damage_per_second_very_hot":  0.5,
 * "damage_per_second_red_hot":   2.0,
 * "damage_per_second_orange_hot": 5.0,
 * "damage_per_second_yellow_hot": 10.0,
 * "damage_per_second_white_hot":  20.0,
 * "damage_per_second_blue_hot":   40.0,
 * "damage_per_second_deep_frozen": 1.0,
 * <p>
 * // ── Wood-specific ────────────────────────────────────────────────────────
 * "char_to_charcoal": true,             // WOOD only: char_result becomes charcoal
 * }
 * <p>
 * ── Behavior types ────────────────────────────────────────────────────────────
 * FOOD        — cookable, can burn/char, can freeze, rots
 * WOOD        — chars to charcoal, no freeze, no cooking (no nutritional product)
 * METAL       — no cooking/charring/freezing; damage profile from heat states only
 * FROZEN_FOOD — already frozen food; thaws/cooks/rots normally when heated
 * GENERIC     — only player damage applies; no cooking/burning/freezing logic
 */
public record ItemHeatBehavior(
        // ── Identity ──────────────────────────────────────────────────────────
        @Nullable ResourceLocation itemId,     // null if tag-based
        @Nullable ResourceLocation itemTagId,  // null if item-specific
        BehaviorType behaviorType,

        // ── Cooking chain ─────────────────────────────────────────────────────
        @Nullable ResourceLocation cookResult,
        double cookMinCelsius,
        int cookTicks,

        @Nullable ResourceLocation burnResult,
        double burnMinCelsius,
        int burnTicks,

        @Nullable ResourceLocation charResult,
        double charMinCelsius,
        int charTicks,

        // ── Freezing chain ────────────────────────────────────────────────────
        @Nullable ResourceLocation freezeResult,
        double freezeMaxCelsius,
        int freezeTicks,

        // ── Rot rate modifiers ────────────────────────────────────────────────
        double rotRateColdMultiplier,
        double rotRateFrozenMultiplier,

        // ── Per-state damage overrides (NaN = use default) ───────────────────
        double damageVeryHot,
        double damageRedHot,
        double damageOrangeHot,
        double damageYellowHot,
        double damageWhiteHot,
        double damageBlueHot,
        double damageDeepFrozen
) {

    public enum BehaviorType {FOOD, WOOD, METAL, FROZEN_FOOD, GENERIC}

    /**
     * Returns the damage-per-second for the given heat state, using the
     * registered override if set (not NaN), otherwise the system default.
     */
    public double damagePerSecond(exp.CCnewmods.misanthrope_world.temperature.heatstate.HeatState state) {
        return switch (state) {
            case VERY_HOT -> isSet(damageVeryHot) ? damageVeryHot : HeatDamageDefaults.VERY_HOT;
            case RED_HOT -> isSet(damageRedHot) ? damageRedHot : HeatDamageDefaults.RED_HOT;
            case ORANGE_HOT -> isSet(damageOrangeHot) ? damageOrangeHot : HeatDamageDefaults.ORANGE_HOT;
            case YELLOW_HOT -> isSet(damageYellowHot) ? damageYellowHot : HeatDamageDefaults.YELLOW_HOT;
            case WHITE_HOT -> isSet(damageWhiteHot) ? damageWhiteHot : HeatDamageDefaults.WHITE_HOT;
            case BLUE_HOT -> isSet(damageBlueHot) ? damageBlueHot : HeatDamageDefaults.BLUE_HOT;
            case DEEP_FROZEN -> isSet(damageDeepFrozen) ? damageDeepFrozen : HeatDamageDefaults.DEEP_FROZEN;
            default -> 0.0;
        };
    }

    private static boolean isSet(double v) {
        return !Double.isNaN(v) && v >= 0.0;
    }

    /**
     * Default damage-per-second values for each heat state (used when no override).
     */
    public static final class HeatDamageDefaults {
        // Held item contact damage scales with intensity.
        // HOT (80–200°C): no damage — painful but survivable without armour
        public static final double VERY_HOT = 0.5;  // 80–200°C: 1 HP / 2 sec
        public static final double RED_HOT = 2.0;  // 200–600°C: 1 HP / 0.5 sec
        public static final double ORANGE_HOT = 5.0;  // 600–900°C
        public static final double YELLOW_HOT = 10.0; // 900–1100°C
        public static final double WHITE_HOT = 20.0; // 1100–1400°C
        public static final double BLUE_HOT = 40.0; // >1400°C: ~2 HP/tick
        public static final double DEEP_FROZEN = 1.0;  // <-20°C: frostbite
    }
}
