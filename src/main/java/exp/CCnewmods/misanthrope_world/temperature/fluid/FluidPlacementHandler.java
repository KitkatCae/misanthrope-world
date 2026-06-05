package exp.CCnewmods.misanthrope_world.temperature.fluid;

import exp.CCnewmods.misanthrope_world.temperature.melt.MeltData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Handles placing fluid produced by item melting into the world.
 * <p>
 * ── Priority chain ────────────────────────────────────────────────────────────
 * <p>
 * 1. IFluidHandler on the block entity at the item's container position
 * → Fill the tank directly (crucible, cistern, cauldron, barrel, etc.)
 * → If tank is full, fall through to next option
 * <p>
 * 2. LiquidBlockContainer at the position
 * → Standard waterlogging interface — handles cauldrons, etc.
 * <p>
 * 3. FlowingFluids API (if mod is loaded)
 * → placeFluidAmountFromPos() with the melt data's fluid level count
 * → FF handles spreading, filling, BFS outward expansion automatically
 * <p>
 * 4. Fluidlogged (if mod is loaded)
 * → canPlaceFluid() check, then vanilla setBlock() with fluid state
 * → Fluidlogged's mixins intercept and store the fluid in the block
 * <p>
 * 5. BFS fallback
 * → Walk outward from the source position, level by level
 * → Find the nearest air or fluid-compatible position
 * → Place a fluid source block there
 * → For entities: check feet position first, then spiral outward
 * <p>
 * ── Fluid layer size from melt volume ────────────────────────────────────────
 * FlowingFluids uses 8 internal "levels" per full fluid block.
 * MeltData.flowingFluidsLevels() converts fluid_mb → FF levels:
 * 1000mB = 8 levels (full block)   e.g. ice
 * 500mB = 4 levels (half block)
 * 250mB = 2 levels
 * 144mB = 1 level  (one layer)    e.g. metal ingot
 * 125mB = 1 level  (minimum)
 * <p>
 * ── Entity melt position ─────────────────────────────────────────────────────
 * For items melting in entity inventories, the position used is the entity's
 * block position (feet). BFS expands outward from there.
 */
public class FluidPlacementHandler {

    /**
     * Maximum BFS search radius when all nearby positions are blocked.
     */
    private static final int MAX_BFS_RADIUS = 8;

    private static Boolean flowingFluidsLoaded = null;
    private static Boolean fluidloggedLoaded = null;

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Place the fluid produced by melting one unit of the given item
     * at the given position in the level.
     *
     * @param level  the server level
     * @param pos    origin position (item's container or entity's feet)
     * @param melt   the melt data for this item
     * @param random for byproduct chance rolls
     * @return true if fluid was placed successfully
     */
    public static boolean placeMeltFluid(ServerLevel level, BlockPos pos,
                                         MeltData melt,
                                         net.minecraft.util.RandomSource random) {
        Fluid fluid = melt.getFluid();
        if (fluid == null || fluid == Fluids.EMPTY) return false;

        int fluidMb = melt.fluidMb();

        // 1. IFluidHandler on block entity at pos
        if (tryFillFluidHandler(level, pos, fluid, fluidMb)) return true;

        // 2. LiquidBlockContainer (cauldron, etc.)
        if (tryFillLiquidContainer(level, pos, fluid)) return true;

        // 3. FlowingFluids
        if (isFlowingFluidsLoaded()) {
            if (tryFlowingFluids(level, pos, fluid, melt.flowingFluidsLevels())) return true;
        }

        // 4. Fluidlogged
        if (isFluidloggedLoaded()) {
            if (tryFluidlogged(level, pos, fluid)) return true;
        }

        // 5. BFS fallback — find nearest suitable position
        return bfsFallbackPlace(level, pos, fluid);
    }

    // ── Step 1: IFluidHandler ─────────────────────────────────────────────────

    private static boolean tryFillFluidHandler(ServerLevel level, BlockPos pos,
                                               Fluid fluid, int mb) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;

        return be.getCapability(ForgeCapabilities.FLUID_HANDLER)
                .map(handler -> {
                    FluidStack toFill = new FluidStack(fluid, mb);
                    int filled = handler.fill(toFill, IFluidHandler.FluidAction.SIMULATE);
                    if (filled > 0) {
                        handler.fill(toFill, IFluidHandler.FluidAction.EXECUTE);
                        return true;
                    }
                    return false;
                })
                .orElse(false);
    }

    // ── Step 2: LiquidBlockContainer ─────────────────────────────────────────

    private static boolean tryFillLiquidContainer(ServerLevel level, BlockPos pos, Fluid fluid) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer container))
            return false;
        if (!container.canPlaceLiquid(level, pos, state, fluid)) return false;
        container.placeLiquid(level, pos, state, fluid.defaultFluidState());
        return true;
    }

    // ── Step 3: FlowingFluids ─────────────────────────────────────────────────

    private static boolean tryFlowingFluids(ServerLevel level, BlockPos pos,
                                            Fluid fluid, int levels) {
        try {
            var api = traben.flowing_fluids.api.FlowingFluidsAPI.getInstance("misanthrope_core");
            if (!api.isModEnabled()) return false;

            // placeFluidAmountFromPos places `levels` units of fluid at pos,
            // spreading outward if the position is full
            api.placeFluidAmountFromPos(level, pos, fluid, levels, true, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Step 4: Fluidlogged ───────────────────────────────────────────────────

    private static boolean tryFluidlogged(ServerLevel level, BlockPos pos, Fluid fluid) {
        try {
            BlockState state = level.getBlockState(pos);

            // Use reflection to call Fluidlogged.canPlaceFluid — avoids compile-time
            // dependency on the class since it's a soft dependency.
            Class<?> fluidloggedClass = Class.forName("de.leximon.fluidlogged.Fluidlogged");
            java.lang.reflect.Method canPlace = fluidloggedClass.getMethod(
                    "canPlaceFluid",
                    net.minecraft.world.level.LevelAccessor.class,
                    net.minecraft.core.BlockPos.class,
                    BlockState.class,
                    Fluid.class);
            boolean allowed = (boolean) canPlace.invoke(null, level, pos, state, fluid);
            if (!allowed) return false;

            FluidState fluidState = fluid.defaultFluidState();
            level.setBlock(pos, state, 3);
            level.scheduleTick(pos, fluid, fluid.getTickDelay(level));
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Step 5: BFS fallback ──────────────────────────────────────────────────

    /**
     * BFS outward from origin, looking for a position where we can place
     * a fluid source block. Tries IFluidHandler → FlowingFluids → direct placement
     * at each position.
     */
    private static boolean bfsFallbackPlace(ServerLevel level, BlockPos origin, Fluid fluid) {
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(origin);
        seen.add(origin);

        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            // Distance check
            if (Math.abs(current.getX() - origin.getX()) > MAX_BFS_RADIUS ||
                    Math.abs(current.getY() - origin.getY()) > MAX_BFS_RADIUS ||
                    Math.abs(current.getZ() - origin.getZ()) > MAX_BFS_RADIUS) continue;

            // Try to place here
            if (tryFillFluidHandler(level, current, fluid, 1000)) return true;

            BlockState state = level.getBlockState(current);

            // Can we place a fluid source here? (air or replaceable)
            if (state.isAir() || state.canBeReplaced()) {
                if (isFlowingFluidsLoaded()) {
                    try {
                        var api = traben.flowing_fluids.api.FlowingFluidsAPI
                                .getInstance("misanthrope_core");
                        if (api.isModEnabled()) {
                            api.placeFluidAmountFromPos(level, current, fluid, 1, true, true);
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }

                // Direct source block placement
                level.setBlock(current, fluid.defaultFluidState().createLegacyBlock(), 3);
                return true;
            }

            // Expand outward
            for (int i = 0; i < 6; i++) {
                BlockPos next = current.offset(dx[i], dy[i], dz[i]);
                if (!seen.contains(next)) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }

        return false; // no suitable position found within radius
    }

    // ── Mod availability ──────────────────────────────────────────────────────

    private static boolean isFlowingFluidsLoaded() {
        if (flowingFluidsLoaded == null) {
            try {
                Class.forName("traben.flowing_fluids.api.FlowingFluidsAPI");
                flowingFluidsLoaded = true;
            } catch (ClassNotFoundException e) {
                flowingFluidsLoaded = false;
            }
        }
        return flowingFluidsLoaded;
    }

    private static boolean isFluidloggedLoaded() {
        if (fluidloggedLoaded == null) {
            try {
                Class.forName("de.leximon.fluidlogged.Fluidlogged");
                fluidloggedLoaded = true;
            } catch (ClassNotFoundException e) {
                fluidloggedLoaded = false;
            }
        }
        return fluidloggedLoaded;
    }
}
