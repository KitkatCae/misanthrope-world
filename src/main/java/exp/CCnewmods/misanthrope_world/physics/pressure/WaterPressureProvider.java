package exp.CCnewmods.misanthrope_world.physics.pressure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Provider for water pressure at a world-space position.
 *
 * <h3>Purpose</h3>
 * The pressure differential system needs external water pressure at every hull
 * block face exposed to water. This interface decouples MVS from the future
 * water pressure mod — that mod registers its implementation here at init time,
 * and MVS queries it without knowing the implementation details.
 *
 * <h3>Default stub</h3>
 * Until the water mod is loaded, the default implementation returns a simple
 * depth-based approximation:
 * <pre>
 *   P_water(depth) = SEA_LEVEL_MBAR + depth_blocks × MBAR_PER_BLOCK
 * </pre>
 * where {@code depth} is measured from the world's sea level down
 * ({@code depth > 0} = below sea level). Above sea level the stub returns 0
 * (no water pressure).
 *
 * Real seawater: ~98.07 mbar per metre. One Minecraft block = ~1 metre.
 * At depth 10 blocks: ~981 mbar above sea-level pressure — enough to begin
 * stressing thin steel hulls ({@code ultimate ~2000 mbar}).
 *
 * <h3>Registration</h3>
 * The water mod calls {@link #register(WaterPressureProvider)} during its
 * {@code FMLCommonSetupEvent}. Only one provider may be registered at a time;
 * subsequent calls replace the previous provider and log a warning.
 *
 * <h3>Thread safety</h3>
 * Registration is one-time at mod init; reads are on the server game thread.
 * The {@code volatile} field provides a safe publication guarantee.
 */
@FunctionalInterface
public interface WaterPressureProvider {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** mbar pressure per block of depth below sea level (approximates seawater). */
    float MBAR_PER_BLOCK_DEPTH = 98.07f;

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Returns the water pressure in mbar at the given world-space position.
     *
     * @param level      the server level
     * @param worldPos   world-space block position to query
     * @param depthMetres depth below the water surface in metres (blocks).
     *                   Positive = submerged. 0 = at surface. Negative = above.
     *                   Pre-computed by the hull solver from the world's sea level
     *                   and current water height at this position.
     * @return water pressure in mbar. 0 = no water pressure (above surface or
     *         in air). Must be non-negative.
     */
    float getPressureMbar(ServerLevel level, BlockPos worldPos, double depthMetres);

    // ── Registry ──────────────────────────────────────────────────────────────

    /**
     * Holds the mutable registry state. Interfaces cannot have non-final static
     * fields, so mutable state lives in this private companion class instead.
     */
    final class Holder {
        private Holder() {}
        static volatile WaterPressureProvider REGISTERED = null;
    }

    /**
     * Registers a custom water pressure provider.
     * Replaces any previously registered provider.
     *
     * <p>Call from {@code FMLCommonSetupEvent} in your water mod.</p>
     *
     * @param provider the provider to register. Non-null.
     */
    static void register(WaterPressureProvider provider) {
        if (provider == null) throw new NullPointerException("WaterPressureProvider must not be null");
        if (Holder.REGISTERED != null) {
            org.apache.logging.log4j.LogManager.getLogger("MisanthropeWorld/Pressure")
                    .warn("[WaterPressureProvider] Replacing existing provider {} with {}",
                            Holder.REGISTERED.getClass().getSimpleName(),
                            provider.getClass().getSimpleName());
        }
        Holder.REGISTERED = provider;
    }

    /**
     * Queries the active provider, falling back to the built-in depth approximation.
     *
     * <p>Called by {@code HullExternalPressureSampler} for each hull face block
     * that is in contact with water.</p>
     *
     * @param level      the server level
     * @param worldPos   world-space position of the hull face block
     * @param depthMetres depth below the water surface at this position
     * @return water pressure in mbar
     */
    static float query(ServerLevel level, BlockPos worldPos, double depthMetres) {
        WaterPressureProvider p = Holder.REGISTERED;
        if (p != null) return p.getPressureMbar(level, worldPos, depthMetres);
        return defaultPressure(depthMetres);
    }

    // ── Default stub ──────────────────────────────────────────────────────────

    /**
     * Simple depth-linear water pressure approximation.
     * Used when no real water mod is loaded.
     *
     * @param depthMetres depth below sea level (positive = submerged)
     * @return pressure in mbar, 0 if not submerged
     */
    static float defaultPressure(double depthMetres) {
        if (depthMetres <= 0) return 0f;
        return (float) (depthMetres * MBAR_PER_BLOCK_DEPTH);
    }

    /**
     * Returns whether a custom water pressure provider has been registered.
     * MVS can use this to show a config hint if no water mod is present.
     */
    static boolean hasCustomProvider() {
        return Holder.REGISTERED != null;
    }
}