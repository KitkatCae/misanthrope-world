package exp.CCnewmods.misanthrope_world.mixin.explosionoverhaul;

import com.vinlanx.explosionoverhaul.Config;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackStateMap;
import exp.CCnewmods.misanthrope_world.crackrender.world.VeinPropagator;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.structural.DynamicStressTracker;
import exp.CCnewmods.misanthrope_world.physics.structural.FragmentSplitter;
import exp.CCnewmods.misanthrope_world.physics.structural.ShipFragmentLauncher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Replaces Explosion Overhaul's {@code finalizeExplosion} debris handling
 * with our own fragment-ship + structural-stress pipeline.
 * <p>
 * ── What EO already does well, untouched by this mixin ──────────────────────
 * {@code CraterDeformer.getCraterBlocks}/{@code applyLargeExplosionLogic}/
 * {@code applySmallExplosionLogic} — the actual crater SHAPE — are a dense
 * Fibonacci-sphere raycast with per-ray energy depletion, genuinely similar
 * in spirit to vanilla Minecraft's own explosion algorithm (just far denser:
 * up to 75,000 rays vs vanilla's 1,352). That's good work and this mixin
 * doesn't touch it — {@code blocksToDestroy}/{@code blocksToLaunch} arrive
 * here exactly as EO computed them.
 * <p>
 * ── What this replaces ───────────────────────────────────────────────────────
 * The original {@code finalizeExplosion} spawns one particle, then for each
 * block in {@code blocksToLaunch} has a flat 35% chance to spawn an
 * individual vanilla {@code FallingBlockEntity} (single detached block,
 * generic gravity), then sets every block in {@code blocksToDestroy} to air.
 * <p>
 * This mixin instead: splits {@code blocksToLaunch} into its actual connected
 * components ({@link FragmentSplitter}), launches each component big enough
 * to matter as its own outward-flying VS2 ship ({@link ShipFragmentLauncher})
 * rather than scattering individual vanilla falling blocks, feeds
 * {@link DynamicStressTracker} for blocks bordering the destroyed region
 * (same unified stress model kinetic/shockwave impacts already use), and
 * seeds a real persisted crack-vein burst around the crater edge
 * ({@link VeinPropagator#generateImpactBurst}) instead of relying solely on
 * EO's block-specific cosmetic effects (glass breaking, dripstone falling,
 * etc. — those are untouched, they're a different concern).
 * <p>
 * Deliberate departure from EO's own behaviour: the original's 35%-per-block
 * coin flip produces a scattered, non-contiguous subset of
 * {@code blocksToLaunch} — reasonable for cheap individual entities, but it
 * actively works against {@link FragmentSplitter} finding coherent chunks.
 * This mixin skips that per-block coin flip and instead lets
 * {@code FragmentSplitter} find the real connected pieces, keeping only
 * those at or above {@link #MIN_FRAGMENT_SIZE} as ships; anything smaller
 * falls through to plain destruction, same as before.
 */
@Mixin(value = com.vinlanx.explosionoverhaul.CraterDeformer.class, remap = false)
public abstract class CraterDeformerMixin {

    /** Components smaller than this crumble to plain block destruction instead of becoming a ship. */
    private static final int MIN_FRAGMENT_SIZE = 3;

    /** Outward launch speed for fragment ships, blocks/tick, scaled by explosion power. */
    private static final double LAUNCH_SPEED_BASE = 0.08;
    private static final double LAUNCH_SPEED_PER_POWER = 0.01;

    /** Fracture-burst tuning — scaled by explosion power, same shape as ImpactHandler's crater burst. */
    private static final double VEIN_COUNT_PER_POWER = 0.3;
    private static final int VEIN_COUNT_MIN = 6;
    private static final int VEIN_COUNT_MAX = 20;
    private static final double VEIN_REACH_PER_POWER = 0.25;
    private static final int VEIN_REACH_MIN = 3;
    private static final int VEIN_REACH_MAX = 10;

    /** Border-stress injection — how strongly a destroyed block's intact neighbours feel it. */
    private static final float BORDER_STRESS_PER_POWER = 0.05f;
    private static final float BORDER_STRESS_MAX = 1.5f;

    @Inject(
            method = "finalizeExplosion",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void misanthrope$finalizeExplosion(
            ServerLevel level, Vec3 explosionPos, float power,
            Set<BlockPos> blocksToDestroy, Set<BlockPos> blocksToLaunch,
            CallbackInfo ci) {
        ci.cancel();

        level.sendParticles(ParticleTypes.EXPLOSION,
                explosionPos.x, explosionPos.y, explosionPos.z, 1, 0.0, 0.0, 0.0, 0.0);

        boolean vs2Loaded = ModList.get().isLoaded("valkyrienskies");
        boolean allowFragmentShips = vs2Loaded
                && power <= 20.0f
                && Config.COMMON.enableFallingBlocks.get();

        Set<BlockPos> shippedPositions = new HashSet<>();
        if (allowFragmentShips && !blocksToLaunch.isEmpty()) {
            double launchSpeed = LAUNCH_SPEED_BASE + LAUNCH_SPEED_PER_POWER * power;

            List<Set<BlockPos>> components = FragmentSplitter.splitComponents(blocksToLaunch);
            for (Set<BlockPos> component : components) {
                if (component.size() < MIN_FRAGMENT_SIZE) continue; // falls through to plain destroy below

                Vector3dc com = FragmentSplitter.centerOfMass(level, component);
                Vector3d outward = new Vector3d(com.x(), com.y(), com.z())
                        .sub(explosionPos.x, explosionPos.y, explosionPos.z);
                double len = outward.length();
                if (len < 1e-6) outward.set(0, 1, 0);
                else outward.div(len);

                double fragMass = component.size() * 2400.0 * 9.81e-3;
                Vector3d impulse = new Vector3d(outward).mul(launchSpeed).mul(fragMass * 0.5);

                if (ShipFragmentLauncher.assembleAndLaunch(level, component, impulse)) {
                    shippedPositions.addAll(component);
                }
            }
        }

        for (BlockPos pos : blocksToDestroy) {
            if (shippedPositions.contains(pos)) continue; // VS2 already cleared this position
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }

        injectBorderStress(level, blocksToDestroy, power);
        spawnCrackBurst(level, explosionPos, power);
    }

    /**
     * Feeds {@link DynamicStressTracker} for intact blocks bordering the
     * destroyed region — same unified stress model kinetic/shockwave impacts
     * already funnel through (see that class's doc for why sub-threshold
     * hits should be able to accumulate rather than being judged and
     * forgotten in isolation). An explosion large enough to matter
     * structurally shouldn't only affect exactly the blocks it deleted.
     */
    private static void injectBorderStress(ServerLevel level, Set<BlockPos> blocksToDestroy, float power) {
        if (blocksToDestroy.isEmpty()) return;

        float stressFraction = Math.min(BORDER_STRESS_MAX, BORDER_STRESS_PER_POWER * power);
        if (stressFraction <= 0f) return;
        boolean isImpact = stressFraction >= 1.0f;

        DynamicStressTracker tracker = DynamicStressTracker.get(level);
        Set<BlockPos> visited = new HashSet<>();
        net.minecraft.core.Direction[] dirs = net.minecraft.core.Direction.values();

        for (BlockPos destroyed : blocksToDestroy) {
            for (net.minecraft.core.Direction dir : dirs) {
                BlockPos neighbor = destroyed.relative(dir);
                if (blocksToDestroy.contains(neighbor) || !visited.add(neighbor)) continue;
                if (!level.isLoaded(neighbor)) continue;

                BlockState state = level.getBlockState(neighbor);
                if (state.isAir()) continue;

                BlockPhysicsData data = BlockPhysicsRegistry.get(state);
                if (data.structural == null) continue;

                tracker.addContribution(neighbor, stressFraction, isImpact);
            }
        }
    }

    /**
     * Real, persisted crack veins radiating from the explosion center — same
     * mechanism {@code ImpactHandler}'s crater rework uses, so an explosion
     * crater and a ship-impact crater read consistently rather than EO's
     * cosmetic per-block-type effects being the only visual language for one
     * and our crack system being the language for the other.
     */
    private static void spawnCrackBurst(ServerLevel level, Vec3 explosionPos, float power) {
        int veinCount = (int) Math.max(VEIN_COUNT_MIN, Math.min(VEIN_COUNT_MAX, power * VEIN_COUNT_PER_POWER));
        int veinReach = (int) Math.max(VEIN_REACH_MIN, Math.min(VEIN_REACH_MAX, power * VEIN_REACH_PER_POWER));

        CrackStateMap stateMap = CrackStateMap.get(level);
        Random burstRng = new Random(level.getRandom().nextLong());
        Vector3d center = new Vector3d(explosionPos.x, explosionPos.y, explosionPos.z);

        VeinPropagator.generateImpactBurst(level, stateMap, center, veinCount, veinReach,
                CrackCause.IMPACT, CrackEntry.LEVEL_SEVERE, level.getGameTime(), burstRng);
    }
}
