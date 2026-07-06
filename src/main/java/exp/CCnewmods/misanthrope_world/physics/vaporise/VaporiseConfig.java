package exp.CCnewmods.misanthrope_world.physics.vaporise;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Forge SERVER config for the ship vaporisation system.
 *
 * <h3>Burnup condition</h3>
 * A ship vaporises when:
 * <pre>
 *   speedMs / sqrt(mass_kg) >= burnupRatioThreshold
 * </pre>
 * This ratio captures the physics of atmospheric entry: a light object at high
 * speed has the same specific kinetic energy as a heavy object at lower speed,
 * but the light object has far less thermal mass to absorb the heating — so it
 * burns up first. The sqrt(mass) denominator approximates surface-area scaling.
 *
 * <h3>Example crossover points (default threshold 18.0)</h3>
 * <pre>
 *   mass = 50 kg   → burns at ~127 m/s  (~0.3 Mach) — a light scout frame
 *   mass = 500 kg  → burns at ~402 m/s  (~1.2 Mach) — a medium ship
 *   mass = 5000 kg → burns at ~1273 m/s (~3.7 Mach) — a heavy battleship
 * </pre>
 */
public final class VaporiseConfig {

    public static VaporiseConfig INSTANCE;
    public static ForgeConfigSpec SPEC;

    static {
        Pair<VaporiseConfig, ForgeConfigSpec> pair =
                new ForgeConfigSpec.Builder().configure(VaporiseConfig::new);
        INSTANCE = pair.getLeft();
        SPEC     = pair.getRight();
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Master switch. Default: true. */
    public final ForgeConfigSpec.BooleanValue enabled;

    /**
     * Burnup ratio threshold: {@code speedMs / sqrt(mass) >= this → vaporise}.
     * Default: 18.0. Increase to make vaporisation harder to trigger;
     * decrease to make even moderate-speed impacts vaporise light ships.
     */
    public final ForgeConfigSpec.DoubleValue burnupRatioThreshold;

    /**
     * Minimum speed (m/s) required for vaporisation to trigger at all,
     * regardless of mass. Prevents stationary ultra-light objects from
     * vaporising from rounding noise. Default: 40.0.
     */
    public final ForgeConfigSpec.DoubleValue minVaporiseSpeedMs;

    /**
     * Maximum mass (VS2 kg) a ship can have and still vaporise.
     * Extremely heavy ships (capital ships, fortresses) should crater/embed
     * instead of vaporising even at high speed — they have too much thermal
     * inertia. Set to 0 to disable this cap. Default: 50000.0 (50 tonnes).
     */
    public final ForgeConfigSpec.DoubleValue maxVaporiseMassKg;

    /**
     * Flash screen intensity multiplier for all clients.
     * 1.0 = default physics-based intensity. Reduce if players find it
     * too harsh. Default: 1.0.
     */
    public final ForgeConfigSpec.DoubleValue flashIntensityMultiplier;

    /**
     * Duration multiplier for the plasma sphere expansion.
     * 1.0 = default. 2.0 = twice as long, slower expansion. Default: 1.0.
     */
    public final ForgeConfigSpec.DoubleValue plasmaDurationMultiplier;

    /**
     * If true, vaporisation triggers a Misanthrope Gas Engine shockwave at the
     * origin (if MGE is loaded). Default: true.
     */
    public final ForgeConfigSpec.BooleanValue triggerMgeShockwave;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    private VaporiseConfig(ForgeConfigSpec.Builder b) {
        b.comment("=== Ship Vaporisation (Burnup on Impact) ===").push("vaporise");

        enabled = b
                .comment("Master switch for the vaporisation system. Default: true")
                .define("enabled", true);

        burnupRatioThreshold = b
                .comment("Burnup threshold: speedMs / sqrt(mass_kg) >= this triggers vaporise.",
                         "Default: 18.0. Higher = harder to vaporise.")
                .defineInRange("burnup_ratio_threshold", 18.0, 1.0, 1000.0);

        minVaporiseSpeedMs = b
                .comment("Minimum speed (m/s) for vaporisation to trigger. Default: 40.0")
                .defineInRange("min_vaporise_speed_ms", 40.0, 1.0, 10000.0);

        maxVaporiseMassKg = b
                .comment("Maximum ship mass (kg) that can vaporise.",
                         "0 = no limit. Default: 50000.0 (50 tonnes)")
                .defineInRange("max_vaporise_mass_kg", 50000.0, 0.0, 1e9);

        b.pop().push("visuals");

        flashIntensityMultiplier = b
                .comment("Screen flash intensity multiplier. 1.0 = default. Default: 1.0")
                .defineInRange("flash_intensity_multiplier", 1.0, 0.0, 5.0);

        plasmaDurationMultiplier = b
                .comment("Plasma sphere duration multiplier. 1.0 = default. Default: 1.0")
                .defineInRange("plasma_duration_multiplier", 1.0, 0.1, 10.0);

        b.pop().push("integration");

        triggerMgeShockwave = b
                .comment("Trigger a Misanthrope Gas Engine shockwave on vaporise (if MGE is loaded).",
                         "Default: true")
                .define("trigger_mge_shockwave", true);

        b.pop();
    }
}
