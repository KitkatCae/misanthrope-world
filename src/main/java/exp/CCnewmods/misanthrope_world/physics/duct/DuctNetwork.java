package exp.CCnewmods.misanthrope_world.physics.duct;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.field.ThermalField;
import exp.CCnewmods.mge.gas.GasComposition;
import exp.CCnewmods.mge.grid.EnvironmentGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Detects duct paths from a fan position and propagates airflow through them.
 * <p>
 * A duct path is a sequence of permeable blocks (non-airtight per
 * {@link BlockPhysicsRegistry}) bounded by solid walls, running from a fan to
 * a vent opening.
 * <p>
 * ── Detection ─────────────────────────────────────────────────────────────────
 * BFS from the fan's output face, walking through permeable blocks, stopping when:
 * - A solid airtight block is reached (wall — this face is not a vent)
 * - An opening into a thermal zone is reached (vent)
 * - MAX_DUCT_LENGTH is exceeded
 * - The path branches (at a junction, all branches are followed)
 * <p>
 * The result is a set of vent positions where airflow exits.
 * <p>
 * ── Propagation ───────────────────────────────────────────────────────────────
 * Each tick, for each active fan:
 * 1. Read temperature and gas at the fan's intake position from EnvironmentGrid.
 * 2. Walk the duct path to each vent.
 * 3. Apply temperature attenuation per block of duct.
 * 4. Write attenuated temperature and gas composition to EnvironmentGrid at vent.
 * 5. ThermalField and the MGE diffusion tick spread it into the connected zone.
 */
public final class DuctNetwork {

    public static final int MAX_DUCT_LENGTH = 64;

    private DuctNetwork() {
    }

    /**
     * Propagate airflow from a fan at {@code fanPos} facing {@code outwardFace}.
     *
     * @param level       server level
     * @param fanPos      position of the fan block
     * @param outwardFace direction the fan blows toward (its output face)
     * @param flowRate    volumetric flow rate in blocks/tick (from fan power source)
     * @return list of vent positions where airflow exits
     */
    public static List<BlockPos> propagate(ServerLevel level, BlockPos fanPos,
                                           Direction outwardFace, double flowRate) {
        BlockPos intakePos = fanPos.relative(outwardFace.getOpposite());
        double intakeTemp = ThermalField.getTemperatureAt(level, intakePos);
        if (Double.isNaN(intakeTemp)) intakeTemp = 20.0;
        GasComposition intakeGas = EnvironmentGrid.getComposition(level, intakePos);

        List<VentResult> vents = findVents(level, fanPos, outwardFace);

        for (VentResult vent : vents) {
            double attenuatedTemp = Math.max(
                    intakeTemp - DuctAirflow.TEMP_ATTENUATION_PER_BLOCK * vent.pathLength(), 0.0);

            if (attenuatedTemp > 0.5) {
                float currentVentTemp = EnvironmentGrid.getTemperature(level, vent.pos());
                float base = Float.isNaN(currentVentTemp) ? (float) attenuatedTemp : currentVentTemp;
                float blended = base + (float) ((attenuatedTemp - base)
                        * Math.min(1.0, flowRate * 0.1));
                EnvironmentGrid.setTemperature(level, vent.pos(), blended);
            }

            if (!intakeGas.isEmpty()) {
                double transferFraction = Math.min(1.0, flowRate * 0.05);
                for (exp.CCnewmods.mge.gas.Gas gas : exp.CCnewmods.mge.gas.GasRegistry.all()) {
                    float amount = intakeGas.get(gas);
                    if (amount <= 0f) continue;
                    float transferred = (float) (amount * transferFraction);
                    EnvironmentGrid.addGas(level, vent.pos(), gas, transferred);
                    EnvironmentGrid.addGas(level, intakePos, gas, -transferred);
                }
            }
        }

        List<BlockPos> out = new ArrayList<>();
        for (VentResult v : vents) out.add(v.pos());
        return out;
    }

    // ── Vent detection ────────────────────────────────────────────────────────

    private static List<VentResult> findVents(ServerLevel level, BlockPos fanPos,
                                              Direction outwardFace) {
        List<VentResult> vents = new ArrayList<>();
        Queue<PathNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        BlockPos startPos = fanPos.relative(outwardFace);
        queue.add(new PathNode(startPos, 1));
        visited.add(startPos.asLong());

        while (!queue.isEmpty()) {
            PathNode node = queue.poll();
            if (node.pathLength() > MAX_DUCT_LENGTH) continue;
            if (!level.isLoaded(node.pos())) continue;

            BlockState state = level.getBlockState(node.pos());
            BlockPhysicsData data = BlockPhysicsRegistry.get(state);

            if (data.isAirtight) continue; // wall — path terminates

            int solidNeighbours = 0;
            List<BlockPos> openNeighbours = new ArrayList<>();
            for (Direction d : Direction.values()) {
                BlockPos np = node.pos().relative(d);
                if (!level.isLoaded(np)) {
                    solidNeighbours++;
                    continue;
                }
                if (BlockPhysicsRegistry.get(level.getBlockState(np)).isAirtight) {
                    solidNeighbours++;
                } else {
                    openNeighbours.add(np);
                }
            }

            boolean isVent = solidNeighbours < 4 && node.pathLength() > 1;
            if (isVent) {
                vents.add(new VentResult(node.pos().immutable(), node.pathLength()));
                continue; // don't continue BFS past vent
            }

            for (BlockPos neighbour : openNeighbours) {
                if (visited.add(neighbour.asLong())) {
                    queue.add(new PathNode(neighbour.immutable(), node.pathLength() + 1));
                }
            }
        }

        return vents;
    }

    private record PathNode(BlockPos pos, int pathLength) {
    }

    private record VentResult(BlockPos pos, int pathLength) {
    }
}
