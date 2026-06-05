package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.mge.compat.MisCoreBridge;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers Misanthrope Core as the {@link MisCoreBridge.StructuralAdapter}
 * for MGE's shockwave system, and handles all shockwave / kinetic stress
 * injection into {@link StructuralStressField}.
 *
 * <p>Registered on {@link FMLLoadCompleteEvent} — after both mods are fully
 * loaded — so MGE's class is guaranteed to exist when we call
 * {@link MisCoreBridge#register}.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShockwaveStressAdapter implements MisCoreBridge.StructuralAdapter {

    private static final boolean MGE_LOADED = ModList.get().isLoaded("mge");

    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        if (!MGE_LOADED) return;
        MisCoreBridge.register(new ShockwaveStressAdapter());
    }

    // ── StructuralAdapter ─────────────────────────────────────────────────────

    @Override
    public float getShockwaveAbsorption(BlockState state) {
        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        if (data.structural == null) return 0f;
        return (float) data.structural.shockwaveAbsorption();
    }

    @Override
    public float getShockwaveAmplification(BlockState state) {
        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        if (data.structural == null) return 1f;
        return (float) data.structural.shockwaveAmplification();
    }

    @Override
    public void injectShockwaveStress(ServerLevel level, BlockPos pos, float strength) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        if (data.structural == null) return;

        // Normalize strength to stress fraction:
        // strength 1.0 → full failure threshold stress
        // strength 0.1 → 10% of failure threshold — causes cracking but not failure
        float stressFraction = strength
                / (float) state.getExplosionResistance(level, pos, null)
                * 600f; // 600 = reference blast resistance for stone

        stressFraction = Math.min(stressFraction, 2.0f); // cap at 200% — above that it's instant failure

        if (stressFraction >= (float) data.structural.failureThresholdFraction()) {
            FailureDispatcher.dispatch(level, pos, state, data.structural);
        } else if (stressFraction >= (float) data.structural.crackThresholdFraction()) {
            float pressure = stressFraction * 50f;
            String sourceId = "misanthrope_core:shockwave:" + pos.asLong();
            // Shockwave crack sources use STRUCTURAL cause — same visual as load cracks
            // but identified by the "shockwave:" prefix for debugging
            CrackPropagator.addSource(new StructuralCrackSource(pos, pressure,
                    level.getGameTime(), sourceId));
        }
    }

    @Override
    public void injectKineticStress(ServerLevel level, BlockPos pos, float torqueNm) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        if (data.structural == null) return;

        // Convert torque to shear stress fraction
        // shear_strength_kpa is the limit; torque is game-scaled N·m
        double shearStrength = data.structural.shearStrengthKpa();
        if (shearStrength <= 0) return;

        float stressFraction = (float) (torqueNm / shearStrength);

        if (stressFraction >= (float) data.structural.failureThresholdFraction()) {
            FailureDispatcher.dispatch(level, pos, state, data.structural);
        } else if (stressFraction >= (float) data.structural.crackThresholdFraction()) {
            float pressure = stressFraction * 50f;
            String sourceId = "misanthrope_core:kinetic:" + pos.asLong();
            CrackPropagator.addSource(new StructuralCrackSource(pos, pressure,
                    level.getGameTime(), sourceId));
        }
    }
}
