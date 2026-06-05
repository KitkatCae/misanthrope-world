package exp.CCnewmods.misanthrope_world.physics.duct;

import exp.CCnewmods.mge.gas.GasComposition;
import net.minecraft.core.Direction;

/**
 * Represents directed airflow through an enclosed tunnel.
 * <p>
 * ── What a "duct" is ──────────────────────────────────────────────────────────
 * Any enclosed tunnel of permeable blocks (air, grates, open blocks) with
 * a fan at one end is automatically a duct.  No dedicated duct block is required.
 * The fan reads the gas composition and temperature from the EnvironmentGrid at
 * its intake face and pushes it directionally through the tunnel.
 * <p>
 * The DuctNetwork detects these tunnels by BFS from any fan, walking through
 * permeable blocks (InsulationRegistry.isAirtight() == false) bounded by solid
 * walls, until it finds an opening (vent) at the other end.
 * <p>
 * ── Physics ───────────────────────────────────────────────────────────────────
 * Temperature attenuation: each block of duct length reduces temperature carry
 * by TEMP_ATTENUATION_PER_BLOCK (default 2°C/block — a well-insulated metal
 * duct loses little heat; an open air channel loses more).
 * <p>
 * Gas transport: gas composition is carried without attenuation through ducts.
 * The fan's volumetric flow rate (blocks/tick) determines how much gas moves.
 * <p>
 * At the vent (terminus), carried temperature and gas are injected into the
 * EnvironmentGrid cells at the vent opening.  The normal diffusion tick then
 * spreads it naturally into the connected thermal zone.
 * <p>
 * ── Fan power sources ─────────────────────────────────────────────────────────
 * Fans can be driven by:
 * - Redstone signal (basic on/off)
 * - Create rotational stress (flow rate scales with RPM)
 * - Our own bellows integration (manual pumping)
 * <p>
 * The fan block BE calls DuctNetwork.tick() each server tick to propagate flow.
 */
public record DuctAirflow(
        Direction flowDirection,
        double temperatureCelsius,
        GasComposition gasComposition,
        double flowRateBlocksPerTick,  // volumetric flow
        int pathLength                  // duct length in blocks
) {
    /**
     * Default temperature loss per block of uninsulated duct.
     */
    public static final double TEMP_ATTENUATION_PER_BLOCK = 2.0;

    /**
     * Temperature at the vent end after travelling through the full duct path.
     * An insulated metal duct (low attenuation) preserves heat well.
     * An open air tunnel loses more heat to surroundings.
     */
    public double ventTemperature(double attenuationPerBlock) {
        double loss = attenuationPerBlock * pathLength;
        return Math.max(temperatureCelsius - loss, 0.0);
    }

    public double ventTemperature() {
        return ventTemperature(TEMP_ATTENUATION_PER_BLOCK);
    }

    /**
     * Gas composition is conserved through the duct (no gas attenuation).
     */
    public GasComposition ventGasComposition() {
        return gasComposition.copy();
    }
}
