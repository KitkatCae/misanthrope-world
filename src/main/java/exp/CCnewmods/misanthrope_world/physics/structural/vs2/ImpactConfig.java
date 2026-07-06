package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Forge SERVER config for the ship-impact crater and embedding system.
 *
 * <p>Registered via MWorld's own setup (see {@code ImpactSystemSetup}), ported
 * from MVSE where it was registered in {@code Misanthrope_vs_engine.commonSetup}
 * aerodynamics configs.</p>
 *
 * <h3>Speed scale</h3>
 * VS2 velocity is in blocks/tick. Multiply × 20 for blocks/s (= m/s).
 * A ship travelling at 20 blocks/tick hits the ground at 400 m/s — well into
 * crater-forming territory for most materials.
 *
 * <h3>Impact modes</h3>
 * <ul>
 *   <li><b>Crater</b>: ship is stopped/deflected; world terrain is excavated in
 *       a roughly spherical cavity scaled to kinetic energy × material hardness.</li>
 *   <li><b>Embed</b>: if the ship is moving slowly enough (relative to material
 *       hardness), it is halted but left partially inside the terrain; the blocks
 *       it occupied are NOT regenerated — the ship is geometrically embedded.
 *       VS2 ship remains but velocity is zeroed.</li>
 *   <li><b>Bounce</b>: elastic materials (gravel, sand, bouncy slabs) partially
 *       reflect velocity perpendicular to the impact normal.</li>
 * </ul>
 *
 * <h3>Reversion</h3>
 * Excavated blocks are stored in a per-impact {@link ImpactMemento}. A
 * configurable timer counts down; when the ship is removed or disassembled
 * from the embed position, the world blocks revert to their original state.
 * This prevents permanent terrain deformation from transient crashes.
 */
public final class ImpactConfig {

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    public static ImpactConfig INSTANCE;
    public static ForgeConfigSpec SPEC;

    static {
        Pair<ImpactConfig, ForgeConfigSpec> pair =
                new ForgeConfigSpec.Builder().configure(ImpactConfig::new);
        INSTANCE = pair.getLeft();
        SPEC     = pair.getRight();
    }

    // -------------------------------------------------------------------------
    // Thresholds
    // -------------------------------------------------------------------------

    /**
     * Minimum ship speed (m/s) at the moment of world contact for ANY impact
     * effect to trigger. Below this, VS2's normal collision handling takes over.
     * Default: 15.0 (≈ 0.75 blocks/tick — a significant but survivable crash).
     */
    public final ForgeConfigSpec.DoubleValue minImpactSpeedMs;

    /**
     * Speed threshold (m/s) below which the ship embeds rather than cratering.
     * Between {@link #minImpactSpeedMs} and this value, the ship embeds.
     * Above this value, it craters. Default: 60.0.
     */
    public final ForgeConfigSpec.DoubleValue embedMaxSpeedMs;

    /**
     * Speed threshold (m/s) above which the impact is considered "hypersonic"
     * and the crater radius is multiplied by {@link #hypersonicCraterMultiplier}.
     * Default: 343.0 (Mach 1 — supersonic crashes produce larger craters).
     */
    public final ForgeConfigSpec.DoubleValue hypersonicThresholdMs;

    // -------------------------------------------------------------------------
    // Crater parameters
    // -------------------------------------------------------------------------

    /**
     * Base crater radius in blocks at {@link #embedMaxSpeedMs}.
     * Actual radius = baseRadius × (speed / embedMaxSpeedMs)^0.5 × materialFactor.
     * Default: 4.0 blocks.
     */
    public final ForgeConfigSpec.DoubleValue baseCraterRadius;

    /**
     * Maximum crater radius in blocks regardless of speed or mass.
     * Prevents catastrophic world destruction. Default: 32.0.
     */
    public final ForgeConfigSpec.DoubleValue maxCraterRadius;

    /**
     * Multiplier applied to crater radius when speed exceeds
     * {@link #hypersonicThresholdMs}. Default: 2.5.
     */
    public final ForgeConfigSpec.DoubleValue hypersonicCraterMultiplier;

    /**
     * How much ship mass (kg from VS2) contributes to crater radius.
     * craterRadius += massContribution × log10(shipMass / 1000). Default: 1.2.
     */
    public final ForgeConfigSpec.DoubleValue massContribution;

    /**
     * Depth-to-radius ratio for the crater shape. 1.0 = hemisphere. 0.5 = shallow
     * bowl. Default: 0.6 (slightly shallower than a hemisphere, more realistic).
     */
    public final ForgeConfigSpec.DoubleValue craterDepthRatio;

    /**
     * Fraction of excavated blocks that are dropped as items vs simply destroyed.
     * 0.0 = all vaporised. 1.0 = all dropped. Default: 0.15 (most material is
     * pulverised; only 15% survives as loot).
     */
    public final ForgeConfigSpec.DoubleValue dropFraction;

    // -------------------------------------------------------------------------
    // Embedding parameters
    // -------------------------------------------------------------------------

    /**
     * Fraction of the ship's bounding box that must penetrate terrain before the
     * ship is considered "embedded" and VS2 velocity is zeroed. Default: 0.25
     * (25% overlap triggers a halt).
     */
    public final ForgeConfigSpec.DoubleValue embedOverlapFraction;

    /**
     * How many blocks deep the ship can push into terrain during embed.
     * Blocks that would be inside the ship's bounding box are cleared to allow
     * the geometry to fit. Default: 3.
     */
    public final ForgeConfigSpec.IntValue embedPenetrationDepth;

    // -------------------------------------------------------------------------
    // Reversion
    // -------------------------------------------------------------------------

    /**
     * Ticks before a crater or embed site automatically reverts (terrain restored).
     * Set to 0 to disable reversion entirely (permanent craters).
     * Default: 12000 (10 minutes).
     */
    public final ForgeConfigSpec.IntValue reversionDelayTicks;

    /**
     * If true, terrain reversion is triggered immediately when the ship that
     * caused the impact is disassembled or deleted from VS2, regardless of the
     * reversion timer. Default: true.
     */
    public final ForgeConfigSpec.BooleanValue revertOnShipRemoval;

    /**
     * Maximum number of simultaneous impact mementos held in memory per level.
     * When exceeded, oldest mementos revert and are discarded. Default: 64.
     */
    public final ForgeConfigSpec.IntValue maxMementosPerLevel;

    // -------------------------------------------------------------------------
    // Material interaction
    // -------------------------------------------------------------------------

    /**
     * Hardness multiplier for blocks tagged {@code minecraft:needs_diamond_tool}.
     * These resist excavation. Default: 3.0 (diamond-tier blocks require 3× more
     * kinetic energy to excavate per block).
     */
    public final ForgeConfigSpec.DoubleValue hardMaterialFactor;

    /**
     * Elasticity factor [0, 1] for blocks tagged {@code minecraft:mineable/shovel}
     * (sand, gravel, soul sand, etc.). Ships bouncing off these surfaces retain
     * this fraction of their perpendicular velocity component. Default: 0.4.
     */
    public final ForgeConfigSpec.DoubleValue softMaterialElasticity;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    private ImpactConfig(ForgeConfigSpec.Builder b) {

        b.comment("=== Ship Impact: Craters & Embedding ===").push("impact");

        minImpactSpeedMs = b
                .comment("Minimum ship speed (m/s) for any impact effect. Below this,",
                         "VS2 handles collision normally. Default: 15.0")
                .defineInRange("min_impact_speed_ms", 15.0, 1.0, 10000.0);

        embedMaxSpeedMs = b
                .comment("Speed (m/s) below which impact results in embedding rather than",
                         "cratering. Above this speed a crater is formed. Default: 60.0")
                .defineInRange("embed_max_speed_ms", 60.0, 5.0, 10000.0);

        hypersonicThresholdMs = b
                .comment("Speed (m/s) above which hypersonic crater multiplier applies.",
                         "Default: 343.0 (Mach 1).")
                .defineInRange("hypersonic_threshold_ms", 343.0, 50.0, 100000.0);

        b.pop().push("crater");

        baseCraterRadius = b
                .comment("Base crater radius (blocks) at embed_max_speed_ms. Scales with",
                         "sqrt(speed/embedMaxSpeed) × material factor. Default: 4.0")
                .defineInRange("base_radius", 4.0, 1.0, 64.0);

        maxCraterRadius = b
                .comment("Hard cap on crater radius in blocks. Default: 32.0")
                .defineInRange("max_radius", 32.0, 4.0, 256.0);

        hypersonicCraterMultiplier = b
                .comment("Radius multiplier above hypersonic_threshold_ms. Default: 2.5")
                .defineInRange("hypersonic_multiplier", 2.5, 1.0, 10.0);

        massContribution = b
                .comment("Radius bonus = massContribution × log10(shipMass / 1000).",
                         "Heavier ships make bigger craters. Default: 1.2")
                .defineInRange("mass_contribution", 1.2, 0.0, 10.0);

        craterDepthRatio = b
                .comment("Depth-to-radius ratio (1.0 = hemisphere, 0.5 = shallow bowl).",
                         "Default: 0.6")
                .defineInRange("depth_ratio", 0.6, 0.1, 2.0);

        dropFraction = b
                .comment("Fraction of excavated blocks dropped as items [0, 1].",
                         "0 = all vaporised. Default: 0.15")
                .defineInRange("drop_fraction", 0.15, 0.0, 1.0);

        b.pop().push("embedding");

        embedOverlapFraction = b
                .comment("Fraction of ship AABB overlapping solid terrain before embed",
                         "halt triggers. Default: 0.25")
                .defineInRange("overlap_fraction", 0.25, 0.01, 1.0);

        embedPenetrationDepth = b
                .comment("Max blocks the ship can push into terrain (blocks cleared to fit).",
                         "Default: 3")
                .defineInRange("penetration_depth", 3, 1, 16);

        b.pop().push("reversion");

        reversionDelayTicks = b
                .comment("Ticks before crater/embed site reverts to original terrain.",
                         "0 = permanent (no reversion). Default: 12000 (10 min).")
                .defineInRange("reversion_delay_ticks", 12000, 0, 72000);

        revertOnShipRemoval = b
                .comment("Revert terrain immediately when the impacting ship is removed.",
                         "Default: true")
                .define("revert_on_ship_removal", true);

        maxMementosPerLevel = b
                .comment("Max simultaneous impact records per level.",
                         "Oldest revert first when exceeded. Default: 64")
                .defineInRange("max_mementos_per_level", 64, 1, 1024);

        b.pop().push("materials");

        hardMaterialFactor = b
                .comment("Hardness multiplier for needs_diamond_tool blocks.",
                         "These blocks resist excavation. Default: 3.0")
                .defineInRange("hard_material_factor", 3.0, 1.0, 20.0);

        softMaterialElasticity = b
                .comment("Elasticity (bounce) factor for mineable/shovel blocks [0, 1].",
                         "Ships retain this fraction of perpendicular speed on bounce.",
                         "Default: 0.4")
                .defineInRange("soft_material_elasticity", 0.4, 0.0, 1.0);

        b.pop();
    }
}
