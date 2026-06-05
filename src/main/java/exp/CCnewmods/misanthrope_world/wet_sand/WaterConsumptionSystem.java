package exp.CCnewmods.misanthrope_world.wet_sand;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Optional;
import java.util.*;

/**
 * Handles the water consumption chain when crops grow on wet-sand moistened
 * farmland.
 * <p>
 * When a crop grows:
 * 1. Check the farmland block below it.
 * 2. Check the blocks adjacent to the farmland for a WettableFallingBlock.
 * 3. If found, trace a drain path back toward the water source.
 * 4. Enqueue a DrainSequence — a time-delayed sequence of block decrements
 * that visibly propagates back toward the source over ~40 ticks.
 * <p>
 * The drain sequence runs during the server tick event registered in
 * Misanthrope_core (or a dedicated tick handler). Each step decrements
 * one block's wetness by one level and waits DRAIN_TICK_INTERVAL ticks
 * before the next step.
 */
public class WaterConsumptionSystem {

    /**
     * Ticks between each visual drain step propagating toward the source.
     */
    public static final int DRAIN_TICK_INTERVAL = 40;

    /**
     * Active drain sequences, keyed by a unique ID. Updated each server tick
     * by advanceDrains().
     */
    private static final Map<UUID, DrainSequence> activeDrains = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Forge event hook — crop growth
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onCropGrow(BlockEvent.CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos cropPos = event.getPos();
        BlockState cropState = event.getState();

        // Only process actual CropBlock growth
        if (!(cropState.getBlock() instanceof CropBlock)) return;

        BlockPos farmlandPos = cropPos.below();
        BlockState farmlandState = serverLevel.getBlockState(farmlandPos);

        // Only care about moistened farmland
        if (!(farmlandState.getBlock() instanceof FarmBlock)) return;
        int moisture = farmlandState.getValue(FarmBlock.MOISTURE);
        if (moisture <= 0) return;

        // Find adjacent wet soil blocks (N/S/E/W of farmland)
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos wetPos = farmlandPos.relative(dir);
            BlockState wetState = serverLevel.getBlockState(wetPos);
            Block wetBlock = wetState.getBlock();

            if (WetSandRegistry.INSTANCE.isWetVariant(wetBlock)) {
                // Found a wet soil adjacent to this farmland — start a drain
                scheduleDrain(serverLevel, wetPos);
                return; // one drain per crop growth event
            }
        }
    }

    // -------------------------------------------------------------------------
    // Drain scheduling
    // -------------------------------------------------------------------------

    /**
     * Traces a drain path from {@code startPos} toward the water source and
     * enqueues a DrainSequence if the path is non-empty.
     */
    public static void scheduleDrain(ServerLevel level, BlockPos startPos) {
        List<BlockPos> path = WetnessGraph.traceDrainPath(level, startPos);
        if (path.isEmpty()) return;

        UUID id = UUID.randomUUID();
        activeDrains.put(id, new DrainSequence(level, path));
        Misanthrope_world.LOGGER.debug(
                "WaterConsumption: scheduled drain path of {} blocks starting at {}",
                path.size(), startPos);
    }

    // -------------------------------------------------------------------------
    // Tick advancement — call this from MServerTickHandler each server tick
    // -------------------------------------------------------------------------

    /**
     * Advances all active drain sequences by one tick. Each sequence waits
     * DRAIN_TICK_INTERVAL ticks between steps. Call once per server tick.
     */
    public static void tick() {
        if (activeDrains.isEmpty()) return;

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, DrainSequence> entry : activeDrains.entrySet()) {
            DrainSequence drain = entry.getValue();
            drain.tick();
            if (drain.isComplete()) {
                toRemove.add(entry.getKey());
            }
        }

        toRemove.forEach(activeDrains::remove);
    }

    // -------------------------------------------------------------------------
    // DrainSequence — represents one pending drain propagation
    // -------------------------------------------------------------------------

    private static class DrainSequence {
        private final ServerLevel level;
        private final List<BlockPos> path;
        private int currentStep = 0;
        private int ticksWaited = 0;

        DrainSequence(ServerLevel level, List<BlockPos> path) {
            this.level = level;
            this.path = path;
        }

        void tick() {
            ticksWaited++;
            // On the first tick (tick 0) process step 0 immediately so the
            // block under farmland drains right away. Then wait between steps.
            if (currentStep == 0 || ticksWaited >= DRAIN_TICK_INTERVAL) {
                ticksWaited = 0;
                processCurrentStep();
                currentStep++;
            }
        }

        boolean isComplete() {
            return currentStep >= path.size();
        }

        private void processCurrentStep() {
            if (currentStep >= path.size()) return;

            BlockPos pos = path.get(currentStep);
            BlockState current = level.getBlockState(pos);
            Block currentBlock = current.getBlock();

            if (!WetSandRegistry.INSTANCE.isWetVariant(currentBlock)) {
                // Block was changed externally — abort drain
                currentStep = path.size();
                return;
            }

            // Decrement wetness by one level
            WetSandRegistry.INSTANCE.getDrierVariant(currentBlock).ifPresent(drierBlock -> {
                level.setBlock(pos, drierBlock.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
                Misanthrope_world.LOGGER.debug(
                        "WaterConsumption: drained {} → {} at {}",
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(currentBlock),
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(drierBlock),
                        pos);
            });
        }
    }
}
