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
            .thermalCrackThreshold(Double.NaN).thermalCrackRate(0.0)
            .build();

    public static final BlockPhysicsData GENERIC_SOLID = new Builder()
            .thermalMass(40.0).conductivity(2.0).insulationR(0.4)
            .dissipationRate(0.05).isAirtight(true).porousness(0.0).adhesion(0.3)
            .thicknessFraction(1.0).electricallyConductive(false)
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

        public Builder note(String v) {
            note = v;
            return this;
        }

        public BlockPhysicsData build() {
            return new BlockPhysicsData(this);
        }
    }
}
