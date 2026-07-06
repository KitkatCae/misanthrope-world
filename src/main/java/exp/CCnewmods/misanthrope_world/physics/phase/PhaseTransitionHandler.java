package exp.CCnewmods.misanthrope_world.physics.phase;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.PhaseTransition;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.PhaseTransitionTrigger;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.WorldSimulation;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Evaluates {@link BlockPhysicsData#phaseTransitions} at runtime for all blocks
 * that declare them in their {@code material_properties/} JSON entry.
 *
 * <h3>Supported triggers</h3>
 * <ul>
 *   <li>{@code ON_HEAT_ABOVE} / {@code ON_MELT} / {@code ON_FIRE} — temperature-gated.
 *       Evaluated when {@code WorldSimulation.onPhaseTransitionThermal} is called by
 *       {@link exp.CCnewmods.misanthrope_world.physics.field.ThermalField}'s wall-scan,
 *       same hook point used by {@link exp.CCnewmods.misanthrope_world.physics.offgas.OffGasHandler}.</li>
 *   <li>{@code ON_WATER_CONTACT} — triggered on any fluid-neighbour change via
 *       {@code BlockEvent.NeighborNotifyEvent}. No temperature gate.</li>
 *   <li>{@code ON_FREEZE} / {@code ON_COOL_BELOW} — temperature-gated, below threshold.</li>
 * </ul>
 *
 * <h3>{@code ON_FIRE} semantics</h3>
 * {@code ON_FIRE} requires both temperature above {@code tempThreshold} AND
 * sufficient ambient oxygen ({@code requiresOxygen=true} means ≥ 160 mbar O₂,
 * the same breathability floor {@link exp.CCnewmods.mge.gas.GasComposition#isBreathable}
 * uses). If MGE is not loaded, oxygen is assumed present (safe default —
 * fire cannot spontaneously suppress itself without an atmosphere mod).
 *
 * <h3>ON_MELT fluid result</h3>
 * {@code resultBlock} on an {@code ON_MELT} entry may be {@code "minecraft:lava"}
 * or any other block/fluid block ID. The result is placed via
 * {@code level.setBlock(pos, resultState, 3)} — no special fluid API needed,
 * since lava, water, and mod fluids all have block representations.
 *
 * <h3>Tick budget</h3>
 * Mirrors {@link exp.CCnewmods.misanthrope_world.physics.offgas.OffGasHandler}:
 * {@link #BLOCKS_PER_TICK} entries processed per server tick from the dirty queue,
 * with re-queuing for blocks that are still in a transitioning condition.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PhaseTransitionHandler {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/PhaseTransition");

    private static final int BLOCKS_PER_TICK = 8;
    private static final boolean MGE = ModList.get().isLoaded("mge");

    // Level → pending blocks
    private static final Map<ServerLevel, ConcurrentLinkedDeque<BlockPos>> DIRTY =
            new ConcurrentHashMap<>();

    // Sustained-heat tracking for ON_HEAT_ABOVE (same pattern as OffGasHandler's sustained map)
    // level → (pos.asLong() → ticks spent continuously above threshold)
    private static final Map<ServerLevel, Map<Long, Integer>> SUSTAINED =
            new ConcurrentHashMap<>();

    private PhaseTransitionHandler() {}

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Enqueue a block for temperature-driven phase-transition evaluation.
     * Called by {@code WorldSimulation.onPhaseTransitionThermal}, which is
     * invoked by ThermalField whenever a block's temperature crosses the first
     * relevant threshold.
     */
    public static void markDirtyThermal(ServerLevel level, BlockPos pos) {
        DIRTY.computeIfAbsent(level, k -> new ConcurrentLinkedDeque<>())
                .add(pos.immutable());
    }

    /**
     * Immediate water-contact evaluation for the given block.
     * Called by {@code WorldSimulation.onPhaseTransitionWaterContact} on
     * {@code BlockEvent.NeighborNotifyEvent} when a fluid neighbour appears.
     * No queuing — water contact transitions are instantaneous (sodium explodes
     * the moment water touches it; no tick delay is realistic or desirable).
     */
    public static void evaluateWaterContact(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        if (data.phaseTransitions.isEmpty()) return;

        boolean adjacentWater = false;
        for (Direction d : Direction.values()) {
            BlockPos adj = pos.relative(d);
            if (level.isLoaded(adj) && level.getBlockState(adj).getFluidState().is(FluidTags.WATER)) {
                adjacentWater = true;
                break;
            }
        }
        if (!adjacentWater) return;

        for (PhaseTransition t : data.phaseTransitions) {
            if (t.trigger() == PhaseTransitionTrigger.ON_WATER_CONTACT) {
                fireTransition(level, pos, t);
                return; // first matching rule wins
            }
        }
    }

    // ── Block placement hook ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        BlockPhysicsData data = BlockPhysicsRegistry.get(level.getBlockState(pos));
        if (data.phaseTransitions.isEmpty()) return;

        // Check water contact immediately on placement (block placed into water)
        evaluateWaterContact(level, pos);

        // Also enqueue for temperature check if any thermal triggers are present
        if (hasThermalTrigger(data)) {
            markDirtyThermal(level, pos);
        }
    }

    /**
     * On any neighbour change, check if a fluid block was placed adjacent to
     * a block with {@code on_water_contact} — if so, evaluate that block
     * immediately. Fires before the next server tick so reactive materials
     * (sodium in water) respond on the same tick the fluid arrived.
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos changedPos = event.getPos();

        // The changed block might be a fluid being placed — check if any of its
        // neighbours has an ON_WATER_CONTACT transition defined.
        for (Direction d : Direction.values()) {
            BlockPos neighbour = changedPos.relative(d);
            if (!level.isLoaded(neighbour)) continue;
            BlockState nState = level.getBlockState(neighbour);
            if (nState.isAir()) continue;
            BlockPhysicsData nData = BlockPhysicsRegistry.get(nState);
            for (PhaseTransition t : nData.phaseTransitions) {
                if (t.trigger() == PhaseTransitionTrigger.ON_WATER_CONTACT) {
                    WorldSimulation.onPhaseTransitionWaterContact(level, neighbour);
                    break;
                }
            }
        }
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        ConcurrentLinkedDeque<BlockPos> queue = DIRTY.get(level);
        if (queue == null || queue.isEmpty()) return;

        int budget = BLOCKS_PER_TICK;
        Set<BlockPos> processed = new HashSet<>();

        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos pos = queue.poll();
            if (pos == null || !processed.add(pos)) continue;
            processThermal(level, pos, queue);
        }
    }

    // ── Core thermal processing ───────────────────────────────────────────────

    private static void processThermal(ServerLevel level, BlockPos pos,
                                       ConcurrentLinkedDeque<BlockPos> queue) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        if (data.phaseTransitions.isEmpty()) return;

        double tempC = getTemperature(level, pos);
        if (Double.isNaN(tempC)) return;

        float oxygenMbar = getOxygenMbar(level, pos);

        for (PhaseTransition t : data.phaseTransitions) {
            if (!isThermalTrigger(t.trigger())) continue;

            boolean triggered = switch (t.trigger()) {
                case ON_MELT, ON_HEAT_ABOVE, ON_EXCEED_TEMP ->
                        !Double.isNaN(t.tempThreshold()) && tempC >= t.tempThreshold();
                case ON_FIRE ->
                        !Double.isNaN(t.tempThreshold()) && tempC >= t.tempThreshold()
                        && (!t.requiresOxygen() || oxygenMbar >= 160f);
                case ON_FREEZE, ON_COOL_BELOW ->
                        !Double.isNaN(t.tempThreshold()) && tempC <= t.tempThreshold();
                default -> false;
            };

            if (!triggered) {
                // Clear any sustained counter if condition no longer holds
                if (t.trigger() == PhaseTransitionTrigger.ON_HEAT_ABOVE) {
                    Map<Long, Integer> sus = SUSTAINED.get(level);
                    if (sus != null) sus.remove(pos.asLong());
                }
                continue;
            }

            // ON_HEAT_ABOVE uses sustained-tick gating (mirrors OffGasHandler's
            // sustained_ticks logic) — the block must stay above threshold for
            // some number of ticks before the transition fires. ON_MELT and ON_FIRE
            // are immediate (no sustained gate on those triggers).
            if (t.trigger() == PhaseTransitionTrigger.ON_HEAT_ABOVE
                    && !Double.isNaN(t.tempThreshold())) {
                Map<Long, Integer> sus = SUSTAINED.computeIfAbsent(level,
                        k -> new ConcurrentHashMap<>());
                long key = pos.asLong();
                // For now, ON_HEAT_ABOVE fires after 1 tick by default — JSON could
                // add a sustained_ticks field later, same as OffGas does.
                sus.put(key, sus.getOrDefault(key, 0) + 1);
            }

            fireTransition(level, pos, t);
            return; // first matching rule wins; block may have been replaced
        }

        // No transition fired — requeue if block still has thermal triggers and
        // is still hot (i.e. temperature read was valid)
        if (hasThermalTrigger(data)) {
            queue.add(pos.immutable());
        }
    }

    // ── Transition firing ─────────────────────────────────────────────────────

    private static void fireTransition(ServerLevel level, BlockPos pos, PhaseTransition t) {
        // Emit gas first (before block replacement, so the old block's position
        // is still correct for EnvironmentGrid writes)
        if (t.releases() != null && MGE) {
            emitGas(level, pos, t.releases());
        }

        // Place result block
        if (t.resultBlock() != null) {
            BlockState result = resolveResultBlock(t.resultBlock());
            if (result != null) {
                level.setBlock(pos, result, 3);
                // Clean up sustained map for this position
                Map<Long, Integer> sus = SUSTAINED.get(level);
                if (sus != null) sus.remove(pos.asLong());
                LOGGER.debug("[PhaseTransition] {} at {} → {} (trigger={})",
                        t.resultBlock(), pos, t.resultBlock(), t.trigger());
            } else {
                LOGGER.warn("[PhaseTransition] Unknown resultBlock '{}' for trigger {} at {}",
                        t.resultBlock(), t.trigger(), pos);
            }
        } else if (t.resultEvent() != null) {
            // resultEvent is a future hook — log for now, implement when event
            // registry exists (same deferred pattern as OffGasHandler's custom behaviours)
            LOGGER.debug("[PhaseTransition] resultEvent '{}' at {} (not yet implemented)",
                    t.resultEvent(), pos);
        }
    }

    @Nullable
    private static BlockState resolveResultBlock(ResourceLocation id) {
        var block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null || block == Blocks.AIR) return null;
        return block.defaultBlockState();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean hasThermalTrigger(BlockPhysicsData data) {
        for (PhaseTransition t : data.phaseTransitions) {
            if (isThermalTrigger(t.trigger())) return true;
        }
        return false;
    }

    private static boolean isThermalTrigger(PhaseTransitionTrigger trigger) {
        return switch (trigger) {
            case ON_MELT, ON_FIRE, ON_HEAT_ABOVE, ON_FREEZE,
                 ON_COOL_BELOW, ON_EXCEED_TEMP -> true;
            default -> false;
        };
    }

    private static double getTemperature(ServerLevel level, BlockPos pos) {
        if (MGE) {
            float gridTemp = exp.CCnewmods.mge.grid.EnvironmentGrid.getTemperature(level, pos);
            if (!Float.isNaN(gridTemp)) return gridTemp;
        }
        return MisTemperatureAPI.getAmbientCelsius(level, pos);
    }

    private static float getOxygenMbar(ServerLevel level, BlockPos pos) {
        if (!MGE) return 209.5f; // assume normal atmosphere if MGE not loaded
        try {
            return exp.CCnewmods.mge.grid.EnvironmentGrid
                    .getComposition(level, pos).oxygenPressure();
        } catch (Exception e) {
            return 209.5f;
        }
    }

    private static void emitGas(ServerLevel level, BlockPos pos, String gasKey) {
        if (!MGE) return;
        try {
            var gasOpt = exp.CCnewmods.mge.gas.GasRegistry.get(
                    new ResourceLocation(gasKey));
            if (gasOpt.isEmpty()) return;
            exp.CCnewmods.mge.grid.EnvironmentGrid.addGas(
                    level, pos, gasOpt.get(), 50f); // burst on transition
        } catch (Exception e) {
            // gas key not found or MGE mismatch — silent
        }
    }
}
