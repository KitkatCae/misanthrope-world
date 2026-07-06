package exp.CCnewmods.misanthrope_world.physics.reentry;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Forge SERVER config for the aerodynamics systems (sound barrier + kinetic heating).
 *
 * <p>Ported from MVSE's Misanthrope_vs_engine.commonSetup; registered in Misanthrope_world's constructor now.</p>
 *
 * <h3>Mach scale</h3>
 * Default Mach 1 = 343 m/s.  VS2 physics units are 1 block = 1 m, so ship
 * velocity magnitude in blocks/tick × 20 = m/s.
 *
 * <h3>Heating scale (Mach → temperature)</h3>
 * <pre>
 *   Mach 1  → ~   400 °C  (sonic transition)
 *   Mach 5  → ~  1500 °C  (orange glow)
 *   Mach 25 → ~  5000 °C  (white plasma)
 *   Mach 208→ ~120000 °C  (cap, effectively disintegration)
 * </pre>
 * Colour shifts:  orange (Mach 5) → yellow (Mach 12) → white (Mach 25) → violet (Mach 80+)
 */
public final class AerodynamicsConfig {

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    public static AerodynamicsConfig INSTANCE;
    public static ForgeConfigSpec SPEC;

    static {
        Pair<AerodynamicsConfig, ForgeConfigSpec> pair =
                new ForgeConfigSpec.Builder().configure(AerodynamicsConfig::new);
        INSTANCE = pair.getLeft();
        SPEC     = pair.getRight();
    }

    // -------------------------------------------------------------------------
    // Sound Barrier
    // -------------------------------------------------------------------------

    /** Speed of sound in m/s (= blocks/s in VS2). Default 343. */
    public final ForgeConfigSpec.DoubleValue machOneSpeedMs;

    /**
     * Cooldown in ticks between consecutive sonic boom events for the same ship.
     * Prevents spam when a ship hovers exactly at Mach 1.
     */
    public final ForgeConfigSpec.IntValue boomCooldownTicks;

    /**
     * Ticks a ship rattles/buffets for after crossing Mach 1 (new — MVSE never
     * actually called ShipRattleAttachment.trigger() with a duration, since
     * fireBoom never invoked it at all; this is the first real value for it).
     * Default: 60 (3 s), matching the half-life scaling already baked into
     * the rattle amplitude/decay math.
     */
    public final ForgeConfigSpec.IntValue rattleDurationTicks;

    /**
     * Minimum atmospheric pressure (mbar) required for a sonic boom to produce
     * sound and shockwave.  Below this, the crossing is silent (vacuum).
     * Default 80 mbar = MGE's MIN_GLIDE_PRESSURE.
     */
    public final ForgeConfigSpec.DoubleValue boomMinPressureMbar;

    /**
     * Shockwave strength passed to {@code ShockwaveHandler.spawn()} at exactly
     * Mach 1. Scales linearly with additional speed.
     */
    public final ForgeConfigSpec.DoubleValue boomBaseStrength;

    /**
     * If a ship vanishes (destroyed/despawned) while still supersonic, the
     * surrounding air rushes into the sudden vacuum where it was, producing
     * an immediate implosion shockwave, and the pressure cone it had been
     * trailing snaps forward as a second, larger, delayed boom. This is that
     * effect's overall strength multiplier, applied on top of the same
     * speed-excess scaling as a normal boom. Set to 0 to disable the effect
     * entirely (falls back to just stopping the rumble silently).
     */
    public final ForgeConfigSpec.DoubleValue vanishImplosionStrength;

    /**
     * Strength multiplier for the delayed "pressure cone snaps forward" boom
     * specifically, relative to the immediate implosion above — this one is
     * meant to be the bigger of the two ("massive, delayed sonic boom").
     */
    public final ForgeConfigSpec.DoubleValue vanishDelayedBoomStrength;

    /**
     * Ticks between the immediate implosion and the delayed forward boom.
     * Represents the time for the trailing pressure cone to catch up and
     * release. Default: 15 (0.75 s).
     */
    public final ForgeConfigSpec.IntValue vanishDelayedBoomTicks;

    // -------------------------------------------------------------------------
    // Kinetic Heating
    // -------------------------------------------------------------------------

    /**
     * Minimum Mach number at which kinetic heating begins.
     * At Mach 5 the heating is just starting; Mach 1–5 is a ramp-up.
     */
    public final ForgeConfigSpec.DoubleValue heatingOnsetMach;

    /**
     * Maximum Mach number the heating formula is capped at.
     * Beyond this, intensity is clamped to 1.0 (full disintegration tier).
     */
    public final ForgeConfigSpec.DoubleValue heatingMaxMach;

    /**
     * Peak surface temperature (°C) injected into the MGE grid at maximum
     * Mach number.  Real reentry peak ≈ 1650 °C for ablative shields;
     * Mach 208 extrapolates to ~120 000 °C plasma.
     */
    public final ForgeConfigSpec.DoubleValue heatingPeakCelsius;

    /**
     * How many surface-layer block positions (along the velocity vector) receive
     * temperature injection per tick.  Higher = deeper thermal penetration.
     * Default 3.  Scales up with intensity at high Mach.
     */
    public final ForgeConfigSpec.IntValue heatingPenetrationDepth;

    /**
     * Base rate at which ionised air is injected into the MGE grid per heated
     * block per tick (mbar).  Represents plasma trail gases.
     */
    public final ForgeConfigSpec.DoubleValue plasmaGasRateMbar;

    /**
     * Tick interval between heating updates (temperature injection + stress).
     * Default 2.  Lower = more granular but more expensive.
     */
    public final ForgeConfigSpec.IntValue heatingTickInterval;

    /**
     * Tick interval at which the {@code ReentryStatePacket} is sent to nearby
     * clients for particle/visual updates.  Default 4.
     */
    public final ForgeConfigSpec.IntValue clientSyncTickInterval;

    /**
     * Radius (blocks) around the ship nose within which players receive
     * reentry visual/sound updates.
     */
    public final ForgeConfigSpec.DoubleValue clientSyncRadius;

    // -------------------------------------------------------------------------
    // Constructor (called by ForgeConfigSpec.Builder.configure)
    // -------------------------------------------------------------------------

    private AerodynamicsConfig(ForgeConfigSpec.Builder b) {

        b.comment("=== Sound Barrier ===").push("sound_barrier");

        machOneSpeedMs = b
                .comment("Speed of sound (m/s = blocks/s). Sonic boom triggers when a ship",
                         "crosses this speed threshold. Default: 343 (real-world sea level).")
                .defineInRange("mach_one_speed_ms", 343.0, 10.0, 10000.0);

        boomCooldownTicks = b
                .comment("Ticks before the same ship can trigger another sonic boom.",
                         "Prevents spam when hovering near Mach 1. Default: 100 (5 s).")
                .defineInRange("boom_cooldown_ticks", 100, 1, 6000);

        rattleDurationTicks = b
                .comment("Ticks a ship rattles/buffets for after crossing Mach 1.",
                         "Default: 60 (3 s).")
                .defineInRange("rattle_duration_ticks", 60, 0, 6000);

        boomMinPressureMbar = b
                .comment("Minimum local atmospheric pressure (mbar) for the boom to produce",
                         "sound and shockwave. Below this value the crossing is silent (vacuum).",
                         "Default: 80.0 (MGE thin-atmosphere threshold).")
                .defineInRange("boom_min_pressure_mbar", 80.0, 0.0, 2000.0);

        boomBaseStrength = b
                .comment("ShockwaveHandler strength at exactly Mach 1. Scales linearly",
                         "with speed excess above Mach 1. Default: 3.5")
                .defineInRange("boom_base_strength", 3.5, 0.1, 50.0);

        vanishImplosionStrength = b
                .comment("Strength multiplier for the immediate implosion shockwave when a",
                         "ship vanishes while supersonic (air rushing into the sudden vacuum).",
                         "Applied on top of the same speed-excess scaling as a normal boom.",
                         "Default: 1.5. Set to 0 to disable the whole vanish effect.")
                .defineInRange("vanish_implosion_strength", 1.5, 0.0, 50.0);

        vanishDelayedBoomStrength = b
                .comment("Strength multiplier for the delayed 'pressure cone snaps forward'",
                         "boom that follows the implosion above — meant to be the bigger of",
                         "the two. Default: 3.0.")
                .defineInRange("vanish_delayed_boom_strength", 3.0, 0.0, 50.0);

        vanishDelayedBoomTicks = b
                .comment("Ticks between the implosion and the delayed forward boom.",
                         "Default: 15 (0.75 s).")
                .defineInRange("vanish_delayed_boom_ticks", 15, 0, 200);

        b.pop();

        b.comment("=== Kinetic Heating (Mach 5 → Mach 208) ===").push("kinetic_heating");

        heatingOnsetMach = b
                .comment("Mach number at which kinetic heating begins.  Below this Mach",
                         "the ship moves too slowly for significant aeroheating.",
                         "Default: 5.0")
                .defineInRange("heating_onset_mach", 5.0, 1.0, 50.0);

        heatingMaxMach = b
                .comment("Mach number at which heating is clamped to maximum intensity.",
                         "Default: 208.0 (arbitrary high cap — full plasma/disintegration).")
                .defineInRange("heating_max_mach", 208.0, 10.0, 100000.0);

        heatingPeakCelsius = b
                .comment("Peak surface temperature (°C) injected into the MGE grid at max",
                         "Mach.  This feeds Misanthrope Core's phase transitions naturally.",
                         "Default: 120000.0")
                .defineInRange("heating_peak_celsius", 120000.0, 500.0, 10000000.0);

        heatingPenetrationDepth = b
                .comment("Number of block layers (along velocity) receiving temperature",
                         "injection per tick.  Scales with intensity.  Default: 3.")
                .defineInRange("heating_penetration_depth", 3, 1, 16);

        plasmaGasRateMbar = b
                .comment("Ionised air added to the MGE grid per heated block per tick (mbar).",
                         "Default: 0.5")
                .defineInRange("plasma_gas_rate_mbar", 0.5, 0.0, 100.0);

        heatingTickInterval = b
                .comment("Server-tick interval between heating updates.  Default: 2.")
                .defineInRange("heating_tick_interval", 2, 1, 20);

        clientSyncTickInterval = b
                .comment("Server-tick interval between ReentryStatePacket sends.  Default: 4.")
                .defineInRange("client_sync_tick_interval", 4, 1, 40);

        clientSyncRadius = b
                .comment("Block radius for client visual/sound sync packets.  Default: 256.")
                .defineInRange("client_sync_radius", 256.0, 32.0, 2048.0);

        b.pop();
    }
}
