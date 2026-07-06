package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.pressure.network.WorldPressureNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * World-space breach consequence handler.
 *
 * <p>When {@link BlockPressureEvaluator} reports {@code BREACH} for a
 * world-space boundary block, this callback:
 * <ol>
 *   <li>Destroys the block (using {@link BlockPhysicsData.PressureBreachMode}
 *       to decide drop behaviour).</li>
 *   <li>Vents interior gas via MGE {@code EnvironmentGrid.enqueueWithNeighbours}
 *       at the breach point, and optionally adds a gas burst via
 *       {@code EnvironmentGrid.addGas} if the interior was pressurised above
 *       ambient.</li>
 *   <li>Injects shockwave stress into neighbouring blocks via
 *       {@code MisWorldBridge.injectShockwaveStress} so the crack system
 *       propagates outward from the breach.</li>
 *   <li>Sends a breach network packet to clients for visual/audio effects.</li>
 * </ol>
 */
public final class WorldBreachCallback implements IBreachCallback {

    /** Singleton — no instance state needed. */
    public static final WorldBreachCallback INSTANCE = new WorldBreachCallback();

    private WorldBreachCallback() {}

    // ── MisWorldBridge reflection ─────────────────────────────────────────────

    private static volatile boolean bridgeResolved = false;
    private static Method bridgeInjectShockwave = null;
    // MisWorldBridge.injectShockwaveStress(ServerLevel, BlockPos, float)

    private static void resolveBridge() {
        if (bridgeResolved) return;
        bridgeResolved = true;
        try {
            Class<?> bridge = Class.forName("exp.CCnewmods.mge.compat.MisWorldBridge");
            bridgeInjectShockwave = bridge.getMethod("injectShockwaveStress",
                    ServerLevel.class, BlockPos.class, float.class);
        } catch (Exception e) {
            // MGE not loaded — stress injection silently skipped
        }
    }

    // ── IBreachCallback ───────────────────────────────────────────────────────

    @Override
    public void onBreach(
            ServerLevel level,
            BlockPos pos,
            BlockPhysicsData.PressureData pd,
            PressureVolumeState volumeState,
            float deltaMbar) {

        resolveBridge();

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return; // already destroyed

        // ── 1. Destroy block ──────────────────────────────────────────────────
        switch (pd.breachMode()) {
            case SHATTER, CRUMBLE, TEAR -> {
                // Drop items (SHATTER/CRUMBLE/TEAR = block destroyed, items drop)
                Block.dropResources(state, level, pos, null, null, ItemStack.EMPTY);
                level.removeBlock(pos, false);
            }
            case VENT -> {
                // Vent — block destroyed, opening created, no item drop
                level.removeBlock(pos, false);
            }
            case IMPLODE -> {
                // Inward collapse — no drop, just vanish
                level.removeBlock(pos, false);
            }
        }

        // ── 2. Remove block state from volume ─────────────────────────────────
        volumeState.blockStates.remove(pos);

        // ── 3. Notify MGE: new opening triggers gas diffusion re-evaluation ───
        notifyMgeVent(level, pos, volumeState, deltaMbar, pd.breachMode());

        // ── 4. Inject shockwave stress into all 6 neighbours ─────────────────
        float stressAmount = Math.abs(deltaMbar) * 0.05f; // scale to crack units
        injectShockwaveStress(level, pos, stressAmount);

        // ── 5. Structural integrity tracking ─────────────────────────────────
        boolean catastrophic = volumeState.registerBreach();
        if (catastrophic) {
            // Future: trigger room decompression / cascade failure.
            // For now log it — full cascade is deferred until
            // WorldSpacePressureHandler has room-volume tracking.
        }

        // ── 6. Send breach packet to clients ─────────────────────────────────
        WorldPressureNetwork.sendBreachPacket(level, pos, pd.breachMode(), deltaMbar);
    }

    // ── MGE vent helpers ──────────────────────────────────────────────────────

    private static void notifyMgeVent(
            ServerLevel level,
            BlockPos pos,
            PressureVolumeState vol,
            float deltaMbar,
            BlockPhysicsData.PressureBreachMode mode) {

        Method enqueue = WorldSpacePressureHandler.getMgeEnqueueNeighbours();
        Method addGas  = WorldSpacePressureHandler.getMgeAddGas();

        if (enqueue != null) {
            try {
                enqueue.invoke(null, level, pos);
            } catch (Exception ignored) {}
        }

        // Gas burst for VENT and TEAR: rush of interior gas through the opening.
        if (addGas != null
                && (mode == BlockPhysicsData.PressureBreachMode.VENT
                    || mode == BlockPhysicsData.PressureBreachMode.TEAR)) {
            float internalPressure = vol.cachedInternalPressureMbar;
            float burstAmount = Math.max(0f, (internalPressure - 1013.25f) / 100f);
            if (burstAmount > 0.01f) {
                try {
                    // addGas(Level, BlockPos, Gas, float) — pass null Gas to let
                    // MGE inject ambient composition (safest cross-version approach)
                    addGas.invoke(null, level, pos, null, burstAmount);
                } catch (Exception ignored) {
                    // addGas signature mismatch — diffusion handles the rest
                }
            }
        }
    }

    // ── Shockwave stress injection ────────────────────────────────────────────

    private static void injectShockwaveStress(ServerLevel level, BlockPos breachPos, float amount) {
        if (bridgeInjectShockwave == null) return;
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = breachPos.relative(dir);
            try {
                bridgeInjectShockwave.invoke(null, level, neighbour, amount);
            } catch (Exception ignored) {}
        }
    }
}
