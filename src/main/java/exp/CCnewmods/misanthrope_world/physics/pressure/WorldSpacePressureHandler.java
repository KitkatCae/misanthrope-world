package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.pressure.network.WorldPressureNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side pressure physics driver for world-space (stationary) blocks.
 *
 * <h3>Two entry paths</h3>
 * <ol>
 *   <li><b>Pulse events</b> — {@link #applyPressurePulse} is called directly
 *       (e.g. from a brew-explosion outcome in Ousia). The target block and its
 *       six neighbours are immediately evaluated against the given ΔP and enter
 *       the active set. No volume detection needed.</li>
 *   <li><b>Continuous simulation</b> — any block in the active set is evaluated
 *       each tick. External pressure is sampled from MGE
 *       {@code EnvironmentGrid.getComposition().totalPressure()} on each exposed
 *       face; the maximum cross-face differential is used. Internal pressure is
 *       sampled from the face pointing toward the enclosed space interior.
 *       No block tags or flood-fill are needed — MGE has already equilibrated
 *       pressure across enclosed regions.</li>
 * </ol>
 *
 * <h3>Hybrid dirty-flag mechanism</h3>
 * <ul>
 *   <li>Blocks in the PLASTIC/FAILURE region are always evaluated every tick.</li>
 *   <li>Blocks in SAFE/ELASTIC that have been flagged dirty (via
 *       {@link BlockEvent.NeighborNotifyEvent}) are re-evaluated on the next tick.</li>
 *   <li>Blocks in SAFE/ELASTIC that are not dirty are re-evaluated every
 *       {@link PressurePhysicsConfig#worldBlockScanInterval} ticks.</li>
 * </ul>
 * This means a sealed room window is essentially free (one read per second at
 * equilibrium) but reacts within one tick when a door opens or a gas canister
 * fires nearby.
 *
 * <h3>Active set lifecycle</h3>
 * Blocks enter the active set via {@link #applyPressurePulse}. They exit
 * automatically when their {@link BlockPressureState#isIdle()} and the interval
 * scan confirms ΔP is negligible. This bounds memory to blocks that have
 * actually experienced pressure events.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldSpacePressureHandler {

    private WorldSpacePressureHandler() {}

    private static final Logger LOGGER = LogManager.getLogger("MisWorld/PressureHandler");

    // ── Active set: levelKey → BlockPos → PressureVolumeState ────────────────
    // Each BlockPos entry is its own "micro-volume state" (one boundary block).
    // We use PressureVolumeState per-block (not per-room) because:
    //  - rooms are implicitly defined by MGE's pressure equilibrium
    //  - structural integrity is tracked per-block for simplicity;
    //    catastrophic room collapse is triggered when a configurable fraction
    //    of the room's blocks breach in a short window (future work).
    private static final Map<ResourceKey<Level>, Map<BlockPos, PressureVolumeState>>
            ACTIVE = new ConcurrentHashMap<>();

    // ── MGE reflection ────────────────────────────────────────────────────────

    private static volatile boolean mgeResolved = false;
    @Nullable private static Method mgeGetComposition  = null; // EnvironmentGrid.getComposition(Level, BlockPos) → GasComposition
    @Nullable private static Method mgeTotalPressure   = null; // GasComposition.totalPressure() → float
    @Nullable private static Method mgeEnqueueNeighbours = null; // EnvironmentGrid.enqueueWithNeighbours(Level, BlockPos)
    @Nullable private static Method mgeAddGas          = null; // EnvironmentGrid.addGas(Level, BlockPos, Gas, float)

    private static void resolveMge() {
        if (mgeResolved) return;
        mgeResolved = true;
        try {
            Class<?> grid  = Class.forName("exp.CCnewmods.mge.grid.EnvironmentGrid");
            Class<?> comp  = Class.forName("exp.CCnewmods.mge.gas.GasComposition");
            mgeGetComposition  = grid.getMethod("getComposition",
                    net.minecraft.world.level.Level.class, BlockPos.class);
            mgeTotalPressure   = comp.getMethod("totalPressure");
            mgeEnqueueNeighbours = grid.getMethod("enqueueWithNeighbours",
                    net.minecraft.world.level.Level.class, BlockPos.class);
            try {
                Class<?> gas = Class.forName("exp.CCnewmods.mge.gas.Gas");
                mgeAddGas = grid.getMethod("addGas",
                        net.minecraft.world.level.Level.class, BlockPos.class, gas, float.class);
            } catch (Exception ignored) { /* addGas optional */ }
        } catch (Exception e) {
            LOGGER.warn("[WorldPressureHandler] MGE reflection failed — gas pressure will use fallback: {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Applies a one-shot pressure pulse to {@code targetPos} and its six
     * immediate neighbours. Each block is evaluated against {@code deltaMbar}
     * immediately and enters the active set for continuous monitoring.
     *
     * <p>Called by Ousia's brew-explosion outcome resolver, and by any other
     * system that generates a sudden pressure event at a world position
     * (e.g. a gas canister rupture, a steam explosion, a vacuum collapse).
     *
     * @param level      the server level
     * @param targetPos  centre of the pressure event
     * @param deltaMbar  signed pressure differential to apply this tick
     *                   (positive = crushing, negative = expansion).
     *                   Should represent the overpressure of the event.
     */
    public static void applyPressurePulse(ServerLevel level, BlockPos targetPos, float deltaMbar) {
        resolveMge();
        PressurePhysicsConfig cfg = PressurePhysicsConfig.INSTANCE;

        // Apply to the target block itself and all six faces
        BlockPos[] targets = new BlockPos[7];
        targets[0] = targetPos.immutable();
        Direction[] dirs = Direction.values();
        for (int i = 0; i < dirs.length; i++) targets[i + 1] = targetPos.relative(dirs[i]).immutable();

        for (BlockPos pos : targets) {
            BlockState state = level.getBlockState(pos);
            BlockPhysicsData bpd = BlockPhysicsRegistry.get(state);
            if (bpd == null || bpd.pressure == null) continue;

            PressureVolumeState vol = getOrCreateVolume(level.dimension(), pos);
            BlockPressureState bs   = vol.getOrCreate(pos);
            bs.pressureDirty = true;

            evaluateBlock(level, pos, state, bpd.pressure, bs, vol, deltaMbar, cfg);
        }
    }

    /**
     * Returns the active {@link PressureVolumeState} for a block position,
     * creating it if absent. Used by MVSE and Ousia to inspect state without
     * triggering evaluation.
     */
    public static @Nullable PressureVolumeState getVolumeState(
            ResourceKey<Level> dim, BlockPos pos) {
        Map<BlockPos, PressureVolumeState> levelMap = ACTIVE.get(dim);
        return levelMap == null ? null : levelMap.get(pos);
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    /**
     * Server-level tick — evaluate all active world-space pressure blocks.
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        resolveMge();
        PressurePhysicsConfig cfg = PressurePhysicsConfig.INSTANCE;
        if (!cfg.enabled.get()) return;

        Map<BlockPos, PressureVolumeState> levelMap = ACTIVE.get(level.dimension());
        if (levelMap == null || levelMap.isEmpty()) return;

        long tick     = level.getGameTime();
        int  budget   = cfg.maxWorldBlocksPerTick.get();
        int  interval = cfg.worldBlockScanInterval.get();
        int  evaluated = 0;

        List<BlockPos> toRemove = new ArrayList<>();

        for (Map.Entry<BlockPos, PressureVolumeState> entry : levelMap.entrySet()) {
            if (evaluated >= budget) break;

            BlockPos pos = entry.getKey();
            PressureVolumeState vol = entry.getValue();
            BlockPressureState bs  = vol.blockStates.get(pos);

            if (bs == null) {
                toRemove.add(pos);
                continue;
            }

            // Decide whether to evaluate this tick
            boolean inActiveStress = bs.deformationStage > 0
                    || Math.abs(bs.elasticDeformAmount) > 0.01f
                    || bs.inflationFraction > 0.01f;
            boolean dueForScan = (tick % interval) == (Math.abs(pos.hashCode()) % interval);
            boolean shouldEval = inActiveStress || bs.pressureDirty || dueForScan;

            if (!shouldEval) continue;

            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                toRemove.add(pos);
                continue;
            }

            BlockPhysicsData bpd = BlockPhysicsRegistry.get(state);
            if (bpd == null || bpd.pressure == null) {
                if (bs.isIdle()) toRemove.add(pos);
                continue;
            }

            float deltaMbar = sampleDeltaMbar(level, pos, state);
            bs.pressureDirty = false;

            evaluateBlock(level, pos, state, bpd.pressure, bs, vol, deltaMbar, cfg);
            evaluated++;

            if (bs.isIdle() && !inActiveStress) toRemove.add(pos);
        }

        toRemove.forEach(levelMap::remove);
    }

    /**
     * Neighbour-notify event — dirty-flag any active blocks adjacent to a
     * changed position so they get re-evaluated next tick.
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) return;
        ResourceKey<Level> dim = ((Level) event.getLevel()).dimension();
        Map<BlockPos, PressureVolumeState> levelMap = ACTIVE.get(dim);
        if (levelMap == null || levelMap.isEmpty()) return;

        BlockPos changed = event.getPos();
        // Mark any active block within 1 block of the changed position as dirty
        for (Direction dir : Direction.values()) {
            PressureVolumeState vol = levelMap.get(changed.relative(dir));
            if (vol != null) vol.markDirty(changed.relative(dir));
        }
        // Also mark the changed block itself
        PressureVolumeState vol = levelMap.get(changed);
        if (vol != null) vol.markDirty(changed);
    }

    /**
     * Level unload — clear all state for that level.
     */
    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel sl) {
            ACTIVE.remove(sl.dimension());
        }
    }

    // ── Pressure sampling ─────────────────────────────────────────────────────

    /**
     * Samples the signed pressure differential (external − internal) across the
     * block at {@code pos}.
     *
     * <p>For each of the six faces, "external" is the pressure on the outer side
     * and "internal" is on the inner side. We take the maximum magnitude
     * differential across all faces — the face under the most stress determines
     * the block's response.
     *
     * <p>Pressure contributions:
     * <ul>
     *   <li>Gas pressure from MGE {@code EnvironmentGrid.getComposition(pos).totalPressure()}</li>
     *   <li>Fluid hydrostatic pressure from {@link FluidPressureSampler#getFluidColumnPressureMbar}</li>
     * </ul>
     * Both sides of each face are sampled and summed independently.
     */
    private static float sampleDeltaMbar(ServerLevel level, BlockPos pos, BlockState state) {
        float maxAbsDelta   = 0f;
        float bestSignedDelta = 0f;

        for (Direction face : Direction.values()) {
            BlockPos outerPos = pos.relative(face);
            BlockPos innerPos = pos.relative(face.getOpposite());

            float outer = sampleTotalPressureAt(level, outerPos);
            float inner = sampleTotalPressureAt(level, innerPos);

            float delta  = outer - inner;
            float absDelta = Math.abs(delta);

            if (absDelta > maxAbsDelta) {
                maxAbsDelta   = absDelta;
                bestSignedDelta = delta;
            }
        }

        return bestSignedDelta;
    }

    /**
     * Returns total pressure in mbar at {@code pos}: gas pressure + fluid
     * hydrostatic pressure.
     */
    private static float sampleTotalPressureAt(ServerLevel level, BlockPos pos) {
        float gas   = sampleGasPressureMbar(level, pos);
        float fluid = FluidPressureSampler.getFluidColumnPressureMbar(level, pos);
        return gas + fluid;
    }

    /**
     * Reads total gas pressure from MGE EnvironmentGrid at {@code pos}.
     * Falls back to standard atmosphere (1013.25 mbar) if MGE is absent.
     */
    private static float sampleGasPressureMbar(ServerLevel level, BlockPos pos) {
        if (mgeGetComposition == null || mgeTotalPressure == null) {
            return 1013.25f; // standard atmosphere fallback
        }
        try {
            Object composition = mgeGetComposition.invoke(null, level, pos);
            if (composition == null) return 1013.25f;
            return (float) mgeTotalPressure.invoke(composition);
        } catch (Exception e) {
            return 1013.25f;
        }
    }

    // ── Block evaluation ──────────────────────────────────────────────────────

    /**
     * Runs one tick of pressure yield-curve evaluation for a single block,
     * then dispatches deform/breach callbacks.
     */
    private static void evaluateBlock(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            BlockPhysicsData.PressureData pd,
            BlockPressureState bs,
            PressureVolumeState vol,
            float deltaMbar,
            PressurePhysicsConfig cfg) {

        long tick = level.getGameTime();

        var result = BlockPressureEvaluator.applyDeltaPressure(bs, pd, deltaMbar, cfg, level, pos);

        // Inflation deflation tick in safe region
        if (result == BlockPressureEvaluator.EvalResult.SAFE) {
            BlockPressureEvaluator.tickDeflation(bs, pd, cfg);
            vol.tickRecovery();
        }

        if (result == BlockPressureEvaluator.EvalResult.BREACH) {
            WorldBreachCallback.INSTANCE.onBreach(level, pos, pd, vol, deltaMbar);
            return;
        }

        if (result != BlockPressureEvaluator.EvalResult.SAFE) {
            sendDeformUpdateIfChanged(level, pos, bs, deltaMbar, pd);
        }
    }

    /**
     * Sends a deform packet if the visual state has changed enough since the
     * last send.
     */
    private static void sendDeformUpdateIfChanged(
            ServerLevel level,
            BlockPos pos,
            BlockPressureState bs,
            float deltaMbar,
            BlockPhysicsData.PressureData pd) {

        float visual = bs.totalVisualDeform(pd);
        if (Math.abs(visual - bs.lastSentElasticDeform) < 0.005f
                && Math.abs(bs.inflationFraction - bs.lastSentInflation) < 0.005f
                && bs.deformationStage == bs.lastSentStage) {
            return; // no meaningful change
        }

        WorldPressureNetwork.sendDeformPacket(level, pos, visual,
                bs.inflationFraction, bs.deformationStage, deltaMbar);

        bs.lastSentElasticDeform = visual;
        bs.lastSentInflation     = bs.inflationFraction;
        bs.lastSentStage         = bs.deformationStage;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static PressureVolumeState getOrCreateVolume(
            ResourceKey<Level> dim, BlockPos pos) {
        return ACTIVE
                .computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(pos.immutable(), k -> {
                    PressureVolumeState vol = new PressureVolumeState();
                    vol.getOrCreate(k); // seed the per-block state
                    return vol;
                });
    }

    /**
     * Exposes the MGE enqueue method for use by {@link WorldBreachCallback}
     * when venting gas after a breach.
     */
    static @Nullable Method getMgeEnqueueNeighbours() {
        resolveMge();
        return mgeEnqueueNeighbours;
    }

    static @Nullable Method getMgeAddGas() {
        resolveMge();
        return mgeAddGas;
    }
}
