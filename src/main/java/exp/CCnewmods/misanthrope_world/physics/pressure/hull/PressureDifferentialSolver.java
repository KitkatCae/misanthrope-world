package exp.CCnewmods.misanthrope_world.physics.pressure.hull;

import exp.CCnewmods.misanthrope_world.physics.pressure.hull.network.HullPressureNetwork;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.pressure.BlockPressureEvaluator;
import exp.CCnewmods.misanthrope_world.physics.pressure.BlockPressureState;
import exp.CCnewmods.misanthrope_world.physics.pressure.PressurePhysicsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Solves pressure differentials for a single ship per server tick.
 *
 * <h3>Tick budget</h3>
 * Full exterior re-sample: every {@link HullPressureState#EXTERIOR_CACHE_TICKS} ticks.
 * Full interior BFS: every {@link HullPressureState#INTERIOR_CACHE_TICKS} ticks.
 * Per-block stage evaluation: every tick (cheap — reads cached ΔP values).
 *
 * <h3>Yield curve step-by-step</h3>
 * For each hull face block with |ΔP| in the plastic region:
 * <ol>
 *   <li>Accumulate {@code stageTick++}</li>
 *   <li>If {@code stageTick >= pd.stagePauseTicks()} and not {@code inStagePause}:
 *       enter pause, set {@code inStagePause = true}, reset {@code stageTick = 0}</li>
 *   <li>During pause: tick {@code pauseTicksRemaining} down.
 *       Emit crack source, send deformation packet at current stage visual.</li>
 *   <li>When {@code pauseTicksRemaining == 0}: advance {@code deformationStage++}.
 *       If {@code deformationStage >= pd.deformationStageCount()}: trigger breach.</li>
 *   <li>On stage advance: emit stage sound + particle packet to clients.</li>
 * </ol>
 *
 * <h3>Inflation loop</h3>
 * For inflatable blocks with ΔP < 0 (internal > external):
 * {@code inflationFraction += pd.inflationRatePerMbar() × (|ΔP| - elasticYield)}.
 * Server tracks volume contribution from {@link HullPressureState#computeInflatedVolumeBonus}.
 * When {@code inflationFraction >= pd.maxInflationFraction()}: trigger TEAR breach.
 *
 * <h3>Breach handling</h3>
 * On breach: remove block from ship space, call
 * {@code EnvironmentGrid.enqueueWithNeighbours} at the world-space position
 * (so MGE diffusion picks up the opening), send breach packet to clients.
 * If {@link HullPressureState#registerBreach()} returns true: trigger
 * {@code ShipDisassembler.disassemble}.
 *
 * <h3>MGE gas vent on VENT/TEAR breach</h3>
 * Calls {@code EnvironmentGrid.addGas} at the world-space breach point with
 * the interior gas composition, approximating the rush of gas through the tear.
 */
public final class PressureDifferentialSolver {

    private PressureDifferentialSolver() {}

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("MVS/PressureSolver");

    // ── Cached exterior pressure map per ship ─────────────────────────────────
    // Map<shipId, Map<shipSpacePos, externalMbar>>
    private static final Map<Long, Map<BlockPos, Float>> EXTERIOR_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long, Long> EXTERIOR_LAST_TICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── MGE gas vent reflection ───────────────────────────────────────────────
    private static volatile boolean mgeVentResolved = false;
    private static Method mgeAddGas  = null; // EnvironmentGrid.addGas(Level, BlockPos, GasComposition, float)
    private static Method mgeEnqueue = null; // EnvironmentGrid.enqueueWithNeighbours(Level, BlockPos)

    private static void resolveMgeVent() {
        if (mgeVentResolved) return;
        mgeVentResolved = true;
        if (!ModList.get().isLoaded("mge")) return;
        try {
            Class<?> g = Class.forName("exp.CCnewmods.mge.grid.EnvironmentGrid");
            mgeEnqueue = g.getMethod("enqueueWithNeighbours",
                    net.minecraft.world.level.Level.class, BlockPos.class);
            // addGas signature may vary — try common ones
            try {
                mgeAddGas = g.getMethod("addGas",
                        net.minecraft.world.level.Level.class, BlockPos.class,
                        Class.forName("exp.CCnewmods.mge.grid.GasComposition"), float.class);
            } catch (Exception ignored) {
                // addGas may not exist or have a different signature — vent without gas burst
            }
        } catch (Exception e) {
            LOGGER.warn("[PressureDifferentialSolver] MGE vent reflection failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Main tick entry
    // -------------------------------------------------------------------------

    /**
     * Runs one tick of pressure differential physics for the given ship.
     * Called from {@link HullPressureHandler#serverTick}.
     *
     * @param level  server level
     * @param ship   the VS2 ship
     * @param state  per-ship mutable state
     * @param tick   current game time
     * @param cfg    pressure physics config
     */
    public static void tick(ServerLevel level, LoadedServerShip ship,
                             HullPressureState state, long tick,
                             PressurePhysicsConfig cfg) {
        resolveMgeVent();

        // ── 1. Refresh exterior cache ─────────────────────────────────────────
        long extLast = EXTERIOR_LAST_TICK.getOrDefault(ship.getId(), -1L);
        Map<BlockPos, Float> exterior;
        if (tick - extLast >= HullPressureState.EXTERIOR_CACHE_TICKS) {
            exterior = HullExternalPressureSampler.sample(level, ship, state);
            EXTERIOR_CACHE.put(ship.getId(), exterior);
            EXTERIOR_LAST_TICK.put(ship.getId(), tick);
        } else {
            exterior = EXTERIOR_CACHE.getOrDefault(ship.getId(), Map.of());
        }

        // ── 2. Refresh interior cache ─────────────────────────────────────────
        if (tick - state.internalPressureLastSampledTick
                >= HullPressureState.INTERIOR_CACHE_TICKS) {
            float fallback = exterior.isEmpty() ? 1013.25f
                    : exterior.values().stream().findFirst().orElse(1013.25f);
            var scan = HullInteriorSampler.scan(level, ship, fallback);
            state.cachedInternalPressureMbar  = scan.internalPressureMbar();
            state.baselineInteriorVolume      = scan.interiorVolume();
            state.effectiveInteriorVolume     = state.totalVolume();
            state.internalPressureLastSampledTick = tick;
        }

        float internal = state.cachedInternalPressureMbar;

        // ── 3. Per-block evaluation ───────────────────────────────────────────
        // Delegates yield-curve logic to BlockPressureEvaluator (MisWorld).
        // This solver retains ship-specific concerns: VS2 coordinate handling,
        // breach consequences (ship block removal, ShipDisassembler),
        // and packet routing via HullPressureNetwork.
        boolean anyStress    = false;
        List<BlockPos> breach = new ArrayList<>();

        for (Map.Entry<BlockPos, Float> entry : exterior.entrySet()) {
            BlockPos shipPos  = entry.getKey();
            float external    = entry.getValue();
            float delta       = external - internal;  // + = crush, - = expansion

            BlockState bs = level.getBlockState(shipPos);
            BlockPhysicsData bpd = BlockPhysicsRegistry.get(bs);
            BlockPhysicsData.PressureData pd = (bpd != null) ? bpd.pressure : null;

            if (pd == null) {
                clearBlockState(state, shipPos);
                continue;
            }

            BlockPressureState blockState = state.getOrCreate(shipPos);

            // ── Delegate yield curve to MisWorld ──────────────────────────────
            var result = BlockPressureEvaluator.applyDeltaPressure(
                    blockState, pd, delta, cfg, level, shipPos);

            switch (result) {
                case SAFE -> {
                    BlockPressureEvaluator.tickDeflation(blockState, pd, cfg);
                    state.tickRecovery();
                }
                case ELASTIC -> {
                    anyStress = true;
                    sendDeformationPacket(level, ship, shipPos, blockState, delta, pd);
                }
                case PLASTIC -> {
                    anyStress = true;
                    // Check if a stage just advanced (pauseTicksRemaining just hit 0)
                    // BlockPressureEvaluator sets inStagePause=false and increments
                    // deformationStage when the pause ends — detect via lastSentStage.
                    if (!blockState.inStagePause
                            && blockState.deformationStage != blockState.lastSentStage
                            && blockState.lastSentStage >= 0) {
                        sendStagePacket(level, ship, shipPos, blockState, delta, pd);
                    } else if (blockState.inStagePause
                            && blockState.lastSentStage == blockState.deformationStage - 1) {
                        // Just entered tension pause this tick
                        sendTensionPausePacket(level, ship, shipPos, blockState, delta);
                    }
                    sendDeformationPacket(level, ship, shipPos, blockState, delta, pd);
                }
                case BREACH -> breach.add(shipPos);
            }
        }

        // ── 4. Process breaches ───────────────────────────────────────────────
        for (BlockPos shipPos : breach) {
            processBreach(level, ship, shipPos, state, cfg);
        }

        // ── 5. Recovery tick if no stress ─────────────────────────────────────
        if (!anyStress) {
            state.tickRecovery();
        }
    }

    // -------------------------------------------------------------------------
    // Breach handling
    // -------------------------------------------------------------------------

    private static void processBreach(ServerLevel level, LoadedServerShip ship,
                                       BlockPos shipPos, HullPressureState state,
                                       PressurePhysicsConfig cfg) {
        BlockState bs = level.getBlockState(shipPos);
        if (bs.isAir()) return; // already gone

        BlockPhysicsData bpd = BlockPhysicsRegistry.get(bs);
        BlockPhysicsData.PressureData pd = (bpd != null) ? bpd.pressure : null;
        BlockPhysicsData.PressureBreachMode mode = (pd != null)
                ? pd.breachMode()
                : BlockPhysicsData.PressureBreachMode.CRUMBLE;

        // Remove block from ship space
        level.setBlock(shipPos, Blocks.AIR.defaultBlockState(), 3);
        state.blockStates.remove(shipPos);

        // Notify MGE: new opening, trigger diffusion
        notifyMgeVent(level, ship, shipPos);

        // Vent gas burst for VENT and TEAR modes
        if (mode == BlockPhysicsData.PressureBreachMode.VENT
                || mode == BlockPhysicsData.PressureBreachMode.TEAR) {
            ventGas(level, ship, shipPos, state);
        }

        // Send breach event packet to clients
        HullPressureNetwork.sendBreachPacket(level, ship, shipPos, mode,
                state.blockStates.getOrDefault(shipPos,
                        new BlockPressureState()).lastDeltaMbar);

        // Update structural integrity — catastrophic failure check
        boolean catastrophic = state.registerBreach();
        if (catastrophic && cfg.catastrophicBreachCount.get() > 0) {
            LOGGER.info("[MVS/Pressure] Ship {} catastrophic hull failure — disassembling",
                    ship.getId());
            exp.CCnewmods.misanthrope_world.physics.structural.vs2.StructuralShipDisassembler
                    .disassemble(level, ship, 0, null, false);
        }
    }

    // ── MGE notifications ─────────────────────────────────────────────────────

    private static void notifyMgeVent(ServerLevel level, LoadedServerShip ship,
                                       BlockPos shipPos) {
        if (mgeEnqueue == null) return;
        // Transform ship-space position to world-space for MGE diffusion
        var transform = ship.getTransform();
        var wv = transform.getShipToWorld().transformPosition(
                new org.joml.Vector3d(shipPos.getX() + 0.5,
                        shipPos.getY() + 0.5, shipPos.getZ() + 0.5));
        BlockPos worldPos = BlockPos.containing(wv.x, wv.y, wv.z);
        try {
            mgeEnqueue.invoke(null, level, worldPos);
            mgeEnqueue.invoke(null, level, shipPos); // also notify ship-space side
        } catch (Exception ignored) {}
    }

    private static void ventGas(ServerLevel level, LoadedServerShip ship,
                                 BlockPos shipPos, HullPressureState state) {
        if (mgeAddGas == null) return;
        // We can't easily get GasComposition without a hard dep on MGE,
        // so we attempt an additive ambient-gas burst at the world position.
        // The gas burst amount is proportional to interior pressure excess.
        // If this fails (signature mismatch), MGE diffusion handles the rest.
        try {
            var transform = ship.getTransform();
            var wv = transform.getShipToWorld().transformPosition(
                    new org.joml.Vector3d(shipPos.getX() + 0.5,
                            shipPos.getY() + 0.5, shipPos.getZ() + 0.5));
            BlockPos worldPos = BlockPos.containing(wv.x, wv.y, wv.z);
            // Get ambient gas composition at ship interior (null = use ambient)
            float burstAmount = Math.max(0f,
                    state.cachedInternalPressureMbar - 1013.25f) / 100f;
            if (burstAmount > 0.01f) {
                mgeAddGas.invoke(null, level, worldPos, null, burstAmount);
            }
        } catch (Exception ignored) {}
    }

    // ── Packet helpers ────────────────────────────────────────────────────────

    private static void sendDeformationPacket(ServerLevel level, LoadedServerShip ship,
                                               BlockPos shipPos,
                                               BlockPressureState bs,
                                               float delta,
                                               BlockPhysicsData.PressureData pd) {
        float visual = bs.totalVisualDeform(pd);
        if (Math.abs(visual - bs.lastSentElasticDeform) < 0.005f
                && Math.abs(bs.inflationFraction - bs.lastSentInflation) < 0.005f
                && bs.deformationStage == bs.lastSentStage) return;

        HullPressureNetwork.sendDeformPacket(level, ship, shipPos,
                visual, bs.inflationFraction, bs.deformationStage, delta);

        bs.lastSentElasticDeform = visual;
        bs.lastSentInflation     = bs.inflationFraction;
        bs.lastSentStage         = bs.deformationStage;
    }

    private static void sendStagePacket(ServerLevel level, LoadedServerShip ship,
                                         BlockPos shipPos,
                                         BlockPressureState bs,
                                         float delta,
                                         BlockPhysicsData.PressureData pd) {
        HullPressureNetwork.sendStageAdvancePacket(level, ship, shipPos,
                bs.deformationStage, delta, pd.breachMode());
    }

    private static void sendTensionPausePacket(ServerLevel level, LoadedServerShip ship,
                                                BlockPos shipPos,
                                                BlockPressureState bs,
                                                float delta) {
        HullPressureNetwork.sendTensionPausePacket(level, ship, shipPos,
                bs.deformationStage, delta);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private static void clearBlockState(HullPressureState state, BlockPos pos) {
        var bs = state.blockStates.get(pos);
        if (bs != null && bs.isIdle()) state.blockStates.remove(pos);
    }

    /** Called when a ship is removed — clean up caches. */
    public static void onShipRemoved(long shipId) {
        EXTERIOR_CACHE.remove(shipId);
        EXTERIOR_LAST_TICK.remove(shipId);
    }
}
