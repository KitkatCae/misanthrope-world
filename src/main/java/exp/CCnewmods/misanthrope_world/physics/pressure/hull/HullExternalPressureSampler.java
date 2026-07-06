package exp.CCnewmods.misanthrope_world.physics.pressure.hull;

import exp.CCnewmods.misanthrope_world.physics.pressure.FluidPressureSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.fml.ModList;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Samples external pressure (mbar) at world-space hull face positions.
 *
 * <h3>Pressure sources (additive)</h3>
 * <ol>
 *   <li>Atmospheric — DimensionAtmosphereProfile altitude curve</li>
 *   <li>Water column — WaterPressureProvider (real mod or depth stub)</li>
 *   <li>MGE local gas — EnvironmentGrid.getPressureMbar at world face</li>
 * </ol>
 *
 * Results are cached per-ship for EXTERIOR_CACHE_TICKS ticks.
 */
public final class HullExternalPressureSampler {

    private HullExternalPressureSampler() {}

    // ── MGE reflection ────────────────────────────────────────────────────────

    private static volatile boolean mgeResolved = false;
    private static Method mgeGetPressure = null;

    private static void resolveMge() {
        if (mgeResolved) return;
        mgeResolved = true;
        if (!ModList.get().isLoaded("mge")) return;
        try {
            Class<?> grid = Class.forName("exp.CCnewmods.mge.grid.EnvironmentGrid");
            mgeGetPressure = grid.getMethod("getPressureMbar",
                    net.minecraft.world.level.Level.class, BlockPos.class);
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("MVS/Pressure")
                    .warn("[HullExternalPressureSampler] MGE EnvironmentGrid.getPressureMbar not found: {}",
                            e.getMessage());
        }
    }

    // ── Atmosphere reflection ─────────────────────────────────────────────────

    private static volatile boolean atmResolved = false;
    private static Method atmGetProfile = null;
    private static Method atmPressureAtAltitude = null;

    private static void resolveAtmosphere() {
        if (atmResolved) return;
        atmResolved = true;
        if (!ModList.get().isLoaded("mge")) return;
        try {
            Class<?> p = Class.forName("exp.CCnewmods.mge.dimension.DimensionAtmosphereProfile");
            atmGetProfile         = p.getMethod("getForLevel", ServerLevel.class);
            atmPressureAtAltitude = p.getMethod("pressureAtAltitudeMbar", double.class);
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("MVS/Pressure")
                    .warn("[HullExternalPressureSampler] DimensionAtmosphereProfile not found: {}",
                            e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds a map of ship-space BlockPos to external pressure in mbar for
     * every hull face block. Called by PressureDifferentialSolver every
     * EXTERIOR_CACHE_TICKS ticks.
     */
    public static Map<BlockPos, Float> sample(ServerLevel level,
                                               LoadedServerShip ship,
                                               HullPressureState state) {
        resolveMge();
        resolveAtmosphere();

        var transform = ship.getTransform();
        double worldY = transform.getPositionInWorld().y();
        int seaLevel  = level.getSeaLevel();
        double depth  = seaLevel - worldY;

        state.isSubmerged      = depth > 0;
        state.waterDepthBlocks = Math.max(0, depth);

        Map<BlockPos, Float> result = new HashMap<>();

        ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
            var chunk    = level.getChunk(chunkX, chunkZ);
            var sections = chunk.getSections();

            for (int si = 0; si < sections.length; si++) {
                var section = sections[si];
                if (section == null || section.hasOnlyAir()) continue;

                int sectionBaseY = chunk.getSectionYFromSectionIndex(si) << 4;

                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            BlockState bs = section.getBlockState(lx, ly, lz);
                            if (!bs.isSolid()) continue;

                            int sx = (chunkX << 4) + lx;
                            int sy = sectionBaseY + ly + level.getMinBuildHeight();
                            int sz = (chunkZ << 4) + lz;
                            BlockPos shipPos = new BlockPos(sx, sy, sz);

                            // Hull face: any face exposed to non-solid in ship space
                            boolean isHull = false;
                            for (Direction dir : Direction.values()) {
                                BlockState nb = level.getBlockState(shipPos.relative(dir));
                                if (!nb.isSolid()) { isHull = true; break; }
                            }
                            if (!isHull) continue;

                            // Transform ship-space centre to world space
                            var wv = transform.getShipToWorld().transformPosition(
                                    new Vector3d(sx + 0.5, sy + 0.5, sz + 0.5));
                            BlockPos worldPos = BlockPos.containing(wv.x, wv.y, wv.z);

                            float ext = computeExternalPressure(level, worldPos, wv.y, seaLevel);
                            result.put(shipPos, ext);
                        }
                    }
                }
            }
        });

        return result;
    }

    // -------------------------------------------------------------------------
    // External pressure at a single world position
    // -------------------------------------------------------------------------

    static float computeExternalPressure(ServerLevel level, BlockPos worldPos,
                                          double worldY, int seaLevel) {
        float atm = atmosphericPressure(level, worldY);

        // Fluid pressure — FluidPressureSampler does a column walk and reads
        // density from BlockPhysicsData, so lava, water, and modded fluids are
        // all handled correctly without a separate density multiplier here.
        FluidState fluid = level.getBlockState(worldPos).getFluidState();
        float water = 0f;
        if (!fluid.isEmpty()) {
            water = FluidPressureSampler.getFluidColumnPressureMbar(level, worldPos);
        }

        // MGE local gas replaces atmospheric if elevated
        float mgeLocal = mgeLocalPressure(level, worldPos);
        float gasComponent = Math.max(atm, mgeLocal);

        return gasComponent + water;
    }

    // ── Atmosphere ────────────────────────────────────────────────────────────

    private static float atmosphericPressure(ServerLevel level, double worldY) {
        if (atmGetProfile != null && atmPressureAtAltitude != null) {
            try {
                Object profile = atmGetProfile.invoke(null, level);
                return ((Number) atmPressureAtAltitude.invoke(profile, worldY)).floatValue();
            } catch (Exception ignored) {}
        }
        // Fallback: exponential decay, scale height 128 blocks
        double alt = worldY - level.getSeaLevel();
        return (float)(1013.25 * Math.exp(-alt / 128.0));
    }

    // ── MGE local gas ─────────────────────────────────────────────────────────

    private static float mgeLocalPressure(ServerLevel level, BlockPos worldPos) {
        if (mgeGetPressure == null) return 0f;
        try {
            return ((Number) mgeGetPressure.invoke(null, level, worldPos)).floatValue();
        } catch (Exception ignored) { return 0f; }
    }
}
