package exp.CCnewmods.misanthrope_world.physics.simulation;

import exp.CCnewmods.misanthrope_world.physics.structure.ThermalStructure;
import exp.CCnewmods.misanthrope_world.physics.structure.ThermalStructure.HeatSourceEntry;
import exp.CCnewmods.misanthrope_world.physics.structure.ThermalStructure.WallFace;
import exp.CCnewmods.misanthrope_world.physics.structure.ThermalStructure.GapFace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-tick thermal physics simulation for a {@link ThermalStructure}.
 * <p>
 * ── Physics model ─────────────────────────────────────────────────────────────
 * <p>
 * Each tick this computes:
 * <p>
 * Q_in   = Σ source.effectiveWatts(o2Mbar) × bellowsBoost
 * Q_wall = Σ wallFace.conductance() × (T_interior - T_ambient) / volume
 * Q_gap  = gapCount × GAP_CONDUCTANCE × (T_interior - T_ambient) / volume
 * Q_chimney = chimneyDraft × CHIMNEY_CONDUCTANCE × (T_interior - T_ambient)
 * Q_net  = Q_in - Q_wall - Q_gap - Q_chimney
 * <p>
 * ΔT = Q_net / thermalMass                 (Newton's law of cooling)
 * T_interior += ΔT × TICK_SCALE
 * <p>
 * Temperature is also spatially distributed across interior cells — cells
 * closer to heat sources are hotter; cells near gaps are cooler.  This
 * produces the rotary kiln temperature gradient and dome kiln roof-is-hottest
 * pattern without any special-casing.
 * <p>
 * ── Spatial temperature distribution ─────────────────────────────────────────
 * <p>
 * The spatially resolved temperature at each interior cell is:
 * <p>
 * T_cell = T_ambient + (T_interior - T_ambient) × spatialWeight(cell)
 * <p>
 * spatialWeight is computed once per scan as the inverse of the cell's
 * normalised distance from the nearest heat source, biased by the gap
 * distance (cells near gaps bleed toward ambient).
 * <p>
 * These per-cell temperatures are written to the {@link exp.CCnewmods.mge.grid.EnvironmentGrid}
 * via {@link exp.CCnewmods.misanthrope_core.physics.field.ThermalField}.
 * <p>
 * ── Integration with FurnaceRecipeProcessor ───────────────────────────────────
 * <p>
 * FurnaceRecipeProcessor calls {@link #getTemperatureAt(BlockPos)} to get the
 * temperature at a specific item position — no longer reads a hardcoded BE field.
 * <p>
 * ── Integration with FurnaceEnvironmentSampler ────────────────────────────────
 * <p>
 * The sampler's O₂ reading and bellows boost are passed in each tick.
 * O₂ scales source output; bellows adds a boost multiplier on top.
 * <p>
 * ── Sealed structure O₂ depletion ────────────────────────────────────────────
 * <p>
 * Sealed structures consume O₂ from the MGE gas grid at the source positions.
 * When O₂ drops to zero, oxygen-requiring sources output 0W and the fire dies.
 * Non-oxygen sources (electric, induction) are unaffected.
 * Gas depletion is handled by writing to EnvironmentGrid — the diffusion tick
 * propagates it naturally.
 */
public final class ThermalSimulation {

    // ── Constants ─────────────────────────────────────────────────────────────

    /**
     * Conductance through a gap face (W/°C). High — gaps leak heat freely.
     */
    private static final double GAP_CONDUCTANCE = 8.0;

    /**
     * Conductance added per chimney block of height (W/°C).
     */
    private static final double CHIMNEY_CONDUCTANCE_PER_BLOCK = 0.5;

    /**
     * Tick-to-joule scale factor (game ticks to SI seconds, ~1 tick = 0.05s).
     */
    public static final double TICK_SCALE = 0.05;

    /**
     * Minimum interior temperature — never goes below ambient.
     */
    private static final double MIN_ABOVE_AMBIENT = 0.0;

    /**
     * Spatial weight falloff per block from nearest heat source.
     */
    private static final double SPATIAL_FALLOFF = 0.12;

    // ── State ─────────────────────────────────────────────────────────────────

    private final ThermalStructure structure;

    /**
     * Current average interior temperature in °C.
     */
    private double interiorTemp = Double.NaN; // NaN = uninitialised, set from ambient on first tick

    /**
     * Per-cell spatial weight cache — built once after scan, reused each tick.
     */
    private final Map<BlockPos, Double> spatialWeights;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ThermalSimulation(ThermalStructure structure) {
        this.structure = structure;
        this.spatialWeights = buildSpatialWeights(structure);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    /**
     * Advance the simulation by one game tick.
     *
     * @param level          server level (for gas grid reads/writes)
     * @param ambientCelsius ambient temperature at the structure's location
     * @param o2Mbar         O₂ partial pressure at the intake position
     * @param bellowsBoost   additional multiplier from bellows [1.0, ~2.0]
     * @return the new average interior temperature in °C
     */
    public double tick(ServerLevel level, double ambientCelsius,
                       float o2Mbar, float bellowsBoost) {
        // Initialise from ambient on first tick
        if (Double.isNaN(interiorTemp)) interiorTemp = ambientCelsius;

        double deltaT = interiorTemp - ambientCelsius;

        // ── Heat input ────────────────────────────────────────────────────────
        double qIn = 0;
        for (HeatSourceEntry src : structure.heatSources) {
            if (!src.isActive(o2Mbar)) continue;
            double watts = src.effectiveWatts(o2Mbar) * bellowsBoost;
            qIn += watts;

            // Consume O₂ for oxygen-requiring sources (write to gas grid)
            if (src.requiresOxygen() && o2Mbar > 0f) {
                float consumed = Math.min(o2Mbar, (float) (watts * 0.0001)); // tiny per-tick draw
                exp.CCnewmods.mge.grid.EnvironmentGrid.addGas(
                        level, src.pos(),
                        exp.CCnewmods.mge.gas.GasRegistry.OXYGEN, -consumed);
            }
        }

        // Induction sources directly heat conductive wall blocks — skip air heating
        // for those sources (handled separately in ThermalField.tickInduction)
        double inductionWatts = structure.heatSources.stream()
                .filter(s -> s.isInduction() && s.isActive(o2Mbar))
                .mapToDouble(s -> s.wattsPerBlock())
                .sum();
        qIn -= inductionWatts; // don't double-count induction in air temp

        // ── Heat loss through walls ────────────────────────────────────────────
        double qWall = 0;
        for (WallFace face : structure.wallFaces) {
            // conductance × (T_interior - T_ambient), divided by volume so larger
            // structures lose heat proportionally less per cell
            qWall += face.conductance() * deltaT / Math.max(1, structure.volume);
        }

        // ── Heat loss through gaps ─────────────────────────────────────────────
        double qGap = structure.gapFaces.size() * GAP_CONDUCTANCE * deltaT;

        // ── Chimney draft ──────────────────────────────────────────────────────
        // Chimney draws hot air upward, increasing heat loss but also increasing
        // O₂ replenishment. The draft effect scales with temperature difference
        // (hot air rises faster when temperature delta is large).
        double chimneyLoss = structure.chimneyShaft.size()
                * CHIMNEY_CONDUCTANCE_PER_BLOCK * deltaT
                * structure.chimneyDraftFactor();

        // Chimney O₂ replenishment — fresh air pulled in from gaps
        if (!structure.chimneyShaft.isEmpty() && !structure.isSealed) {
            float replenish = (float) (structure.chimneyDraftFactor() * deltaT * 0.001);
            if (replenish > 0f) {
                BlockPos intakePos = structure.gapFaces.isEmpty() ? null
                        : structure.gapFaces.get(0).interiorPos();
                if (intakePos != null) {
                    exp.CCnewmods.mge.grid.EnvironmentGrid.addGas(
                            level, intakePos,
                            exp.CCnewmods.mge.gas.GasRegistry.OXYGEN,
                            Math.min(replenish, 2.0f));
                }
            }
        }

        // ── Net heat and temperature update ───────────────────────────────────
        double qNet = qIn - qWall - qGap - chimneyLoss;

        // Thermal mass = sum of wall thermal masses + volume (air has minimal mass)
        double thermalMass = structure.totalWallThermalMass
                + structure.volume * 1.0; // 1 J/°C per air block

        double dT = (qNet * TICK_SCALE) / Math.max(1.0, thermalMass);

        interiorTemp = Math.max(ambientCelsius + MIN_ABOVE_AMBIENT,
                interiorTemp + dT);

        // Cap at the weakest wall's maximum temperature — the wall physically
        // can't sustain higher without cracking (WallCrackSystem handles that)
        // We don't cap here — let it exceed and let WallCrackSystem respond.

        return interiorTemp;
    }

    // ── Temperature queries ────────────────────────────────────────────────────

    /**
     * Current average interior temperature in °C.
     * Returns NaN before the first tick.
     */
    public double getInteriorTemp() {
        return interiorTemp;
    }

    /**
     * Spatial temperature at a specific world position within the structure.
     * Returns the interior average if the position has no spatial weight (not in interior).
     * <p>
     * Used by FurnaceRecipeProcessor to get the temperature of items at their
     * exact position — items near the heat source are hotter than items near a gap.
     *
     * @param ambientCelsius the ambient temperature (for scaling)
     */
    public double getTemperatureAt(BlockPos pos, double ambientCelsius) {
        if (Double.isNaN(interiorTemp)) return ambientCelsius;
        Double weight = spatialWeights.get(pos);
        if (weight == null) return interiorTemp; // not in interior — return average
        return ambientCelsius + (interiorTemp - ambientCelsius) * weight;
    }

    /**
     * Set the interior temperature directly (e.g. on load from NBT).
     */
    public void setInteriorTemp(double celsius) {
        this.interiorTemp = celsius;
    }

    // ── Spatial weight computation ────────────────────────────────────────────

    /**
     * Computes a spatial weight [0,1] for each interior cell indicating
     * how hot it is relative to the average interior temperature.
     * <p>
     * Weight = 1.0 at a heat source position.
     * Weight decays exponentially with distance from the nearest source.
     * Weight is additionally reduced if the cell is near a gap face.
     * <p>
     * Called once after scan — result cached for the structure's lifetime.
     */
    private static Map<BlockPos, Double> buildSpatialWeights(ThermalStructure structure) {
        Map<BlockPos, Double> weights = new HashMap<>();
        if (structure.interior.isEmpty()) return weights;

        // Collect source positions
        java.util.Set<BlockPos> sourcePosSet = new java.util.HashSet<>();
        for (HeatSourceEntry src : structure.heatSources) sourcePosSet.add(src.pos());

        // Gap positions for proximity penalty
        java.util.Set<BlockPos> gapPosSet = new java.util.HashSet<>();
        for (GapFace gf : structure.gapFaces) gapPosSet.add(gf.interiorPos());

        // If no sources found inside, all weights = 1.0 (uniform)
        if (sourcePosSet.isEmpty()) {
            for (BlockPos p : structure.interior) weights.put(p, 1.0);
            return weights;
        }

        for (BlockPos cell : structure.interior) {
            // Distance to nearest source
            double minSourceDist = sourcePosSet.stream()
                    .mapToDouble(s -> Math.sqrt(cell.distSqr(s)))
                    .min().orElse(0);

            // Distance to nearest gap
            double minGapDist = gapPosSet.isEmpty() ? Double.MAX_VALUE
                    : gapPosSet.stream()
                    .mapToDouble(g -> Math.sqrt(cell.distSqr(g)))
                    .min().orElse(Double.MAX_VALUE);

            // Source proximity weight: 1.0 at source, decays with distance
            double sourceWeight = Math.exp(-SPATIAL_FALLOFF * minSourceDist);

            // Gap penalty: cells near gaps bleed toward ambient
            double gapPenalty = minGapDist < 4.0
                    ? Math.max(0, 1.0 - (4.0 - minGapDist) / 4.0 * 0.6) : 1.0;

            weights.put(cell.immutable(), Math.max(0.05, sourceWeight * gapPenalty));
        }

        return weights;
    }
}
