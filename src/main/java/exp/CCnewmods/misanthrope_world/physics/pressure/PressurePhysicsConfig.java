package exp.CCnewmods.misanthrope_world.physics.pressure;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Forge SERVER config for the hull pressure differential simulation.
 *
 * <h3>Quick reference — stage pause timing</h3>
 * {@code stagePauseMultiplier} scales the per-block {@code stage_pause_ticks}
 * from {@code BlockPhysicsData.PressureData}. At the default JSON value of 100
 * ticks and multiplier 1.0, each inter-stage pause lasts exactly 5 seconds.
 * Set multiplier to 1.2 for ~6 seconds (max intended tension).
 */
public final class PressurePhysicsConfig {

    public static PressurePhysicsConfig INSTANCE;
    public static ForgeConfigSpec       SPEC;

    static {
        Pair<PressurePhysicsConfig, ForgeConfigSpec> pair =
                new ForgeConfigSpec.Builder().configure(PressurePhysicsConfig::new);
        INSTANCE = pair.getLeft();
        SPEC     = pair.getRight();
    }

    // ── Master switch ─────────────────────────────────────────────────────────

    public final ForgeConfigSpec.BooleanValue enabled;

    // ── Timing ────────────────────────────────────────────────────────────────

    /**
     * Multiplier applied to every block's {@code stage_pause_ticks} value.
     * 1.0 = default (5 seconds at JSON default 100 ticks).
     * 1.2 = 6 seconds (max intended). Default: 1.0.
     */
    public final ForgeConfigSpec.DoubleValue stagePauseMultiplier;

    /**
     * Ticks between full hull-face external pressure scans per ship.
     * Lower = more responsive but more expensive. Default: 10.
     */
    public final ForgeConfigSpec.IntValue exteriorScanInterval;

    /**
     * Ticks between interior BFS pressure samples per ship.
     * Lower = more accurate internal pressure but more expensive. Default: 5.
     */
    public final ForgeConfigSpec.IntValue interiorScanInterval;

    // ── Pressure scale ────────────────────────────────────────────────────────

    /**
     * Global multiplier applied to all external pressure values before they
     * are compared to block yield strengths. Use to scale difficulty:
     * < 1.0 = ships are harder to crush, > 1.0 = easier. Default: 1.0.
     */
    public final ForgeConfigSpec.DoubleValue externalPressureScale;

    /**
     * Whether vacuum (altitude-based very low external pressure) can cause
     * expansion breaches. If false, only positive ΔP (crush) is simulated.
     * Default: true.
     */
    public final ForgeConfigSpec.BooleanValue simulateExpansion;

    // ── Inflation ─────────────────────────────────────────────────────────────

    /**
     * Global multiplier for inflation rate. Scales how fast inflatable blocks
     * expand under positive ΔP. Default: 1.0.
     */
    public final ForgeConfigSpec.DoubleValue inflationRateScale;

    /**
     * Global multiplier for deflation rate (how fast inflation recovers when
     * ΔP drops). Default: 1.0.
     */
    public final ForgeConfigSpec.DoubleValue deflationRateScale;

    // ── Fatigue ───────────────────────────────────────────────────────────────

    /**
     * Whether elastic fatigue cracking is simulated (very slow stress
     * accumulation even below elastic yield). Default: true.
     */
    public final ForgeConfigSpec.BooleanValue simulateFatigue;

    // ── Catastrophic failure ─────────────────────────────────────────────────

    /**
     * Number of individual block breaches before catastrophic hull failure
     * triggers (ship disassembly). 0 = disabled. Default: 12.
     */
    public final ForgeConfigSpec.IntValue catastrophicBreachCount;

    // ── Water pressure ────────────────────────────────────────────────────────

    /**
     * Multiplier on water pressure from the stub (or real water mod).
     * Increase to make submarines experience higher pressure; decrease for
     * gentler underwater simulation. Default: 1.0.
     */
    public final ForgeConfigSpec.DoubleValue waterPressureScale;

    // ── Performance ───────────────────────────────────────────────────────────

    /**
     * Maximum number of ships evaluated per server tick.
     * Excess ships are deferred to subsequent ticks (round-robin).
     * Default: 8.
     */
    public final ForgeConfigSpec.IntValue maxShipsPerTick;

    /**
     * Maximum number of hull-face blocks evaluated per ship per scan.
     * Ships with more exposed hull blocks will be sub-sampled randomly.
     * Default: 512.
     */
    public final ForgeConfigSpec.IntValue maxHullFacesPerShip;

    // ── Creature pressure damage ──────────────────────────────────────────────

    /**
     * Master switch for the creature / entity pressure-damage system.
     * When false, no {@link exp.CCnewmods.misanthrope_world.physics.pressure.creature.CreaturePressureProfile}
     * is evaluated and no damage is applied to living entities.
     * Default: true.
     */
    public final ForgeConfigSpec.BooleanValue creaturePressureDamageEnabled;

    /**
     * Whether the creature pressure system also applies to players.
     * Set to {@code false} (the default) when ColdSweat is present so that
     * player pressure effects are handled by its trait pipeline instead.
     * Set to {@code true} when running without ColdSweat.
     * Default: false.
     */
    public final ForgeConfigSpec.BooleanValue creaturePressureDamagePlayer;

    // ── World-space pressure ──────────────────────────────────────────────────

    /**
     * Maximum number of world-space pressure boundary blocks evaluated
     * per level per tick. Blocks beyond this limit are deferred to the next
     * tick (round-robin within the active set).
     * Default: 256.
     */
    public final ForgeConfigSpec.IntValue maxWorldBlocksPerTick;

    /**
     * Ticks between full MGE composition re-samples for world-space boundary
     * blocks that are in the SAFE/ELASTIC region and not dirty.
     * Dirty blocks (from NeighborNotifyEvent) are always re-sampled immediately.
     * Default: 20 (1 second).
     */
    public final ForgeConfigSpec.IntValue worldBlockScanInterval;

    // ── Constructor ───────────────────────────────────────────────────────────

    private PressurePhysicsConfig(ForgeConfigSpec.Builder b) {
        b.comment("=== Hull Pressure Differential Physics ===").push("pressure");

        enabled = b
                .comment("Master switch. Default: true")
                .define("enabled", true);

        b.pop().push("timing");

        stagePauseMultiplier = b
                .comment("Multiplier on stage_pause_ticks. 1.0 = 5s, 1.2 = 6s. Default: 1.0")
                .defineInRange("stage_pause_multiplier", 1.0, 0.1, 10.0);

        exteriorScanInterval = b
                .comment("Ticks between exterior hull scans per ship. Default: 10")
                .defineInRange("exterior_scan_interval", 10, 1, 100);

        interiorScanInterval = b
                .comment("Ticks between interior BFS samples per ship. Default: 5")
                .defineInRange("interior_scan_interval", 5, 1, 50);

        b.pop().push("pressure_scale");

        externalPressureScale = b
                .comment("Scales all external pressure values. <1 = harder to crush. Default: 1.0")
                .defineInRange("external_pressure_scale", 1.0, 0.01, 100.0);

        simulateExpansion = b
                .comment("Simulate expansion breaches (vacuum/low external pressure). Default: true")
                .define("simulate_expansion", true);

        waterPressureScale = b
                .comment("Scales water pressure contribution. Default: 1.0")
                .defineInRange("water_pressure_scale", 1.0, 0.0, 100.0);

        b.pop().push("inflation");

        inflationRateScale = b
                .comment("Scales inflation rate for inflatable blocks. Default: 1.0")
                .defineInRange("inflation_rate_scale", 1.0, 0.0, 10.0);

        deflationRateScale = b
                .comment("Scales deflation (recovery) rate for inflatable blocks. Default: 1.0")
                .defineInRange("deflation_rate_scale", 1.0, 0.0, 10.0);

        b.pop().push("fatigue");

        simulateFatigue = b
                .comment("Enable elastic fatigue cracking below yield. Default: true")
                .define("simulate_fatigue", true);

        b.pop().push("failure");

        catastrophicBreachCount = b
                .comment("Block breaches before catastrophic disassembly. 0 = disabled. Default: 12")
                .defineInRange("catastrophic_breach_count", 12, 0, 1000);

        b.pop().push("performance");

        maxShipsPerTick = b
                .comment("Max ships evaluated per server tick. Default: 8")
                .defineInRange("max_ships_per_tick", 8, 1, 64);

        maxHullFacesPerShip = b
                .comment("Max hull-face blocks sampled per ship per scan. Default: 512")
                .defineInRange("max_hull_faces_per_ship", 512, 16, 4096);

        b.pop().push("world_space");

        maxWorldBlocksPerTick = b
                .comment("Max world-space pressure blocks evaluated per level per tick. Default: 256")
                .defineInRange("max_world_blocks_per_tick", 256, 8, 2048);

        worldBlockScanInterval = b
                .comment("Ticks between MGE re-samples for non-dirty world-space blocks. Default: 20")
                .defineInRange("world_block_scan_interval", 20, 1, 200);

        b.pop().push("creature_pressure");

        creaturePressureDamageEnabled = b
                .comment(
                        "Enable pressure-based damage for creatures (mobs, fish, players).",
                        "Profiles are loaded from data/<namespace>/creature_pressure/*.json.",
                        "Default: true"
                )
                .define("enabled", true);

        creaturePressureDamagePlayer = b
                .comment(
                        "Also apply creature pressure damage to players.",
                        "Leave false when ColdSweat is installed — it handles player effects",
                        "through its own trait pipeline. Enable only when ColdSweat is absent.",
                        "Default: false"
                )
                .define("damage_player", false);

        b.pop();
    }
}
