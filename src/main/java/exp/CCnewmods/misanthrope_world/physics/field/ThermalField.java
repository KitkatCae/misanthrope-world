package exp.CCnewmods.misanthrope_world.physics.field;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import exp.CCnewmods.misanthrope_world.physics.bellows.IBellowsTarget;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.DynamicHeatSourceRegistry;
import exp.CCnewmods.misanthrope_world.physics.WorldSimulation;
import exp.CCnewmods.misanthrope_world.physics.simulation.ThermalSimulation;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import exp.CCnewmods.misanthrope_world.physics.structural.ThermalCrackSource;
import exp.CCnewmods.misanthrope_world.physics.structure.ThermalStructure;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import exp.CCnewmods.mge.grid.EnvironmentGrid;
import exp.CCnewmods.mge.gas.GasRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level manager for the thermal field.
 * <p>
 * Every server tick, for each registered heat source in the level:
 * 1. Retrieves or builds the {@link ThermalStructure} around it.
 * 2. Runs one {@link ThermalSimulation} tick with the current O₂ / bellows state.
 * 3. Writes spatially-resolved temperatures to every interior cell of the
 * structure via {@link EnvironmentGrid#setTemperature}.
 * 4. Writes induction temperatures directly to adjacent conductive block positions.
 * 5. Checks wall block temperatures against thermal crack thresholds and feeds
 * stress into {@link CrackPropagator} via {@link ThermalCrackSource}.
 * 6. Notifies {@link StructuralStressField} when a wall block crosses its
 * {@code strength_retention_curve} first threshold (thermal weakening).
 * <p>
 * ── Crucible injection ────────────────────────────────────────────────────────
 * {@link #injectStructureHeat} is called by CrucibleBlockEntity each tick to
 * write the crucible's measured interior temperature directly into the
 * EnvironmentGrid for all cells in the crucible's interior. The crucible manages
 * its own thermal model; ThermalField just propagates the result outward.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ThermalField {

    public static final int SIM_INTERVAL = 4;
    private static final int MAX_RESCANS_PER_TICK = 2;
    private static final int CHUNK_SCAN_INTERVAL = 40;

    private static final Map<ServerLevel, LevelState> STATES = new ConcurrentHashMap<>();

    private ThermalField() {
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            STATES.computeIfAbsent(level, LevelState::new).tick();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        STATES.clear();
    }

    @SubscribeEvent
    public static void onBlockChange(net.minecraftforge.event.level.BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        LevelState state = STATES.get(level);
        if (state != null) state.invalidateNear(event.getPos());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static double getTemperatureAt(ServerLevel level, BlockPos pos) {
        float gridTemp = EnvironmentGrid.getTemperature(level, pos);
        if (!Float.isNaN(gridTemp)) return gridTemp;
        return MisTemperatureAPI.getAmbientCelsius(level, pos);
    }

    public static void invalidate(ServerLevel level, BlockPos sourcePos) {
        LevelState state = STATES.get(level);
        if (state != null) state.invalidateSource(sourcePos);
    }

    /**
     * Called by CrucibleBlockEntity each server tick to write its measured
     * interior temperature into the EnvironmentGrid for all positions in the
     * crucible's interior set.
     *
     * <p>The crucible manages its own thermal model (CrucibleTemperatureModule).
     * This method simply propagates the result outward into the environment so
     * entities and systems near the crucible see the correct ambient temperature.
     *
     * @param level       server level
     * @param interiorSet all block positions inside the crucible structure
     * @param tempCelsius current measured interior temperature in °C
     */
    public static void injectStructureHeat(net.minecraft.world.level.Level level,
                                           Iterable<BlockPos> interiorSet,
                                           double tempCelsius) {
        if (level.isClientSide()) return;
        if (tempCelsius < 21.0) return;
        float temp = (float) tempCelsius;
        for (BlockPos pos : interiorSet) {
            if (level.isLoaded(pos)) {
                EnvironmentGrid.setTemperature(level, pos, temp);
            }
        }
    }

    // ── LevelState ────────────────────────────────────────────────────────────

    private static final class LevelState {
        private final ServerLevel level;
        private final Map<BlockPos, SimZone> zones = new LinkedHashMap<>();
        private final Queue<BlockPos> rescanQueue = new ArrayDeque<>();
        private int globalTick = 0;

        LevelState(ServerLevel level) {
            this.level = level;
        }

        void tick() {
            globalTick++;

            int rescanned = 0;
            while (!rescanQueue.isEmpty() && rescanned < MAX_RESCANS_PER_TICK) {
                BlockPos pos = rescanQueue.poll();
                rebuildZone(pos);
                rescanned++;
            }

            for (BlockPos dynPos : DynamicHeatSourceRegistry.getActive(level)) {
                if (!zones.containsKey(dynPos)) rescanQueue.add(dynPos.immutable());
            }

            if (globalTick % SIM_INTERVAL != 0) return;

            List<BlockPos> toRemove = new ArrayList<>();

            for (Map.Entry<BlockPos, SimZone> entry : zones.entrySet()) {
                BlockPos sourcePos = entry.getKey();
                SimZone zone = entry.getValue();

                if (!level.isLoaded(sourcePos)) continue;
                BlockState srcState = level.getBlockState(sourcePos);
                if (BlockPhysicsRegistry.get(srcState).emission == null
                        && !DynamicHeatSourceRegistry.isActive(level, sourcePos)) {
                    toRemove.add(sourcePos);
                    continue;
                }

                double ambient = MisTemperatureAPI.getAmbientCelsius(level, sourcePos);
                float o2 = EnvironmentGrid.getGas(level, sourcePos, GasRegistry.OXYGEN);
                float bellows = 1.0f;
                BlockEntity srcBe = level.getBlockEntity(sourcePos);
                if (srcBe instanceof IBellowsTarget bt) {
                    bellows = 1.0f + bt.getCurrentBellowsIntensity();
                }

                double newTemp = zone.simulation.tick(level, ambient, o2, bellows);
                writeThermalField(zone, newTemp, ambient);
                checkThermalCracks(zone, ambient);

                if (zone.structure.hasInductionSource()) tickInduction(zone, o2);
            }

            toRemove.forEach(pos -> {
                SimZone zone = zones.remove(pos);
                if (zone != null) clearThermalField(zone);
            });
        }

        // ── Temperature writes ────────────────────────────────────────────────

        private void writeThermalField(SimZone zone, double interiorTemp, double ambient) {
            for (BlockPos cell : zone.structure.interior) {
                double cellTemp = zone.simulation.getTemperatureAt(cell, ambient);
                if (cellTemp > ambient + 0.5) {
                    EnvironmentGrid.setTemperature(level, cell, (float) cellTemp);
                }
            }

            for (ThermalStructure.WallFace face : zone.structure.wallFaces) {
                BlockPos wallPos = face.interiorPos().relative(face.direction());
                double wallTemp = ambient + (interiorTemp - ambient)
                        * (1.0 - face.totalR() / Math.max(0.01, zone.structure.avgWallInsulationR));
                wallTemp = Math.max(ambient, wallTemp);
                if (wallTemp > ambient + 0.5) {
                    EnvironmentGrid.setTemperature(level, wallPos, (float) wallTemp);
                }
            }
        }

        private void clearThermalField(SimZone zone) {
            // Temperatures decay naturally once we stop writing; nothing to do explicitly
        }

        // ── Thermal crack coupling ────────────────────────────────────────────

        /**
         * For every wall block whose surface temperature exceeds its
         * {@code thermal_crack_threshold}, inject stress into {@link CrackPropagator}
         * via a {@link ThermalCrackSource}. Also notify {@link StructuralStressField}
         * when a block crosses its first strength-retention temperature threshold so
         * the structural system can re-evaluate column loads.
         */
        private void checkThermalCracks(SimZone zone, double ambient) {
            for (ThermalStructure.WallFace face : zone.structure.wallFaces) {
                BlockPos wallPos = face.interiorPos().relative(face.direction());
                if (!level.isLoaded(wallPos)) continue;

                float rawTemp = EnvironmentGrid.getTemperature(level, wallPos);
                if (Float.isNaN(rawTemp)) continue;
                double wallTempC = rawTemp;
                double deltaAboveAmbient = wallTempC - ambient;
                if (deltaAboveAmbient <= 0) continue;

                BlockState wallState = level.getBlockState(wallPos);
                BlockPhysicsData data = BlockPhysicsRegistry.get(wallState);

                // ── Off-gas threshold coupling ────────────────────────────────────────────
                // Tell OffGasHandler to check this block if it has thermal_offgas defined
                // and the temperature has crossed the threshold.
                if (data.thermalOffGas != null
                        && !Double.isNaN(data.thermalOffGas.thresholdCelsius())
                        && wallTempC >= data.thermalOffGas.thresholdCelsius()) {
                    WorldSimulation.onThermalOffgasThreshold(level, wallPos);
                }
                // ── Thermal cracking ──────────────────────────────────────────
                double threshold = data.thermalCrackThreshold;
                double rate = data.thermalCrackRate;
                if (!Double.isNaN(threshold) && rate > 0 && deltaAboveAmbient >= threshold) {
                    float pressure = (float) Math.min(50f,
                            (deltaAboveAmbient - threshold) / threshold * 25f + 1f);
                    String sourceId = "misanthrope_world:thermal:" + wallPos.asLong();
                    CrackPropagator.addSource(new ThermalCrackSource(
                            wallPos, pressure, level.getGameTime(), sourceId));
                }

                // ── Structural thermal weakening ──────────────────────────────
                if (data.structural != null
                        && !data.structural.strengthRetentionCurve().isEmpty()) {
                    double firstThreshold = data.structural.strengthRetentionCurve()
                            .get(0).celsius();
                    if (wallTempC >= firstThreshold) {
                        StructuralStressField.notifyThermalWeakening(level, wallPos);
                    }
                }
            }
        }

        // ── Induction ─────────────────────────────────────────────────────────

        private void tickInduction(SimZone zone, float o2) {
            for (ThermalStructure.HeatSourceEntry src : zone.structure.heatSources) {
                if (!src.isInduction()) continue;
                if (!src.isActive(o2)) continue;
                double watts = src.wattsPerBlock();

                for (Direction dir : Direction.values()) {
                    BlockPos adj = src.pos().relative(dir);
                    if (!level.isLoaded(adj)) continue;
                    BlockState adjState = level.getBlockState(adj);
                    BlockPhysicsData adjData = BlockPhysicsRegistry.get(adjState);
                    if (!adjData.electricallyConductive) continue;

                    float currentTemp = EnvironmentGrid.getTemperature(level, adj);
                    double ambientAdj = MisTemperatureAPI.getAmbientCelsius(level, adj);
                    if (Float.isNaN(currentTemp)) currentTemp = (float) ambientAdj;

                    double dT = (watts * ThermalSimulation.TICK_SCALE) / adjData.thermalMass;
                    double newTemp = Math.min(src.peakCelsius(), currentTemp + dT);
                    EnvironmentGrid.setTemperature(level, adj, (float) newTemp);
                }
            }
        }

        // ── Invalidation ──────────────────────────────────────────────────────

        void invalidateNear(BlockPos changedPos) {
            for (Map.Entry<BlockPos, SimZone> entry : zones.entrySet()) {
                SimZone zone = entry.getValue();
                if (zone.structure.interior.contains(changedPos)
                        || zone.structure.wallBlocks.contains(changedPos)) {
                    rescanQueue.add(entry.getKey());
                }
            }
        }

        void invalidateSource(BlockPos sourcePos) {
            zones.remove(sourcePos);
        }

        // ── Zone build ────────────────────────────────────────────────────────

        private void rebuildZone(BlockPos sourcePos) {
            if (!level.isLoaded(sourcePos)) return;
            int maxRadius = getDimensionMaxRadius(level);
            ThermalStructure structure = ThermalStructure.scan(level, sourcePos, maxRadius);

            double prevTemp = Double.NaN;
            SimZone existing = zones.get(sourcePos);
            if (existing != null) prevTemp = existing.simulation.getInteriorTemp();

            ThermalSimulation sim = new ThermalSimulation(structure);
            if (!Double.isNaN(prevTemp)) sim.setInteriorTemp(prevTemp);

            zones.put(sourcePos.immutable(), new SimZone(structure, sim));
        }

        private static int getDimensionMaxRadius(ServerLevel level) {
            ResourceLocation dimId = level.dimension().location();
            exp.CCnewmods.mge.dimension.DimensionAtmosphereProfile profile =
                    exp.CCnewmods.mge.dimension.DimensionAtmosphereLoader.get(dimId);
            if (profile != null) return profile.maxAirtightRadius;
            return exp.CCnewmods.misanthrope_world.physics.structure.ThermalStructure.DEFAULT_MAX_RADIUS;
        }

        private record SimZone(ThermalStructure structure, ThermalSimulation simulation) {
        }
    }
}
