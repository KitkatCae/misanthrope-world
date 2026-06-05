package exp.CCnewmods.misanthrope_world.temperature.handler;

import com.Tribulla.thermodynamica.api.HeatAPI;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Emits entity core temperatures into Thermodynamica's heat simulation grid.
 * <p>
 * ── When entities heat their surroundings ─────────────────────────────────────
 * Any living entity whose ColdSweat CORE temperature significantly exceeds
 * the ambient temperature at their position injects heat into Thermodynamica's
 * block grid. Thermodynamica's BFS simulation then propagates that heat outward
 * through nearby blocks, producing realistic heat falloff.
 * <p>
 * This runs every 40 ticks per entity (every 2 seconds) to avoid performance
 * overhead while still producing smooth environmental heating effects.
 * <p>
 * ── Radius computation ────────────────────────────────────────────────────────
 * See MisTemperatureAPI.computeEntityEmissionRadius() for the full formula.
 * <p>
 * Key principle: larger entities and hotter entities heat a larger area.
 * characteristicLength = cbrt(width × width × height)
 * excess = max(0, coreCelsius - ambientCelsius)
 * radius = clamp(characteristicLength × excess / 100, 1.0, 16.0)
 * <p>
 * The temperature injected at each block position uses distance-based
 * diminishing returns:
 * effectiveCelsius = ambientCelsius + excess × (1 - dist/radius)²
 * <p>
 * ── What entities qualify ────────────────────────────────────────────────────
 * Any entity registered in ColdSweat's EntityTempData with a positive
 * temperature effect qualifies — blazes, magma cubes, fire elementals,
 * luxtructosaurus, any custom mob a pack author defines via ColdSweat data.
 * No hardcoded list needed.
 * <p>
 * Cold entities (core < ambient) do NOT inject into Thermodynamica — cold
 * entities affect other entities through ColdSweat's EntitiesTempModifier,
 * which already handles entity→entity cold transfer correctly.
 * <p>
 * ── Dropped item entities ─────────────────────────────────────────────────────
 * ItemEntity temperatures are handled by ItemTemperatureTickHandler, not here.
 * This handler is only for LivingEntity subclasses.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class EntityHeatEmitter {

    /**
     * Only emit heat every N ticks per entity.
     */
    private static final int EMIT_INTERVAL_TICKS = 40;

    /**
     * Minimum excess above ambient to bother emitting (performance filter).
     */
    private static final double MIN_EXCESS_TO_EMIT = 15.0;

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();

        // Server side only
        if (level.isClientSide()) return;

        // Throttle to every EMIT_INTERVAL_TICKS ticks
        if (entity.tickCount % EMIT_INTERVAL_TICKS != 0) return;

        // Only process entities ColdSweat tracks with temperature data
        if (!MisTemperatureAPI.isEntityTemperatureTracked(entity)) return;

        // Get the entity's registered emission temperature
        // This is the peak temperature this entity type emits, from EntityTempData
        double emissionCelsius = MisTemperatureAPI.getRegisteredEntityEmissionCelsius(entity);
        if (Double.isNaN(emissionCelsius)) return;

        // Compare to ambient at entity's position
        BlockPos entityPos = entity.blockPosition();
        double ambientCelsius = MisTemperatureAPI.getAmbientCelsius(level, entityPos);
        double excess = emissionCelsius - ambientCelsius;

        // Skip cold entities and entities barely above ambient
        if (excess < MIN_EXCESS_TO_EMIT) return;

        // Skip if Thermodynamica isn't available
        if (!MisTemperatureAPI.isThermodynamicaLoaded()) return;

        // Compute emission radius based on entity size + temperature excess
        double radius = MisTemperatureAPI.computeEntityEmissionRadius(
                entity, emissionCelsius, ambientCelsius);

        if (radius < 0.5) return; // too small to bother

        // Inject heat into Thermodynamica at positions within the emission radius.
        // For very large entities (luxtructosaurus at ~58 blocks direct injection,
        // capped at 128), this loop covers a significant volume — but it only runs
        // every 40 ticks and Thermodynamica handles propagation from there.
        emitHeatInRadius(level, entityPos, ambientCelsius, emissionCelsius, radius);
    }

    /**
     * Register heat sources in Thermodynamica's grid within the emission radius.
     * Uses squared distance for efficiency, distance-squared falloff for realism.
     */
    private static void emitHeatInRadius(Level level, BlockPos centre,
                                         double ambientCelsius, double coreCelsius,
                                         double radius) {
        HeatAPI api = HeatAPI.get();
        int iRadius = (int) Math.ceil(radius);
        // Vertical range scales with horizontal radius — a 58-block luxtructosaurus
        // heats the full height of its cave, not just a flat disc.
        int vRadius = Math.max(2, iRadius / 3);
        double radiusSq = radius * radius;
        double excess = coreCelsius - ambientCelsius;

        for (int dx = -iRadius; dx <= iRadius; dx++) {
            for (int dy = -vRadius; dy <= vRadius; dy++) {
                for (int dz = -iRadius; dz <= iRadius; dz++) {
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > radiusSq) continue;

                    BlockPos pos = centre.offset(dx, dy, dz);

                    // Distance falloff: (1 - dist/radius)² for smooth quadratic decay
                    double dist = Math.sqrt(distSq);
                    double falloff = 1.0 - (dist / radius);
                    falloff = falloff * falloff;

                    double injectedCelsius = ambientCelsius + excess * falloff;

                    try {
                        api.registerBlockCelsius(
                                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(
                                        level.getBlockState(pos).getBlock()),
                                injectedCelsius
                        );
                    } catch (Exception ignored) {
                        // Thermodynamica may reject if block is untrackable
                    }
                }
            }
        }
    }
}
