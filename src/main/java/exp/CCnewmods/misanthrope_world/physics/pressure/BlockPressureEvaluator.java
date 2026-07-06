package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Stateless per-block pressure yield-curve evaluator.
 *
 * <p>Given a block's current {@link BlockPressureState}, its
 * {@link BlockPhysicsData.PressureData}, and the signed pressure differential
 * (ΔP = external − internal in mbar), advances the state through:
 * <ul>
 *   <li><b>SAFE</b> — elastic recovery; no permanent damage</li>
 *   <li><b>ELASTIC</b> — proportional deformation, reversible; fatigue accumulates</li>
 *   <li><b>PLASTIC</b> — stepped deformation stages with tension pauses; crack sources</li>
 *   <li><b>FAILURE</b> — immediate breach</li>
 * </ul>
 *
 * <p>This class does not send network packets, apply breach consequences, or
 * modify world state — those are the caller's responsibility via
 * {@link IDeformCallback} and {@link IBreachCallback}. It only mutates the
 * {@link BlockPressureState} and registers/refreshes crack sources via
 * {@link CrackPropagator}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * BlockPressureEvaluator.EvalResult result = BlockPressureEvaluator.applyDeltaPressure(
 *         blockState, pd, deltaMbar, cfg, level, pos);
 * if (result == EvalResult.BREACH) {
 *     breachCallback.onBreach(level, pos, pd, pd.breachMode(), blockState.lastDeltaMbar);
 * } else if (result != EvalResult.SAFE) {
 *     deformCallback.onDeformUpdate(level, pos, blockState, deltaMbar, pd);
 * }
 * }</pre>
 */
public final class BlockPressureEvaluator {

    private BlockPressureEvaluator() {}

    // ── Result ────────────────────────────────────────────────────────────────

    /**
     * Outcome of a single pressure evaluation tick for one block.
     */
    public enum EvalResult {
        /** ΔP is below elastic yield; block is recovering. No network update needed. */
        SAFE,
        /** ΔP is in the elastic region; visual deform changed. Send deform packet. */
        ELASTIC,
        /** ΔP is in the plastic region; stage or deform changed. Send deform packet. */
        PLASTIC,
        /** Block has exceeded ultimate strength or inflation limit. Caller must breach. */
        BREACH
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Evaluates one tick of pressure physics for a single boundary block.
     *
     * @param state      the block's mutable accumulated state; mutated in-place
     * @param pd         pressure material data for this block
     * @param deltaMbar  signed pressure differential: external − internal (mbar).
     *                   Positive = crushing (external > internal).
     *                   Negative = expansion (internal > external).
     * @param cfg        pressure config for global multipliers
     * @param level      server level (for CrackPropagator access)
     * @param pos        world-space position of this block (for crack source key)
     * @return evaluation result — caller decides whether to send packets/breach
     */
    public static EvalResult applyDeltaPressure(
            BlockPressureState state,
            BlockPhysicsData.PressureData pd,
            float deltaMbar,
            PressurePhysicsConfig cfg,
            ServerLevel level,
            BlockPos pos) {

        state.lastDeltaMbar = deltaMbar;

        float absDelta    = Math.abs(deltaMbar);
        boolean compressive = deltaMbar > 0; // external > internal

        // Expansion simulation can be disabled globally
        if (!compressive && !cfg.simulateExpansion.get()) {
            return recoverSafe(state);
        }

        float scaledAbs = absDelta * cfg.externalPressureScale.get().floatValue();
        var region = pd.region(scaledAbs, compressive);

        return switch (region) {
            case SAFE    -> recoverSafe(state);
            case ELASTIC -> evalElastic(state, pd, scaledAbs, compressive, cfg, level, pos);
            case PLASTIC -> evalPlastic(state, pd, scaledAbs, compressive, cfg, level, pos);
            case FAILURE -> EvalResult.BREACH;
        };
    }

    // ── Region handlers ───────────────────────────────────────────────────────

    private static EvalResult recoverSafe(BlockPressureState state) {
        // Elastic recovery — deform decays toward zero each tick
        state.elasticDeformAmount *= 0.85f;
        state.stageTick    = 0;
        state.inStagePause = false;
        // pressureDirty cleared by caller after evaluation
        // Crack source expires naturally via CrackPropagator heal rate
        return EvalResult.SAFE;
    }

    private static EvalResult evalElastic(
            BlockPressureState state,
            BlockPhysicsData.PressureData pd,
            float absDelta,
            boolean compressive,
            PressurePhysicsConfig cfg,
            ServerLevel level,
            BlockPos pos) {

        // Proportional elastic deform — fully recoverable
        float fraction = absDelta / pd.plasticYieldMbar();
        state.elasticDeformAmount = (compressive ? -1f : 1f)
                * fraction * 0.15f; // max 0.15 blocks inward/outward

        // Partial inflation for inflatable blocks even in elastic region
        if (!compressive && pd.inflatable()) {
            float inflateRate = pd.inflationRatePerMbar()
                    * cfg.inflationRateScale.get().floatValue()
                    * Math.max(0f, absDelta - pd.elasticYieldMbar());
            state.inflationFraction = Math.min(
                    pd.maxInflationFraction() * 0.5f,
                    state.inflationFraction + inflateRate);
        }

        // Fatigue accumulation — very slow, but persistent
        if (cfg.simulateFatigue.get()) {
            state.fatigueStress += pd.elasticFatigueRate();
            float crackThreshMbar = pd.ultimateStrengthMbar() * pd.crackThresholdFraction();
            if (state.fatigueStress * pd.ultimateStrengthMbar() > crackThreshMbar) {
                registerCrackSource(level, pos, absDelta, pd, state);
            }
        }

        return EvalResult.ELASTIC;
    }

    private static EvalResult evalPlastic(
            BlockPressureState state,
            BlockPhysicsData.PressureData pd,
            float absDelta,
            boolean compressive,
            PressurePhysicsConfig cfg,
            ServerLevel level,
            BlockPos pos) {

        // Full elastic-extent deformation
        state.elasticDeformAmount = (compressive ? -1f : 1f) * 0.15f;

        // Inflation for inflatable blocks expanding under internal pressure
        if (!compressive && pd.inflatable()) {
            float inflateRate = pd.inflationRatePerMbar()
                    * cfg.inflationRateScale.get().floatValue()
                    * Math.max(0f, absDelta - pd.elasticYieldMbar());
            state.inflationFraction = Math.min(
                    pd.maxInflationFraction(),
                    state.inflationFraction + inflateRate);
            if (state.inflationFraction >= pd.maxInflationFraction()) {
                // Fully inflated — tear breach
                return EvalResult.BREACH;
            }
        }

        // Crack source at or above the crack threshold mbar
        float crackThreshMbar = pd.ultimateStrengthMbar() * pd.crackThresholdFraction();
        if (absDelta >= crackThreshMbar) {
            float cp = PressureCrackSource.computeCrackPressure(
                    absDelta, crackThreshMbar, pd.effectiveUltimate(compressive));
            registerCrackSource(level, pos, cp, pd, state);
        }

        // Stage tick / tension pause logic
        if (state.inStagePause) {
            state.pauseTicksRemaining--;
            if (state.pauseTicksRemaining <= 0) {
                state.inStagePause = false;
                state.deformationStage++;
                if (state.deformationStage >= pd.deformationStageCount()) {
                    return EvalResult.BREACH;
                }
                // Stage advance — caller sends stage-advance packet
            }
            // Still in pause; fall through to return PLASTIC so caller sends
            // the current deform packet (for tension-pause flicker)
        } else {
            state.stageTick++;
            int pauseTicks = (int)(pd.stagePauseTicks()
                    * cfg.stagePauseMultiplier.get().floatValue());
            if (state.stageTick >= pauseTicks) {
                state.inStagePause        = true;
                state.pauseTicksRemaining = pauseTicks;
                state.stageTick           = 0;
                // Caller sends tension-pause packet
            }
        }

        return EvalResult.PLASTIC;
    }

    // ── Crack source helpers ──────────────────────────────────────────────────

    /**
     * Registers or refreshes a {@link PressureCrackSource} with
     * {@link CrackPropagator} for the given position.
     */
    public static void registerCrackSource(
            ServerLevel level,
            BlockPos pos,
            float crackPressureOrAbsDelta,
            BlockPhysicsData.PressureData pd,
            BlockPressureState state) {
        try {
            String sourceId = "misanthrope_world:pressure:" + pos.asLong();
            if (state.crackSourceActive) {
                // Refresh: remove old entry then re-add with updated pressure
                CrackPropagator.removeSource(sourceId);
            }
            CrackPropagator.addSource(new PressureCrackSource(pos, crackPressureOrAbsDelta));
            state.crackSourceActive = true;
        } catch (Exception ignored) {
            // CrackPropagator soft-dep — pressure still simulates without cracks
        }
    }

    /**
     * Deflates an inflatable block at {@code deflationRateScale} per tick
     * when ΔP drops. Called by the owning handler in the SAFE case for
     * inflatable blocks that still have residual inflation.
     */
    public static void tickDeflation(BlockPressureState state,
                                     BlockPhysicsData.PressureData pd,
                                     PressurePhysicsConfig cfg) {
        if (state.inflationFraction > 0f && pd.inflatable()) {
            float deflateRate = pd.inflationRatePerMbar()
                    * cfg.deflationRateScale.get().floatValue();
            state.inflationFraction = Math.max(0f, state.inflationFraction - deflateRate);
        }
    }
}
