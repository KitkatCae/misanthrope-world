package exp.CCnewmods.misanthrope_world.wet_sand;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.*;

/**
 * BFS utilities for the wet-sand propagation system.
 * <p>
 * findDistanceToWater:
 * Performs a bounded BFS from a given BlockPos, checking all horizontally
 * and vertically adjacent blocks for water (source or flowing). Returns the
 * shortest distance to any water block, capped at WetnessLevel.MAX_DISTANCE + 1
 * (meaning "out of range" = dry).
 * <p>
 * findDrainPath:
 * Used by WaterConsumptionSystem when a crop consumes moisture. Traces a
 * path from the consuming farmland back toward the water source through
 * successively wetter blocks, returning an ordered list of positions to
 * decrement one wetness level each.
 */
public final class WetnessGraph {

    private WetnessGraph() {
    }

    // -------------------------------------------------------------------------
    // Water distance BFS
    // -------------------------------------------------------------------------

    /**
     * Returns the shortest Manhattan distance (horizontal only) from {@code origin}
     * to any water block (source or flowing), searching up to
     * {@link WetnessLevel#MAX_DISTANCE} + 1 blocks away.
     * <p>
     * Returns MAX_DISTANCE + 1 if no water is found within range, which
     * maps to WetnessLevel.DRY via {@link WetnessLevel#forDistance}.
     * <p>
     * We check horizontal neighbours only (N/S/E/W) plus the block directly
     * below origin (for cases where sand sits on top of a waterlogged block).
     *
     * @param level  The server level to query
     * @param origin The position of the soil block being evaluated
     * @return shortest distance to water, or MAX_DISTANCE + 1 if out of range
     */
    public static int findDistanceToWater(Level level, BlockPos origin) {
        int limit = WetnessLevel.MAX_DISTANCE;

        // BFS — queue holds (pos, distance)
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        // Seed: check the origin block's direct neighbours at distance 1
        queue.add(origin);
        visited.add(origin);

        int[] distance = {0};
        // Use level-by-level BFS
        int currentDist = 0;

        // Simple BFS tracking distance per level
        Queue<int[]> bfsQueue = new ArrayDeque<>();
        Set<Long> bfsVisited = new HashSet<>();

        bfsQueue.add(new int[]{origin.getX(), origin.getY(), origin.getZ(), 0});
        bfsVisited.add(origin.asLong());

        while (!bfsQueue.isEmpty()) {
            int[] current = bfsQueue.poll();
            int cx = current[0], cy = current[1], cz = current[2], dist = current[3];

            if (dist > limit) break; // beyond max range

            // Check all 6 neighbours
            int[][] offsets = {
                    {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, // horizontal
                    {0, -1, 0}                             // below (water under sand)
            };

            for (int[] offset : offsets) {
                int nx = cx + offset[0];
                int ny = cy + offset[1];
                int nz = cz + offset[2];
                BlockPos nPos = new BlockPos(nx, ny, nz);
                long nLong = nPos.asLong();

                if (bfsVisited.contains(nLong)) continue;
                bfsVisited.add(nLong);

                int newDist = dist + 1;
                if (newDist > limit) continue;

                // Check if this neighbour is water
                if (isWater(level, nPos)) {
                    return newDist;
                }

                // Continue BFS only through blocks that can transmit wetness:
                // air, wettable soil blocks, or other passable blocks
                BlockState nState = level.getBlockState(nPos);
                if (canTransmitWetness(nState)) {
                    bfsQueue.add(new int[]{nx, ny, nz, newDist});
                }
            }
        }

        return limit + 1; // out of range
    }

    /**
     * Returns true if the block at pos is a water source or flowing water.
     */
    public static boolean isWater(Level level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        return fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
    }

    /**
     * Returns true if this block state can act as a "conduit" for BFS wetness
     * propagation — i.e. it won't block the wetness signal.
     * Wettable blocks (wet or dry variants) and air all conduct.
     */
    private static boolean canTransmitWetness(BlockState state) {
        Block block = state.getBlock();
        return state.isAir()
                || WetSandRegistry.INSTANCE.isWettable(block);
    }

    // -------------------------------------------------------------------------
    // Drain path tracing (used by WaterConsumptionSystem)
    // -------------------------------------------------------------------------

    /**
     * Traces a drain path from a consuming position (e.g. the wet block under
     * farmland that was just drained) back toward the water source, following
     * the wetness gradient (successively more-saturated blocks).
     * <p>
     * Returns an ordered list of BlockPos values, starting at the consumer and
     * ending at or near the water source. Each position in the list will have
     * its wetness decremented by one level when the drain propagation runs.
     * <p>
     * The path terminates when:
     * - A water source block is reached (no more blocks to decrement)
     * - No wetter neighbour can be found (isolated block)
     * - The path exceeds MAX_DRAIN_PATH_LENGTH (safety cap)
     *
     * @param level    The server level
     * @param startPos The position that triggered the drain (wet block under farmland)
     * @return ordered list of positions to decrement, may be empty if startPos
     * is not a wet variant
     */
    public static List<BlockPos> traceDrainPath(ServerLevel level, BlockPos startPos) {
        List<BlockPos> path = new ArrayList<>();

        Block startBlock = level.getBlockState(startPos).getBlock();
        if (!WetSandRegistry.INSTANCE.isWetVariant(startBlock)) {
            return path; // not a wet block, nothing to drain
        }

        path.add(startPos);

        int maxLength = WetnessLevel.MAX_DISTANCE * 3; // generous cap
        BlockPos current = startPos;

        for (int i = 0; i < maxLength; i++) {
            // Find the wettest neighbour — this is the "toward source" direction
            BlockPos nextPos = findWettestNeighbour(level, current);
            if (nextPos == null) break; // no wetter neighbour, end of path

            // If the next step is water itself, we've reached the source
            if (isWater(level, nextPos)) break;

            Block nextBlock = level.getBlockState(nextPos).getBlock();
            if (!WetSandRegistry.INSTANCE.isWetVariant(nextBlock)) break;

            // Avoid cycles
            if (path.contains(nextPos)) break;

            path.add(nextPos);
            current = nextPos;
        }

        return path;
    }

    /**
     * Among the horizontal and below neighbours of {@code pos}, returns the
     * position that has the highest (most saturated) WetnessLevel, or null
     * if no wetter neighbours exist.
     */
    private static BlockPos findWettestNeighbour(Level level, BlockPos pos) {
        BlockPos best = null;
        WetnessLevel bestLevel = WetnessLevel.DRY;

        int[][] offsets = {
                {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
        };

        for (int[] offset : offsets) {
            BlockPos neighbour = pos.offset(offset[0], offset[1], offset[2]);
            Block block = level.getBlockState(neighbour).getBlock();

            Optional<WetnessLevel> level_ = WetSandRegistry.INSTANCE.getWetnessLevel(block);
            if (level_.isPresent()) {
                WetnessLevel nl = level_.get();
                // "Wetter" means closer to SATURATED = higher waterDistance value means LOWER level
                // We want the most saturated = lowest waterDistance number
                if (nl.waterDistance < bestLevel.waterDistance || bestLevel == WetnessLevel.DRY) {
                    bestLevel = nl;
                    best = neighbour;
                }
            }
        }

        return best;
    }

    // Add missing import
    private static Optional<WetnessLevel> Optional_ofNullable_bridge(WetnessLevel level) {
        return Optional.ofNullable(level);
    }
}
