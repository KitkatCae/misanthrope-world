package exp.CCnewmods.misanthrope_world.physics.pressure.creature;

/**
 * Pressure tolerance profile for a single entity type.
 *
 * <p>Loaded from {@code data/misanthrope_world/creature_pressure/<modid>-<entity>.json}.
 *
 * <h3>Damage model</h3>
 * <pre>
 *   total pressure at entity pos = gas pressure (MGE) + fluid hydrostatic (MWorld)
 *
 *   crush zone  : totalPressure > crushThresholdMbar
 *   vacuum zone : totalPressure < vacuumThresholdMbar  (only when simulateExpansion is on)
 * </pre>
 * Inside a damage zone the entity accumulates {@code damageAccumTicks} per server tick.
 * When the accumulator reaches {@code damageIntervalTicks} the entity receives
 * {@code damagePerInterval} points (half-hearts) of pressure damage, then the
 * accumulator resets. Armour from {@code pressure_sealed/} JSONs reduces the
 * damage by {@code damageReduction} [0, 1] before it is applied.
 *
 * <p>Set a threshold to {@code Float.MAX_VALUE} (crush) or {@code -1} (vacuum)
 * to disable that zone for this entity.
 */
public final class CreaturePressureProfile {

    /** Registry key of the entity type, e.g. {@code minecraft:cod}. */
    public final String entityId;

    // ── Crush (over-pressure) ─────────────────────────────────────────────────

    /**
     * Total pressure in mbar above which this entity starts taking crush damage.
     * Typical seawater at 10 m depth ≈ 2000 mbar total. Surface air ≈ 1013 mbar.
     * Use {@code Float.MAX_VALUE} to disable crush damage for this entity.
     */
    public final float crushThresholdMbar;

    /**
     * Maximum total pressure this entity can ever survive.  If total pressure
     * exceeds this value the entity takes {@code crushInstantKillDamage} instead
     * of the normal interval-based damage (useful for e.g. deep ocean fish that
     * explode if brought to the surface under very high vacuum, or surface fish
     * that implode at extreme depth).
     * Use {@code Float.MAX_VALUE} to disable instant-kill crush.
     */
    public final float crushInstantKillThresholdMbar;

    // ── Vacuum (under-pressure) ───────────────────────────────────────────────

    /**
     * Total pressure in mbar below which this entity starts taking expansion /
     * decompression damage.  Standard atmosphere is 1013 mbar; the vacuum of
     * space is 0 mbar.
     * Use {@code -1} to disable vacuum damage for this entity.
     */
    public final float vacuumThresholdMbar;

    /** Instant-kill vacuum threshold (mbar). {@code -1} = disabled. */
    public final float vacuumInstantKillThresholdMbar;

    // ── Timing ────────────────────────────────────────────────────────────────

    /**
     * Server ticks between damage applications once inside a damage zone.
     * Default 40 (2 seconds at 20 TPS).
     */
    public final int damageIntervalTicks;

    // ── Damage amounts ────────────────────────────────────────────────────────

    /** Damage points applied each interval while in the crush zone. */
    public final float crushDamagePerInterval;

    /** Damage points applied each interval while in the vacuum zone. */
    public final float vacuumDamagePerInterval;

    /** Damage points applied once when crossing {@link #crushInstantKillThresholdMbar}. */
    public final float crushInstantKillDamage;

    /** Damage points applied once when crossing {@link #vacuumInstantKillThresholdMbar}. */
    public final float vacuumInstantKillDamage;

    // ── Armour interaction ────────────────────────────────────────────────────

    /**
     * If {@code true}, damage reduction from matching {@code pressure_sealed/}
     * armour sets is applied before dealing damage.
     * Default: {@code true}.
     */
    public final boolean respectPressureArmour;

    // ── Constructor (all-args) ────────────────────────────────────────────────

    public CreaturePressureProfile(
            String entityId,
            float crushThresholdMbar,
            float crushInstantKillThresholdMbar,
            float vacuumThresholdMbar,
            float vacuumInstantKillThresholdMbar,
            int   damageIntervalTicks,
            float crushDamagePerInterval,
            float vacuumDamagePerInterval,
            float crushInstantKillDamage,
            float vacuumInstantKillDamage,
            boolean respectPressureArmour) {

        this.entityId                      = entityId;
        this.crushThresholdMbar            = crushThresholdMbar;
        this.crushInstantKillThresholdMbar = crushInstantKillThresholdMbar;
        this.vacuumThresholdMbar           = vacuumThresholdMbar;
        this.vacuumInstantKillThresholdMbar = vacuumInstantKillThresholdMbar;
        this.damageIntervalTicks           = Math.max(1, damageIntervalTicks);
        this.crushDamagePerInterval        = crushDamagePerInterval;
        this.vacuumDamagePerInterval       = vacuumDamagePerInterval;
        this.crushInstantKillDamage        = crushInstantKillDamage;
        this.vacuumInstantKillDamage       = vacuumInstantKillDamage;
        this.respectPressureArmour         = respectPressureArmour;
    }

    // ── Convenience predicates ────────────────────────────────────────────────

    public boolean hasCrushZone()  { return crushThresholdMbar  < Float.MAX_VALUE; }
    public boolean hasVacuumZone() { return vacuumThresholdMbar >= 0f; }

    @Override
    public String toString() {
        return "CreaturePressureProfile{entity=" + entityId
                + ", crush>" + crushThresholdMbar + "mbar"
                + ", vacuum<" + vacuumThresholdMbar + "mbar}";
    }
}
