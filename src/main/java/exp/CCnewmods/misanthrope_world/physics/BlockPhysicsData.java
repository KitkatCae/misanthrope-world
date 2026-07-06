package exp.CCnewmods.misanthrope_world.physics;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Unified block physics record loaded from
 * {@code data/<ns>/material_properties/<path>.json}.
 *
 * <p>This replaces {@code InsulationData} entirely. Every field that was on
 * {@code InsulationData} is present here with identical semantics, so the
 * {@code InsulationRegistry} shim can delegate directly without any math.
 *
 * <p>All subsections ({@link HeatEmission}, {@link PhaseTransition},
 * {@link OffGas}, {@link Radiation}, {@link LightEmission},
 * {@link StructuralData}) are nullable — absent from JSON means the block does
 * not participate in that subsystem.
 *
 * <p>Mana-related fields ({@code mana_affinity}, {@code mana_capacity}, etc.)
 * are intentionally omitted — those belong to the forthcoming mana mod and will
 * be loaded by its own registry. The parser silently ignores any
 * {@code mana_*} keys so existing data files keep loading cleanly.
 */
public final class BlockPhysicsData {

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Block registry ID, or {@code null} if this entry is tag-based.
     */
    @Nullable
    public final ResourceLocation blockId;

    // ── Core thermal (1-to-1 with old InsulationData) ─────────────────────────

    /**
     * J/°C — how much energy this block absorbs before its temperature rises.
     */
    public final double thermalMass;

    /**
     * W/(m·K) — directional heat-flow rate through the material.
     * Higher = conducts faster (copper > stone > wood).
     * Distinct from {@link #insulationR}: conductivity describes the material
     * intrinsic rate; insulationR is the effective wall resistance used by the
     * structure scanner.
     */
    public final double conductivity;

    /**
     * R-value per block thickness — resistance to heat flow.
     * Used by {@code ThermalStructure} wall-stacking math.
     */
    public final double insulationR;

    /**
     * Fractional heat loss to ambient per tick (0–1).
     */
    public final double dissipationRate;

    /**
     * Whether this block seals gas exchange (airtight wall/solid).
     */
    public final boolean isAirtight;

    /**
     * Gas permeability fraction (0 = sealed, 1 = fully open).
     */
    public final double porousness;

    /**
     * Surface adhesion coefficient, used by particulate settling.
     */
    public final double adhesion;

    /**
     * Slab/stair thickness as a fraction of a full block (1.0 = full cube).
     * Multiplied into {@link #insulationR} and {@link #thermalMass} during
     * wall-stack R-value summation in {@code ThermalStructure}.
     */
    public final double thicknessFraction;

    /**
     * Whether this block responds to induction heaters.
     * Only metals and some minerals should be {@code true}.
     */
    public final boolean electricallyConductive;

    // ── Density ───────────────────────────────────────────────────────────────

    /**
     * Material density in kg/m³. Used for:
     * <ul>
     *   <li>Fluid hydrostatic column pressure (ρgh per block depth) in
     *       {@link exp.CCnewmods.misanthrope_world.physics.pressure.FluidPressureSampler}</li>
     *   <li>VS2 physics body mass calculation in MVS Engine</li>
     *   <li>Column load calculation in {@code StructuralStressField}</li>
     * </ul>
     *
     * <p>This is an intrinsic <em>material</em> property, not volumetric:
     * a slab of granite has the same {@code densityKgM3} as a full block.
     * Callers needing effective mass multiply by {@link #thicknessFraction} themselves.
     *
     * <p>Reference values: air ≈ 1.2, water = 1000, sandstone ≈ 1500,
     * stone/concrete ≈ 2400, granite ≈ 2700, iron ≈ 7800, lead ≈ 11300, lava ≈ 3100.
     *
     * <p>For fluid blocks (water, lava, modded fluids), set this in
     * {@code material_properties} JSON so {@code FluidPressureSampler} can
     * compute the correct hydrostatic constant without a separate fluid registry.
     */
    public final double densityKgM3;

    // ── Thermal cracking ──────────────────────────────────────────────────────

    /**
     * °C above ambient at which thermal expansion begins injecting crack stress.
     * {@code Double.NaN} = never cracks thermally (metals, firebrick).
     * Reference values: glass ~80, granite ~200, sandstone ~150, brick ~300.
     */
    public final double thermalCrackThreshold;

    /**
     * Crack intensity added to {@code CrackStateMap} per tick above threshold.
     * Reference values: glass 0.008 (brittle), granite 0.002, brick 0.001.
     */
    public final double thermalCrackRate;

    // ── Heat emission (replaces HeatSourceData for material_properties entries) ─

    /**
     * Non-null if this block emits heat. Takes over from the legacy
     * {@code thermal/heat_sources/} JSONs for any block that also has full
     * material-properties data. The two systems coexist: blocks listed only in
     * {@code heat_sources/} still work through {@code HeatSourceRegistry}.
     */
    @Nullable
    public final HeatEmission emission;

    // ── Phase transitions ─────────────────────────────────────────────────────

    /**
     * Ordered list of phase-change rules. Evaluated top-to-bottom each time a
     * trigger condition is checked; first matching rule wins.
     */
    public final List<PhaseTransition> phaseTransitions;

    // ── Off-gassing ───────────────────────────────────────────────────────────

    /**
     * Gas released when block temperature exceeds a threshold. Nullable.
     */
    @Nullable
    public final OffGas thermalOffGas;

    /**
     * Gas released passively at ambient conditions. Nullable.
     */
    @Nullable
    public final OffGas ambientOffGas;

    // ── Radiation / light ─────────────────────────────────────────────────────

    @Nullable
    public final RadiationData radiation;
    @Nullable
    public final LightEmissionData lightEmission;

    // ── Structural ────────────────────────────────────────────────────────────

    /**
     * Structural mechanics data. {@code null} = vanilla behaviour (no
     * Misanthrope structural tracking for this block, Minecollapse tags still
     * apply if present).
     */
    @Nullable
    public final StructuralData structural;

    /**
     * Pressure-differential response data. {@code null} = block does not
     * participate in pressure simulation (treated as rigid with infinite
     * strength for pressure purposes).
     */
    @Nullable
    public final PressureData pressure;

    // ── pH / chemical reactivity ──────────────────────────────────────────────

    /**
     * Surface pH of this block (0–14, 7.0 = neutral). Always present —
     * every block has a pH even if it never reacts to anything; this is the
     * value an aqueous film on the block's surface settles to in a neutral
     * atmosphere. Stone/concrete sit slightly alkaline (~9–10), most metals
     * are inert (7.0), organic/biological blocks trend mildly acidic (~5–6).
     *
     * <p>This is distinct from {@link #phReactivity} — {@code phValue} is
     * what the block <em>is</em>; {@code phReactivity} is how strongly it
     * <em>responds</em> to ambient pH that differs from its own.
     */
    public final double phValue;

    /**
     * Chemical reactivity to ambient pH exposure. {@code null} = block is
     * chemically inert (no corrosion/etching response regardless of ambient
     * gas composition) — the correct default for most blocks.
     */
    @Nullable
    public final PhReactivity phReactivity;

    // ── Misc flags ────────────────────────────────────────────────────────────

    public final boolean biological;
    public final boolean passiveHeatOutput;
    public final boolean heatDecoupled;
    public final boolean lavaImmune;
    public final boolean lavaContactStable;
    public final boolean fatiguImmune;
    public final boolean witherImmune;
    public final boolean glitch;
    public final double selfRepairRate;
    @Nullable
    public final ResourceLocation decayInto;
    @Nullable
    public final String customBehavior;
    @Nullable
    public final String note;
    @Nullable
    public final String disambiguation;
    public final boolean stub;

    // ── Snowlogging modifier ──────────────────────────────────────────────────

    /**
     * Non-null if this block is a Snow Real Magic snow-overlay form (e.g.
     * {@code snowrealmagic:slab}, {@code snowrealmagic:fence}).
     *
     * <p>When present, {@link BlockPhysicsRegistry#getWithSnowlog} uses these
     * values to additively modify the host block's physics rather than returning
     * this block's data standalone. The host block's data is fetched normally;
     * these deltas are then applied on top.
     *
     * <p>The {@code snow} block (layers=1–8) additionally sets
     * {@link SnowlogModifier#layerScale} {@code true}, which causes the caller
     * to scale {@code insRAdd} and {@code thermalMassAdd} by
     * {@code layers / 8.0} from the blockstate's {@code layers} property.
     */
    @Nullable
    public final SnowlogModifier snowlogModifier;

    // ── Constructor ───────────────────────────────────────────────────────────

    private BlockPhysicsData(Builder b) {
        this.blockId = b.blockId;
        this.thermalMass = b.thermalMass;
        this.conductivity = b.conductivity;
        this.insulationR = b.insulationR;
        this.dissipationRate = b.dissipationRate;
        this.isAirtight = b.isAirtight;
        this.porousness = b.porousness;
        this.adhesion = b.adhesion;
        this.thicknessFraction = b.thicknessFraction;
        this.electricallyConductive = b.electricallyConductive;
        this.densityKgM3 = b.densityKgM3;
        this.thermalCrackThreshold = b.thermalCrackThreshold;
        this.thermalCrackRate = b.thermalCrackRate;
        this.emission = b.emission;
        this.phaseTransitions = b.phaseTransitions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(b.phaseTransitions);
        this.thermalOffGas = b.thermalOffGas;
        this.ambientOffGas = b.ambientOffGas;
        this.radiation = b.radiation;
        this.lightEmission = b.lightEmission;
        this.structural = b.structural;
        this.pressure = b.pressure;
        this.phValue = b.phValue;
        this.phReactivity = b.phReactivity;
        this.biological = b.biological;
        this.passiveHeatOutput = b.passiveHeatOutput;
        this.heatDecoupled = b.heatDecoupled;
        this.lavaImmune = b.lavaImmune;
        this.lavaContactStable = b.lavaContactStable;
        this.fatiguImmune = b.fatiguImmune;
        this.witherImmune = b.witherImmune;
        this.glitch = b.glitch;
        this.selfRepairRate = b.selfRepairRate;
        this.decayInto = b.decayInto;
        this.customBehavior = b.customBehavior;
        this.note = b.note;
        this.disambiguation = b.disambiguation;
        this.stub = b.stub;
        this.snowlogModifier = b.snowlogModifier;
    }

    // ── Derived helpers (mirrors old InsulationData API) ─────────────────────

    /**
     * Effective heat conductance through this block (W/°C per unit area).
     * Infinite for air/gaps; higher = more heat flows per °C difference.
     */
    public double conductance() {
        return insulationR <= 0.0 ? Double.MAX_VALUE : 1.0 / insulationR;
    }

    /**
     * Returns a copy of this data with {@link #thicknessFraction} halved and
     * {@link #isAirtight} cleared — used for slab fallback in
     * {@code BlockPhysicsRegistry}.
     */
    public BlockPhysicsData asSlab() {
        Builder b = new Builder(this);
        b.thicknessFraction = this.thicknessFraction * 0.5;
        b.insulationR = this.insulationR * 0.5;
        b.thermalMass = this.thermalMass * 0.5;
        b.isAirtight = false;
        return new BlockPhysicsData(b);
    }

    // ── Well-known fallbacks ──────────────────────────────────────────────────

    public static final BlockPhysicsData AIR = new Builder()
            .thermalMass(1.0).conductivity(0.0).insulationR(0.0)
            .dissipationRate(0.5).isAirtight(false).porousness(1.0).adhesion(0.0)
            .thicknessFraction(1.0).electricallyConductive(false)
            .densityKgM3(1.2)
            .thermalCrackThreshold(Double.NaN).thermalCrackRate(0.0)
            .build();

    public static final BlockPhysicsData GENERIC_SOLID = new Builder()
            .thermalMass(40.0).conductivity(2.0).insulationR(0.4)
            .dissipationRate(0.05).isAirtight(true).porousness(0.0).adhesion(0.3)
            .thicknessFraction(1.0).electricallyConductive(false)
            .densityKgM3(2400.0)
            .thermalCrackThreshold(200.0).thermalCrackRate(0.002)
            .build();

    // ── Nested record types ───────────────────────────────────────────────────

    /**
     * Heat emission properties — a block that actively produces heat.
     * Replaces {@code HeatSourceData} for blocks defined via material_properties.
     */
    public record HeatEmission(
            double peakCelsius,
            double wattsPerBlock,
            boolean requiresOxygen,
            boolean isInduction,
            boolean isContinuous,
            @Nullable String activeWhenProperty,
            @Nullable String activeWhenValue,
            double oxygenConsumptionRate,
            double co2EmissionRate,
            /** One of: constant | linear_ramp | bell_curve | stepped */
            String heatProfileType,
            double structuralStressAbove,
            boolean inducePressureCheck
    ) {
        /**
         * Returns true if this source is currently active given the block state
         * and current O₂ partial pressure.
         */
        public boolean isActive(net.minecraft.world.level.block.state.BlockState state,
                                float o2Mbar) {
            if (requiresOxygen && o2Mbar <= 0f) return false;
            if (activeWhenProperty != null && !activeWhenProperty.isBlank()) {
                try {
                    for (var prop : state.getProperties()) {
                        if (prop.getName().equals(activeWhenProperty)) {
                            return state.getValue(prop).toString()
                                    .equalsIgnoreCase(activeWhenValue);
                        }
                    }
                    return false;
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Watts output scaled by O₂ availability.
         */
        public double effectiveWatts(float o2Mbar) {
            if (!requiresOxygen) return wattsPerBlock;
            return wattsPerBlock * Math.min(1f, o2Mbar / 209.5f);
        }
    }

    /**
     * A single phase-change rule: when {@code trigger} condition is met at
     * {@code tempThreshold} °C (and optionally other conditions), replace this
     * block with {@code resultBlock} or fire {@code resultEvent}.
     */
    public record PhaseTransition(
            PhaseTransitionTrigger trigger,
            double tempThreshold,       // °C; Double.NaN = not temperature-gated
            double pressureThreshold,   // kPa; Double.NaN = not pressure-gated
            @Nullable ResourceLocation resultBlock,
            @Nullable String resultEvent,
            boolean requiresOxygen,
            @Nullable String requiresAtmosphere,
            @Nullable String releases,   // gas registry key emitted on transition
            boolean reversible,
            @Nullable String note
    ) {
    }

    /**
     * Trigger conditions for phase transitions.
     */
    public enum PhaseTransitionTrigger {
        ON_MELT, ON_FREEZE, ON_FIRE, ON_CHAR, ON_FIRE_SMELT,
        ON_HEAT_ABOVE, ON_COOL_BELOW, ON_SOFTEN,
        ON_WATER_CONTACT, ON_PRESSURE_EXCEED, ON_PRESSURE_DROP,
        ON_IMPACT, ON_IGNITE, ON_CONTACT_ATMOSPHERE,
        ON_FIELD_LOSS, ON_EXCEED_TEMP, ON_SUBLIMATE
    }

    /**
     * Off-gassing rule. Used for both {@link BlockPhysicsData#thermalOffGas}
     * (temperature-driven) and {@link BlockPhysicsData#ambientOffGas}
     * (passive, condition-driven).
     */
    public record OffGas(
            /** MGE gas registry key, e.g. {@code "mge:co2"}. */
            String gasKey,
            /** mbar per tick at base rate. */
            float rateMbarPerTick,
            /** If true, rate multiplies linearly with (temp - threshold) / 1000. */
            boolean ratesScalesWithTemp,
            /** °C above which thermal off-gassing begins. NaN = always. */
            double thresholdCelsius,
            /** °C above which the block converts to {@link #sustainedResult}. */
            double sustainedAboveCelsius,
            /** Game ticks sustained above {@link #sustainedAboveCelsius} before conversion. */
            int sustainedTicks,
            @Nullable ResourceLocation sustainedResult,
            boolean requiresOxygen,
            /**
             * Ambient condition gate. One of:
             * {@code null} (always), {@code "contact_water"}, {@code "contact_air"},
             * {@code "submerged"}, or {@code "below_celsius:N"}.
             */
            @Nullable String condition,
            /** °C above which ambient off-gassing stops. NaN = never stops. */
            double stopsAboveCelsius
    ) {
    }

    /**
     * Radiation emitted by this block.
     */
    public record RadiationData(
            /** alpha | beta | gamma | alpha_beta | gamma_beta | thermal_decay | … */
            String type,
            float intensity,
            String halfLife,
            @Nullable String color,
            float cancerRisk
    ) {
    }

    /**
     * Passive light emission.
     */
    public record LightEmissionData(
            /** static | pulse | atmospheric_scaled */
            String type,
            int level,
            @Nullable String color,
            @Nullable String source
    ) {
    }

    /**
     * Structural mechanics data. Present only when the block participates in
     * the {@code StructuralStressField} simulation.
     */
    public record StructuralData(
            // ── Strength ──────────────────────────────────────────────────────
            double compressiveStrengthKpa,
            double tensileStrengthKpa,
            double shearStrengthKpa,
            /** 0–1; lower = more brittle (cracks sooner relative to failure). */
            double fractureToughness,
            // ── Temperature ───────────────────────────────────────────────────
            /** Anchor points for strength-vs-temperature curve.
             *  Each entry: {celsius, fractionOfFullStrength}. */
            List<StrengthPoint> strengthRetentionCurve,
            // ── Mass / load ───────────────────────────────────────────────────
            double densityKgM3,
            boolean isStructuralFrame,
            /** Blocks; structural frames transfer load laterally this far. */
            int loadTransferRange,
            // ── Failure ───────────────────────────────────────────────────────
            /** CRUMBLE | FRAGMENT_VS2 | CAVE_IN | LATTICE_COLLAPSE */
            FailureMode failureMode,
            /** Fraction of strength at which actual failure fires (≤ 1.0). */
            double failureThresholdFraction,
            /** Fraction at which CrackPropagator activates (< failureThreshold). */
            double crackThresholdFraction,
            // ── FRAGMENT_VS2 config ────────────────────────────────────────────
            int fragmentMinSize,
            double fragmentInitialVelocityScale,
            // ── CAVE_IN config ─────────────────────────────────────────────────
            int caveInRadiusOverride,
            // ── LATTICE_COLLAPSE config ────────────────────────────────────────
            @Nullable LatticeCollapseData latticeCollapse,
            // ── Shockwave response ─────────────────────────────────────────────
            /** null = use vanilla blast resistance. */
            @Nullable Double blastResistanceOverride,
            /** Multiplier on incoming shockwave strength (hollow = >1). */
            double shockwaveAmplification,
            /** Fraction of shockwave energy absorbed (0 = none, 1 = full). */
            double shockwaveAbsorption
    ) {
        /**
         * Returns the strength fraction at the given temperature via
         * linear interpolation of {@link #strengthRetentionCurve}.
         */
        public double strengthFractionAt(double celsius) {
            if (strengthRetentionCurve == null || strengthRetentionCurve.isEmpty()) return 1.0;
            StrengthPoint prev = strengthRetentionCurve.get(0);
            for (StrengthPoint p : strengthRetentionCurve) {
                if (celsius <= p.celsius()) {
                    if (p == prev) return prev.fraction();
                    double t = (celsius - prev.celsius()) / (p.celsius() - prev.celsius());
                    return prev.fraction() + t * (p.fraction() - prev.fraction());
                }
                prev = p;
            }
            return prev.fraction();
        }
    }

    /**
     * One anchor on the strength-retention temperature curve.
     */
    public record StrengthPoint(double celsius, double fraction) {
    }

    /**
     * LATTICE_COLLAPSE sub-config (atomic-lattice implosion).
     */
    public record LatticeCollapseData(
            /** IMPLODE | BLACK_HOLE */
            String mode,
            @Nullable ResourceLocation resultBlock,
            @Nullable ResourceLocation blackHoleEntity,
            int implodeDurationTicks,
            boolean chainToNeighbors,
            double chainChance
    ) {
    }

    /**
     * Failure mode enumeration used by {@code StructuralData}.
     */
    public enum FailureMode {
        CRUMBLE, FRAGMENT_VS2, CAVE_IN, LATTICE_COLLAPSE
    }

    // =========================================================================
    // PRESSURE MECHANICS
    // =========================================================================

    /**
     * Pressure-differential response data for a block.
     *
     * <p>Controls how the block behaves when internal and external gas/fluid
     * pressures differ. Present only for blocks that participate in the
     * pressure simulation (hull plates, windows, fabric, rubber, etc.).
     *
     * <h3>Yield curve model</h3>
     * Pressure response follows a material-specific yield curve:
     * <ul>
     *   <li><b>Below {@code elasticYieldMbar}</b> — elastic region.
     *       Block deforms visually (vertex displacement proportional to ΔP)
     *       but returns to original shape when ΔP drops. No permanent damage.</li>
     *   <li><b>Between {@code elasticYieldMbar} and {@code plasticYieldMbar}</b>
     *       — plastic region. Block advances to the next deformation stage.
     *       Stages tick up toward {@code deformationStageCount}. Between stages
     *       there is a configurable pause ({@code stagePauseTicks}) during which
     *       stress accumulates but the block does not change — the "tension pause"
     *       that builds dread before the next crunch.</li>
     *   <li><b>Above {@code ultimateStrengthMbar}</b> — immediate structural
     *       failure. Block is destroyed at the breach point and a
     *       {@link PressureBreachMode} event fires.</li>
     * </ul>
     *
     * <h3>Inflation</h3>
     * If {@code inflatable = true}, the block can expand outward into adjacent
     * air under positive ΔP (internal > external) before tearing:
     * <ul>
     *   <li>Inflation fraction: {@code ΔP / ultimateStrengthMbar}, clamped [0,1].</li>
     *   <li>At {@code maxInflationFraction}, the block occupies the full adjacent
     *       block space — the server tracks this as expanded volume.</li>
     *   <li>Above {@code maxInflationFraction}, the block tears (VENT failure).</li>
     *   <li>Inflation is reversible: when ΔP drops, the block shrinks back.</li>
     * </ul>
     *
     * <h3>Material archetypes in JSON</h3>
     * <pre>
     *   Stone/concrete:    elasticYield=800, plastic=800, ultimate=1200, stages=1 (brittle)
     *   Steel (iron block): elasticYield=2000, plastic=4000, ultimate=8000, stages=3, pauseTicks=120
     *   Wood:              elasticYield=400, plastic=600, ultimate=800, stages=2, pauseTicks=40
     *   Rubber/slime:      elasticYield=5, plastic=20, ultimate=60, stages=4, inflatable=true
     *   Fabric/wool:       elasticYield=2, plastic=10, ultimate=30, stages=4, inflatable=true, maxInflation=0.9
     *   Glass:             elasticYield=600, plastic=600, ultimate=620, stages=1, pauseTicks=0 (instant shatter)
     * </pre>
     */
    public record PressureData(
            // ── Yield thresholds (mbar differential) ─────────────────────────
            /** ΔP below which deformation is fully elastic (recoverable). */
            float elasticYieldMbar,
            /** ΔP at which plastic deformation stages begin. */
            float plasticYieldMbar,
            /** ΔP at which the block fails outright. */
            float ultimateStrengthMbar,

            // ── Stepped yield curve ───────────────────────────────────────────
            /**
             * Number of discrete plastic deformation stages [1, 4].
             * Each stage advances the block visually (deeper inward/outward warp)
             * and emits a sound event. Between stages: {@link #stagePauseTicks}.
             */
            int deformationStageCount,
            /**
             * Ticks between deformation stage advances while ΔP stays in the
             * plastic region. Gives the "tension pause" effect.
             * Default: 100 (5 seconds). Set to 0 for instant stage progression.
             */
            int stagePauseTicks,
            /**
             * Rate at which stress accumulates per tick in the elastic region
             * (as a fraction of elastic yield). Used for fatigue cracking.
             * Default: 0.0001 (very slow). Set to 0 to disable fatigue.
             */
            float elasticFatigueRate,

            // ── Inflation (expansion under positive ΔP) ───────────────────────
            /**
             * Whether this block can inflate outward into adjacent air before tearing.
             * Only meaningful for flexible materials (rubber, slime, fabric, wool,
             * membrane blocks). Hard blocks ignore this field.
             */
            boolean inflatable,
            /**
             * Maximum outward expansion as a fraction of one block space [0, 1].
             * At 1.0, the block occupies the full adjacent cell.
             * At this fraction, the block tears if ΔP is still above ultimate.
             * Default: 0.7 for most inflatables.
             */
            float maxInflationFraction,
            /**
             * Rate of inflation per tick per mbar above elastic yield.
             * {@code inflationAmount += inflationRatePerMbar * (ΔP - elasticYield)}.
             * Default: 0.001.
             */
            float inflationRatePerMbar,

            // ── Breach / failure mode ─────────────────────────────────────────
            /**
             * What happens when the block exceeds {@link #ultimateStrengthMbar}.
             */
            PressureBreachMode breachMode,

            // ── Compression vs tension asymmetry ─────────────────────────────
            /**
             * Multiplier applied to {@link #ultimateStrengthMbar} when the block
             * is in compression (external > internal). Materials like concrete and
             * stone are much stronger in compression than in tension.
             * Default: 1.0 (symmetric). Stone: ~8.0 (compressiveStrength >> tensile).
             */
            float compressionMultiplier,

            // ── Crack source integration ──────────────────────────────────────
            /**
             * Fraction of {@link #ultimateStrengthMbar} at which crack sources
             * are registered with {@code CrackPropagator}. Default: 0.6.
             */
            float crackThresholdFraction
    ) {
        /**
         * Returns the effective ultimate strength for the given stress sign.
         * @param compressive true if external pressure exceeds internal (crush)
         */
        public float effectiveUltimate(boolean compressive) {
            return compressive
                    ? ultimateStrengthMbar * compressionMultiplier
                    : ultimateStrengthMbar;
        }

        /**
         * Returns whether ΔP (always positive, absolute value) is in the
         * elastic, plastic, or failure region.
         * @param absDeltaMbar absolute value of pressure differential
         * @param compressive  true = external exceeds internal
         */
        public Region region(float absDeltaMbar, boolean compressive) {
            float ultimate = effectiveUltimate(compressive);
            if (absDeltaMbar >= ultimate)              return Region.FAILURE;
            if (absDeltaMbar >= plasticYieldMbar)      return Region.PLASTIC;
            if (absDeltaMbar >= elasticYieldMbar)      return Region.ELASTIC;
            return Region.SAFE;
        }

        public enum Region { SAFE, ELASTIC, PLASTIC, FAILURE }
    }

    /**
     * What happens when a block exceeds its ultimate pressure strength.
     *
     * <ul>
     *   <li>{@code CRUMBLE} — block is destroyed inward (crush) or outward
     *       (tension tear), drops as items. Uses existing {@link FailureDispatcher}.</li>
     *   <li>{@code VENT} — block is destroyed, creating a pressure vent opening.
     *       Gas/fluid rushes through the breach. MGE EnvironmentGrid is notified
     *       at the breach point.</li>
     *   <li>{@code SHATTER} — brittle instant failure (glass, ceramics). Sends
     *       glass-shatter particles and sound. Drops items at high velocity.</li>
     *   <li>{@code IMPLODE} — inward catastrophic collapse (only when compressive).
     *       Triggers LATTICE_COLLAPSE failure mode on the FailureDispatcher.</li>
     *   <li>{@code TEAR} — fabric/membrane rip. Block is destroyed; inflatable
     *       volume immediately collapses. Gas vents. Drops item if repairable.</li>
     * </ul>
     */
    public enum PressureBreachMode {
        CRUMBLE, VENT, SHATTER, IMPLODE, TEAR
    }

    // =========================================================================
    // PH / CHEMICAL REACTIVITY
    // =========================================================================

    /**
     * Describes how a block's structural strength degrades under sustained
     * exposure to ambient pH that differs from its own {@link #phValue}.
     *
     * <h3>Exposure model</h3>
     * Each tick, {@code StructuralStressField} reads the cumulative acid/base
     * "load" at a block's position via
     * {@code exp.CCnewmods.mge.grid.EnvironmentGrid.getComposition(level, pos)}:
     * for every gas present, {@code (7.0 - gas.phValue()) * partialPressureMbar}
     * is summed. This is <em>not</em> a true pH average — it is a cumulative
     * exposure load that scales directly with both how extreme a gas's pH is
     * and how much of it is actually present. A trace of a strong acid gas and
     * a large volume of a weak acid gas can produce comparable load.
     *
     * <p>Positive load = net acidic exposure; negative load = net alkaline
     * exposure. The relevant rate ({@link #acidCorrosionRatePerLoad} or
     * {@link #baseCorrosionRatePerLoad}) is selected by the sign of the load,
     * then scaled by the magnitude.
     *
     * <h3>Effect on structural strength</h3>
     * Accumulated corrosion is tracked as a fractional strength loss, applied
     * the same way {@link StructuralData#strengthFractionAt} applies thermal
     * weakening — as an additional multiplicative factor on
     * {@code effectiveStress} in {@code StructuralStressField.evaluateBlock}.
     * A block at 100% corrosion accumulation behaves as if its strength had
     * fallen to {@link #minStrengthFraction} of nominal.
     *
     * <p>Corrosion accumulation is one-directional (it does not heal on its
     * own) unless {@link #selfRepairFraction} is non-zero, mirroring
     * {@link BlockPhysicsData#selfRepairRate} but scoped to chemical damage
     * specifically (e.g. a self-sealing material, or a magical ward).
     *
     * <h3>JSON authoring guidance</h3>
     * <pre>
     *   Mild steel (iron-family):  acidRate=0.00004  baseRate=0.000005  resistance=1.0
     *   Stainless / nickel alloys: acidRate=0.000006 baseRate=0.000002 resistance=1.0
     *   Copper/bronze:             acidRate=0.00003  baseRate=0.00001  resistance=1.0
     *   Limestone/marble:          acidRate=0.0002   baseRate=0.0      resistance=1.0
     *   Cloth/fabric sail:         acidRate=0.0008   baseRate=0.0003   resistance=1.0
     *   Glass:                     acidRate=0.00001  baseRate=0.00015  resistance=1.0 (etched by base, not acid)
     * </pre>
     * Resistant blocks should not omit {@code ph_reactivity} entirely if they
     * still need a non-default {@link BlockPhysicsData#phValue} for some other
     * block to react <em>to</em> them — omit only when the block neither reacts
     * to pH nor needs to be a meaningful pH source itself.
     */
    public record PhReactivity(
            /**
             * Strength-loss fraction accumulated per tick, per unit of positive
             * (acidic) cumulative load. A typical mild steel block exposed to a
             * sustained SO₂ atmosphere accumulates noticeable corrosion over
             * real-world-minutes of play time at this default scale.
             */
            double acidCorrosionRatePerLoad,
            /**
             * Strength-loss fraction accumulated per tick, per unit of negative
             * (alkaline) cumulative load magnitude. Most materials resist base
             * exposure far better than acid exposure — default asymmetric.
             */
            double baseCorrosionRatePerLoad,
            /**
             * Multiplier applied to both rates above. 1.0 = baseline material
             * (use the rates as authored). Lower = more resistant (e.g. 0.05 for
             * a corrosion-immune turbine fin alloy). This is the single field a
             * "resistant variant" of an otherwise-identical material should
             * override, keeping the base rates shared across a material family.
             */
            double resistanceFactor,
            /**
             * Floor on {@link StructuralData#strengthFractionAt}-style strength
             * fraction once corrosion accumulation reaches 1.0 (fully corroded).
             * Mirrors the thermal curve's behaviour of never quite reaching zero
             * strength instantly. Default 0.05 (95% strength loss at full
             * corrosion).
             */
            double minStrengthFraction,
            /**
             * Fractional self-repair of accumulated corrosion per tick (0–1).
             * 0.0 = corrosion is permanent (the correct default for ordinary
             * materials). Non-zero values represent self-sealing surfaces,
             * passivation layers that regenerate, or magical wards.
             */
            double selfRepairFraction
    ) {
        /**
         * Returns the accumulated-corrosion strength multiplier for a given
         * corrosion accumulation fraction [0, 1], linearly interpolated between
         * 1.0 (no corrosion) and {@link #minStrengthFraction} (fully corroded).
         */
        public double strengthFractionAt(double corrosionAccumulation) {
            double c = Math.max(0.0, Math.min(1.0, corrosionAccumulation));
            return 1.0 - c * (1.0 - minStrengthFraction);
        }
    }

    /**
     * Describes how a Snow Real Magic snow-overlay block modifies its host.
     *
     * <p>All fields are additive deltas applied to the host block's physics
     * when that host is snowlogged. The host's own JSON values are the base;
     * these are stacked on top.
     *
     * <ul>
     *   <li>{@code insRAdd} — R-value added to host's {@code insulationR}.</li>
     *   <li>{@code thermalMassAdd} — J/°C added to host's {@code thermalMass}.</li>
     *   <li>{@code porousnessSubtract} — subtracted from host's {@code porousness}
     *       (clamped ≥ 0). Zero for fence/fence_gate which remain open.</li>
     *   <li>{@code forceAirtight} — if {@code true} and host
     *       {@code porousness - porousnessSubtract ≤ 0.05}, set
     *       {@code isAirtight = true}.</li>
     *   <li>{@code layerScale} — only {@code true} for {@code snowrealmagic:snow}.
     *       When set, caller should scale {@code insRAdd} and {@code thermalMassAdd}
     *       by {@code layers / 8.0} from the blockstate's {@code layers} property
     *       before applying.</li>
     * </ul>
     */
    public record SnowlogModifier(
            double insRAdd,
            double thermalMassAdd,
            double porousnessSubtract,
            boolean forceAirtight,
            boolean layerScale
    ) {}

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        @Nullable
        ResourceLocation blockId;
        double thermalMass = 40.0;
        double conductivity = 2.0;
        double insulationR = 0.4;
        double dissipationRate = 0.05;
        boolean isAirtight = true;
        double porousness = 0.0;
        double adhesion = 0.3;
        double thicknessFraction = 1.0;
        boolean electricallyConductive = false;
        double densityKgM3 = 2400.0; // generic stone/concrete default
        double thermalCrackThreshold = 200.0;
        double thermalCrackRate = 0.002;
        @Nullable
        HeatEmission emission;
        @Nullable
        List<PhaseTransition> phaseTransitions;
        @Nullable
        OffGas thermalOffGas;
        @Nullable
        OffGas ambientOffGas;
        @Nullable
        RadiationData radiation;
        @Nullable
        LightEmissionData lightEmission;
        @Nullable
        StructuralData structural;
        @Nullable
        PressureData pressure;
        double phValue = 7.0;
        @Nullable
        PhReactivity phReactivity;
        boolean biological = false;
        boolean passiveHeatOutput = false;
        boolean heatDecoupled = false;
        boolean lavaImmune = false;
        boolean lavaContactStable = false;
        boolean fatiguImmune = false;
        boolean witherImmune = false;
        boolean glitch = false;
        double selfRepairRate = 0.0;
        @Nullable
        ResourceLocation decayInto;
        @Nullable
        String customBehavior;
        @Nullable
        String note;
        @Nullable
        String disambiguation;
        boolean stub = false;
        @Nullable
        SnowlogModifier snowlogModifier;

        public Builder() {
        }

        /**
         * Copy constructor — used by {@link BlockPhysicsData#asSlab()}.
         */
        Builder(BlockPhysicsData src) {
            this.blockId = src.blockId;
            this.thermalMass = src.thermalMass;
            this.conductivity = src.conductivity;
            this.insulationR = src.insulationR;
            this.dissipationRate = src.dissipationRate;
            this.isAirtight = src.isAirtight;
            this.porousness = src.porousness;
            this.adhesion = src.adhesion;
            this.thicknessFraction = src.thicknessFraction;
            this.electricallyConductive = src.electricallyConductive;
            this.densityKgM3 = src.densityKgM3;
            this.thermalCrackThreshold = src.thermalCrackThreshold;
            this.thermalCrackRate = src.thermalCrackRate;
            this.emission = src.emission;
            this.phaseTransitions = src.phaseTransitions.isEmpty() ? null
                    : new java.util.ArrayList<>(src.phaseTransitions);
            this.thermalOffGas = src.thermalOffGas;
            this.ambientOffGas = src.ambientOffGas;
            this.radiation = src.radiation;
            this.lightEmission = src.lightEmission;
            this.structural = src.structural;
            this.pressure = src.pressure;
            this.phValue = src.phValue;
            this.phReactivity = src.phReactivity;
            this.biological = src.biological;
            this.passiveHeatOutput = src.passiveHeatOutput;
            this.heatDecoupled = src.heatDecoupled;
            this.lavaImmune = src.lavaImmune;
            this.lavaContactStable = src.lavaContactStable;
            this.fatiguImmune = src.fatiguImmune;
            this.witherImmune = src.witherImmune;
            this.glitch = src.glitch;
            this.selfRepairRate = src.selfRepairRate;
            this.decayInto = src.decayInto;
            this.customBehavior = src.customBehavior;
            this.note = src.note;
            this.disambiguation = src.disambiguation;
            this.stub = src.stub;
            this.snowlogModifier = src.snowlogModifier;
        }

        public Builder thermalMass(double v) {
            thermalMass = v;
            return this;
        }

        public Builder conductivity(double v) {
            conductivity = v;
            return this;
        }

        public Builder insulationR(double v) {
            insulationR = v;
            return this;
        }

        public Builder dissipationRate(double v) {
            dissipationRate = v;
            return this;
        }

        public Builder isAirtight(boolean v) {
            isAirtight = v;
            return this;
        }

        public Builder porousness(double v) {
            porousness = v;
            return this;
        }

        public Builder adhesion(double v) {
            adhesion = v;
            return this;
        }

        public Builder thicknessFraction(double v) {
            thicknessFraction = v;
            return this;
        }

        public Builder electricallyConductive(boolean v) {
            electricallyConductive = v;
            return this;
        }

        public Builder densityKgM3(double v) {
            densityKgM3 = v;
            return this;
        }

        public Builder thermalCrackThreshold(double v) {
            thermalCrackThreshold = v;
            return this;
        }

        public Builder thermalCrackRate(double v) {
            thermalCrackRate = v;
            return this;
        }

        public Builder emission(HeatEmission v) {
            emission = v;
            return this;
        }

        public Builder structural(StructuralData v) {
            structural = v;
            return this;
        }

        public Builder pressure(PressureData v) {
            pressure = v;
            return this;
        }

        public Builder phValue(double v) {
            phValue = v;
            return this;
        }

        public Builder phReactivity(PhReactivity v) {
            phReactivity = v;
            return this;
        }

        public Builder note(String v) {
            note = v;
            return this;
        }

        public BlockPhysicsData build() {
            return new BlockPhysicsData(this);
        }
    }
}
