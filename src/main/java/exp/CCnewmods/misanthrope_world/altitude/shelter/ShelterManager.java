package exp.CCnewmods.misanthrope_world.altitude.shelter;

import exp.CCnewmods.misanthrope_world.altitude.compat.MgeAtmosphereReader;
import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Predicate;

/**
 * Determines how well-sheltered a player is, then reduces that shelter
 * based on local wind speed from MGE/ProjectAtmosphere.
 * <p>
 * <b>Wind physics model:</b> High wind reduces the effective shelter multiplier.
 * Even a fully enclosed room leaks wind-chill through gaps and walls at high
 * wind speeds. The wind attenuation curve is:
 * <pre>
 *   windAttenuation = 1.0 - clamp(windSpeed / WIND_FULL_CANCEL_MPS, 0, 1)^0.5
 * </pre>
 * At 0 m/s: no attenuation (shelter is fully effective).
 * At {@value WIND_FULL_CANCEL_MPS} m/s (hurricane): shelter cancelled entirely.
 * The sqrt curve means wind has diminishing returns at moderate speeds —
 * a stiff breeze at 10 m/s only cancels ~37% of shelter, matching the intuition
 * that a stone hut protects you well in all but extreme conditions.
 */
public final class ShelterManager {

    public static final ShelterManager INSTANCE = new ShelterManager();
    private ShelterManager() {}

    /**
     * Wind speed in m/s at which shelter is completely cancelled.
     * ProjectAtmosphere's highest hurricane-force winds peak around 70 m/s.
     */
    private static final double WIND_FULL_CANCEL_MPS = 70.0;

    private static final double[][] FLOOD_DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the raw shelter enclosure fraction (0–1) before wind adjustment.
     * 0 = fully exposed, 1 = completely enclosed.
     */
    public double shelterEnclosure(ServerPlayer player, AltitudeBand band) {
        if (!band.enableShelterCheck()) return 0.0;
        return enclosure(player, band.shelterCheckRadius());
    }

    /**
     * Returns the wind speed at the player's position in m/s.
     * Reads from MGE's WindProviderManager (which delegates to PA if loaded).
     * Returns 0.0 when MGE is absent.
     */
    public double windSpeedMps(ServerPlayer player) {
        return MgeAtmosphereReader.getInstance().getWindSpeedMps(
                player.level(), player.blockPosition());
    }

    /**
     * Computes the shelter multiplier: how much of the altitude modifier the
     * enclosure cancels, after accounting for wind blowing through.
     *
     * @param band       the active altitude band
     * @param enclosure  raw flood-fill enclosure fraction (0–1)
     * @param windMps    current wind speed in m/s
     */
    public double shelterMultiplier(AltitudeBand band, double enclosure, double windMps) {
        if (!band.enableShelterCheck() || band.shelterReduction() <= 0.0) return 0.0;

        // Wind attenuates shelter: high wind = less shelter effectiveness
        double scaledWind = windMps * MisWorldConfig.altitudeWindSensitivity();
        double windAttenuation = 1.0 - Math.min(1.0, Math.sqrt(scaledWind / WIND_FULL_CANCEL_MPS));

        // Effective enclosure = geometric enclosure × wind attenuation
        double effectiveEnclosure = enclosure * windAttenuation;

        // Scale by band's configured shelter reduction cap
        return Math.min(effectiveEnclosure * band.shelterReduction(), band.shelterReduction());
    }

    // ── Enclosure computation (directional flood-fill) ────────────────────────

    private double enclosure(ServerPlayer player, int radius) {
        var level = player.level();
        BlockPos origin = player.blockPosition().above(); // head height

        Predicate<BlockPos> inBounds = pos ->
                Math.abs(pos.getX() - origin.getX()) <= radius &&
                Math.abs(pos.getY() - origin.getY()) <= radius &&
                Math.abs(pos.getZ() - origin.getZ()) <= radius;

        int closedDirections = 0;
        for (double[] dir : FLOOD_DIRECTIONS) {
            if (hasClosure(level, origin, radius, dir[0], dir[1], dir[2], inBounds))
                closedDirections++;
        }

        return (double) closedDirections / FLOOD_DIRECTIONS.length;
    }

    private boolean hasClosure(BlockGetter level, BlockPos origin, int radius,
                                double dx, double dy, double dz,
                                Predicate<BlockPos> inBounds) {
        for (int dist = 1; dist <= radius; dist++) {
            BlockPos check = origin.offset((int)(dx*dist), (int)(dy*dist), (int)(dz*dist));
            if (!inBounds.test(check)) return false;
            if (isClosureBlock(level, check)) return true;
        }
        return false;
    }

    private boolean isClosureBlock(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;

        var block = state.getBlock();

        // Closed doors / trapdoors count as walls
        if (block instanceof DoorBlock) {
            try { return !state.getValue(BlockStateProperties.OPEN); }
            catch (IllegalArgumentException ignored) {}
        }
        if (block instanceof TrapDoorBlock) {
            try { return !state.getValue(BlockStateProperties.OPEN); }
            catch (IllegalArgumentException ignored) {}
        }

        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty();
    }
}
