package exp.CCnewmods.misanthrope_world.temperature.heatstate;

/**
 * Discrete thermal state of an item, derived from its Celsius temperature.
 * <p>
 * Used by all item heat systems: visual overlays, player damage, food cooking,
 * food preservation, wood burning, and storage interaction. The state is
 * computed from raw Celsius each tick and stored in the capability NBT so
 * client-side rendering can read it without doing the math.
 * <p>
 * ── Temperature bounds (defaults) ────────────────────────────────────────────
 * Materials may override the transition points via ItemHeatBehavior data files.
 * These defaults apply to materials with no registered behavior.
 * <p>
 * DEEP_FROZEN   < -20°C  — solid ice state, zero rot, fragile
 * FROZEN        -20–0°C  — frozen, no rot
 * COLD          0–10°C   — refrigerator range, slow rot
 * CHILLED       10–16°C  — cool cellar, reduced rot
 * NORMAL        16–40°C  — ambient/room temperature
 * WARM          40–80°C  — warm to touch, no damage
 * HOT           80–200°C — hot to touch — brief contact causes discomfort
 * VERY_HOT      200–600°C  — burns on contact — continuous damage
 * RED_HOT       600–900°C  — glowing red — heavy damage
 * ORANGE_HOT    900–1100°C — orange glow — severe damage
 * YELLOW_HOT    1100–1400°C — yellow-white glow — extreme damage
 * WHITE_HOT     1400–2500°C — white glow — near-instant damage
 * BLUE_HOT      > 2500°C — blue-white, arc-temperature — instant lethal
 */
public enum HeatState {

    // ── Cold side ─────────────────────────────────────────────────────────────
    DEEP_FROZEN,
    FROZEN,
    COLD,
    CHILLED,

    // ── Neutral ───────────────────────────────────────────────────────────────
    NORMAL,

    // ── Hot side ─────────────────────────────────────────────────────────────
    WARM,
    HOT,
    VERY_HOT,
    RED_HOT,
    ORANGE_HOT,
    YELLOW_HOT,
    WHITE_HOT,
    BLUE_HOT;

    // ── Default transition thresholds (°C) ────────────────────────────────────

    public static HeatState fromCelsius(double celsius) {
        if (celsius > 2500) return BLUE_HOT;
        else if (celsius > 1400) return WHITE_HOT;
        else if (celsius > 1100) return YELLOW_HOT;
        else if (celsius > 900) return ORANGE_HOT;
        else if (celsius > 600) return RED_HOT;
        else if (celsius > 200) return VERY_HOT;
        else if (celsius > 80) return HOT;
        else if (celsius > 40) return WARM;
        else if (celsius > 16) return NORMAL;
        else if (celsius > 10) return CHILLED;
        else if (celsius > 0) return COLD;
        else if (celsius > -20) return FROZEN;
        else return DEEP_FROZEN;
    }

    /**
     * True if this state causes player damage from held/worn items.
     */
    public boolean damagesPlayer() {
        return this == VERY_HOT || this == RED_HOT || this == ORANGE_HOT
                || this == YELLOW_HOT || this == WHITE_HOT || this == BLUE_HOT
                || this == DEEP_FROZEN;
    }

    /**
     * True if this state causes cold damage (frostbite from held items).
     */
    public boolean isColdDamage() {
        return this == DEEP_FROZEN;
    }

    /**
     * True if this state actively cooks food.
     */
    public boolean cookingActive() {
        return ordinal() >= VERY_HOT.ordinal();
    }

    /**
     * True if this state preserves food (slows rot).
     */
    public boolean preservesFood() {
        return this == COLD || this == CHILLED || this == FROZEN || this == DEEP_FROZEN;
    }

    /**
     * True if this state halts food rot entirely.
     */
    public boolean haltsRot() {
        return this == FROZEN || this == DEEP_FROZEN;
    }

    /**
     * True if food in this state is actively being frozen.
     */
    public boolean freezingActive() {
        return this == FROZEN || this == DEEP_FROZEN;
    }

    /**
     * NBT ID for serialisation.
     */
    public byte id() {
        return (byte) ordinal();
    }

    public static HeatState fromId(byte id) {
        HeatState[] vals = values();
        if (id < 0 || id >= vals.length) return NORMAL;
        return vals[id];
    }
}
