package exp.CCnewmods.misanthrope_world.physics.offgas;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.OffGas;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Drives thermal and ambient off-gassing for blocks with
 * {@link BlockPhysicsData#thermalOffGas} or {@link BlockPhysicsData#ambientOffGas}
 * defined in their {@code material_properties/} entry.
 *
 * <h3>Thermal off-gassing</h3>
 * When a block's surface temperature (from {@code EnvironmentGrid}) exceeds
 * {@code thermal_offgas.threshold_celsius}, gas is emitted at
 * {@code rate_mbar_per_tick} (scaled by temperature if {@code rate_scales_with_temp}).
 * If the block stays above {@code sustained_above_celsius} for
 * {@code sustained_ticks} ticks, it converts to {@code sustained_result}.
 * Example: limestone → quicklime above 840°C.
 *
 * <h3>Ambient off-gassing</h3>
 * Blocks emit gas passively based on a {@code condition}:
 * <ul>
 *   <li>{@code null} — always emit</li>
 *   <li>{@code "contact_water"} — block adjacent to water</li>
 *   <li>{@code "contact_air"} — block has at least one non-airtight neighbour</li>
 *   <li>{@code "submerged"} — block is in water</li>
 *   <li>{@code "below_celsius:N"} — ambient temperature below N°C</li>
 * </ul>
 *
 * <h3>Tick scheduling</h3>
 * Blocks enter the dirty set when placed ({@code BlockEvent.EntityPlaceEvent})
 * or when their temperature changes (called by {@code ThermalField}).
 * The handler processes {@link #BLOCKS_PER_TICK} entries per server tick,
 * re-queuing blocks that are still actively off-gassing.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OffGasHandler {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/OffGas");

    private static final int BLOCKS_PER_TICK = 8;
    private static final boolean MGE = ModList.get().isLoaded("mge");

    // Level → pending blocks
    private static final Map<ServerLevel, ConcurrentLinkedDeque<BlockPos>> DIRTY =
            new ConcurrentHashMap<>();

    // Sustained-heat tracking: level → (pos → ticks spent above sustained threshold)
    private static final Map<ServerLevel, Map<Long, Integer>> SUSTAINED =
            new ConcurrentHashMap<>();

    private OffGasHandler() {
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Enqueue a block for off-gas evaluation. Called by:
     * <ul>
     *   <li>{@code ThermalField} when a block's wall temperature changes</li>
     *   <li>{@code BlockEvent.EntityPlaceEvent} for newly placed blocks</li>
     * </ul>
     */
    public static void markDirty(ServerLevel level, BlockPos pos) {
        DIRTY.computeIfAbsent(level, k -> new ConcurrentLinkedDeque<>())
                .add(pos.immutable());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        BlockPhysicsData data = BlockPhysicsRegistry.get(level.getBlockState(pos));
        if (data.thermalOffGas != null || data.ambientOffGas != null) {
            markDirty(level, pos);
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
            processBlock(level, pos, queue);
        }
    }

    // ── Core processing ───────────────────────────────────────────────────────

    private static void processBlock(ServerLevel level, BlockPos pos,
                                     ConcurrentLinkedDeque<BlockPos> queue) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        boolean requeue = false;

        // ── Thermal off-gas ───────────────────────────────────────────────────
        if (data.thermalOffGas != null && MGE) {
            requeue |= processThermalOffGas(level, pos, state, data.thermalOffGas);
        }

        // ── Ambient off-gas ───────────────────────────────────────────────────
        if (data.ambientOffGas != null && MGE) {
            requeue |= processAmbientOffGas(level, pos, state, data.ambientOffGas);
        }

        if (requeue) queue.add(pos.immutable());
    }

    private static boolean processThermalOffGas(ServerLevel level, BlockPos pos,
                                                BlockState state, OffGas cfg) {
        float rawTemp = exp.CCnewmods.mge.grid.EnvironmentGrid.getTemperature(level, pos);
        if (Float.isNaN(rawTemp)) return false;

        double tempC = rawTemp;
        double threshold = Double.isNaN(cfg.thresholdCelsius()) ? 0 : cfg.thresholdCelsius();
        if (tempC < threshold) return false;

        // Stops-above check
        if (!Double.isNaN(cfg.stopsAboveCelsius()) && tempC > cfg.stopsAboveCelsius()) return false;

        // Compute rate
        float rate = cfg.rateMbarPerTick();
        if (cfg.ratesScalesWithTemp()) {
            rate *= (float) Math.max(1.0, (tempC - threshold) / 1000.0 + 1.0);
        }

        emitGas(level, pos, cfg.gasKey(), rate);

        // Sustained conversion
        if (!Double.isNaN(cfg.sustainedAboveCelsius())
                && tempC >= cfg.sustainedAboveCelsius()
                && cfg.sustainedResult() != null) {

            Map<Long, Integer> sustained = SUSTAINED.computeIfAbsent(level,
                    k -> new ConcurrentHashMap<>());
            long key = pos.asLong();
            int ticks = sustained.getOrDefault(key, 0) + 1;

            if (ticks >= cfg.sustainedTicks()) {
                sustained.remove(key);
                level.setBlock(pos, net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getValue(cfg.sustainedResult())
                        .defaultBlockState(), 3);
                return false; // block replaced, stop tracking
            } else {
                sustained.put(key, ticks);
            }
        }

        return true; // continue tracking
    }

    private static boolean processAmbientOffGas(ServerLevel level, BlockPos pos,
                                                BlockState state, OffGas cfg) {
        // Stops-above check
        if (!Double.isNaN(cfg.stopsAboveCelsius())) {
            double ambientC = MisTemperatureAPI.getAmbientCelsius(level, pos);
            if (ambientC > cfg.stopsAboveCelsius()) return false;
        }

        // Condition check
        if (!meetsCond(level, pos, state, cfg.condition())) return false;

        emitGas(level, pos, cfg.gasKey(), cfg.rateMbarPerTick());
        return true; // ambient sources always requeue (they're passive, ongoing)
    }

    // ── Condition evaluation ──────────────────────────────────────────────────

    private static boolean meetsCond(ServerLevel level, BlockPos pos,
                                     BlockState state, String cond) {
        if (cond == null) return true;
        return switch (cond) {
            case "contact_water" -> {
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos adj = pos.relative(d);
                    if (level.isLoaded(adj) && level.getBlockState(adj).getFluidState()
                            .is(net.minecraft.tags.FluidTags.WATER)) yield true;
                }
                yield false;
            }
            case "contact_air" -> {
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos adj = pos.relative(d);
                    if (level.isLoaded(adj)) {
                        BlockPhysicsData nd = BlockPhysicsRegistry.get(level.getBlockState(adj));
                        if (!nd.isAirtight) yield true;
                    }
                }
                yield false;
            }
            case "submerged" -> state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            default -> {
                if (cond.startsWith("below_celsius:")) {
                    try {
                        double limit = Double.parseDouble(cond.substring(14));
                        yield MisTemperatureAPI.getAmbientCelsius(level, pos) < limit;
                    } catch (NumberFormatException e) {
                        LOGGER.warn("[OffGasHandler] Invalid below_celsius condition: {}", cond);
                        yield false;
                    }
                }
                yield true; // unknown condition — emit anyway, don't crash
            }
        };
    }

    // ── Gas emission ──────────────────────────────────────────────────────────

    private static void emitGas(ServerLevel level, BlockPos pos,
                                String gasKey, float mbarPerTick) {
        if (!MGE || mbarPerTick <= 0) return;
        try {
            var gasOpt = exp.CCnewmods.mge.gas.GasRegistry.get(
                    new net.minecraft.resources.ResourceLocation(gasKey));
            if (gasOpt.isEmpty()) return;
            exp.CCnewmods.mge.grid.EnvironmentGrid.addGas(
                    level, pos, gasOpt.get(), mbarPerTick);
        } catch (Exception e) {
            // Gas key not found or MGE API mismatch — silent, don't spam log
        }
    }
}
