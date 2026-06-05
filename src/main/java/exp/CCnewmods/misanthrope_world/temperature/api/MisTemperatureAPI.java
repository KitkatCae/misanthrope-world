package exp.CCnewmods.misanthrope_world.temperature.api;

import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.common.capability.handler.EntityTempManager;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import com.Tribulla.thermodynamica.api.HeatAPI;
import exp.CCnewmods.misanthrope_world.temperature.capability.ItemTemperatureCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import exp.CCnewmods.mge.grid.EnvironmentGrid;
import net.minecraftforge.common.util.LazyOptional;

/**
 * Unified temperature API for all Misanthrope Core systems.
 * <p>
 * ── Principle ─────────────────────────────────────────────────────────────────
 * Nothing outside the temperature package ever calls ColdSweat or Thermodynamica
 * directly. All temperature reads and writes go through this class. If either mod
 * is absent, the relevant methods return safe fallback values or no-op quietly.
 * <p>
 * This means pottery cracking, forging, crucible heat, entity heat emission,
 * and item temperature decay all share one consistent temperature model with one
 * consistent unit (Celsius).
 * <p>
 * ── Temperature sources (priority order) ─────────────────────────────────────
 * <p>
 * For BLOCK POSITIONS (getAmbientCelsius):
 * 1. Thermodynamica's BFS-simulated temperature (getSimulatedCelsius)
 * → most accurate, accounts for heat propagation from sources
 * 2. ColdSweat's world temperature (WorldHelper.getRoughTemperatureAt)
 * → accounts for biome, elevation, time, weather
 * 3. Biome base temperature fallback (vanilla biome.getBaseTemperature × 40)
 * → rough but always available
 * <p>
 * For ENTITIES (getEntityCoreCelsius):
 * 1. ColdSweat's CORE trait (Temperature.get(entity, Trait.CORE))
 * → the entity's internal body core temperature
 * 2. Our own ItemTemperatureCapability on the entity (if not ColdSweat-tracked)
 * 3. Ambient temperature at entity position as fallback
 * <p>
 * For ITEMS (getItemCelsius):
 * 1. Our ItemTemperatureCapability on the ItemStack
 * 2. Ambient temperature at last-known position if capability not yet set
 * <p>
 * ── Celsius range reference ───────────────────────────────────────────────────
 * ~-30°C  Deep frozen biome (extreme cold biome at altitude)
 * 0°C   Freezing point of water
 * 20°C   Room temperature / comfortable biome ambient
 * 37°C   Human body core temperature
 * 60°C   Hot spring / lava-adjacent biome
 * 100°C   Boiling water
 * 200°C   Campfire nearby
 * 650°C   Clay firing point (terracotta)
 * 900°C   Ceramic clay firing point
 * 1200°C   Fireclay firing point
 * 1300°C   Iron melting point
 * 1600°C   Void alloy crucible operating temperature
 */
public class MisTemperatureAPI {

    // ── Thresholds ────────────────────────────────────────────────────────────

    /**
     * Below this, clay starts tinting toward dry brown.
     */
    public static final double CLAY_DRYING_CELSIUS = 30.0;
    /**
     * Above this for sufficient ticks, terracotta clay fires.
     */
    public static final double CLAY_FIRING_CELSIUS = 650.0;
    /**
     * Above this for sufficient ticks, ceramic clay fires.
     */
    public static final double CERAMIC_FIRING_CELSIUS = 900.0;
    /**
     * Above this for sufficient ticks, fireclay fires.
     */
    public static final double FIRECLAY_FIRING_CELSIUS = 1200.0;
    /**
     * Above this, unfired clay cracks unless glaze-protected.
     */
    public static final double CLAY_CRACKING_CELSIUS = 1400.0;

    /**
     * Ticks at firing temperature required to fire clay items in ambient heat
     * (not in a kiln). Kilns multiply this by 0.05 — effectively 20x faster.
     */
    public static final int AMBIENT_FIRING_TICKS = 24000; // 20 minutes
    public static final int KILN_FIRING_TICKS = 1200;  // 1 minute

    // ── Capability / mod availability ─────────────────────────────────────────

    private static Boolean thermodynamicaLoaded = null;
    private static Boolean coldSweatLoaded = null;

    public static boolean isThermodynamicaLoaded() {
        if (thermodynamicaLoaded == null) {
            try {
                Class.forName("com.Tribulla.thermodynamica.api.HeatAPI");
                thermodynamicaLoaded = true;
            } catch (ClassNotFoundException e) {
                thermodynamicaLoaded = false;
            }
        }
        return thermodynamicaLoaded;
    }

    public static boolean isColdSweatLoaded() {
        if (coldSweatLoaded == null) {
            try {
                Class.forName("com.momosoftworks.coldsweat.api.util.Temperature");
                coldSweatLoaded = true;
            } catch (ClassNotFoundException e) {
                coldSweatLoaded = false;
            }
        }
        return coldSweatLoaded;
    }

    // ── Block position temperature ────────────────────────────────────────────

    /**
     * Get the ambient temperature at a block position in Celsius.
     * <p>
     * Priority:
     * 1. Thermodynamica simulated temperature (BFS heat propagation)
     * 2. ColdSweat world temperature (biome/elevation/weather)
     * 3. Vanilla biome base temperature × 40
     */
    public static double getAmbientCelsius(Level level, BlockPos pos) {
        // 0. MGE EnvironmentGrid — ThermalField writes explicit temperatures here.
        try {
            float gridTemp = EnvironmentGrid.getTemperature(level, pos);
            if (!Float.isNaN(gridTemp)) return gridTemp;
        } catch (Exception ignored) {
        }

        // 1. Thermodynamica
        if (isThermodynamicaLoaded()) {
            try {
                java.util.OptionalDouble simOpt = HeatAPI.get().getSimulatedCelsius(level, pos);
                if (simOpt.isPresent()) {
                    return simOpt.getAsDouble();
                }
            } catch (Exception ignored) {
            }
        }

        // 2. ColdSweat world temperature
        if (isColdSweatLoaded()) {
            try {
                // WorldHelper.getRoughTemperatureAt returns °C (our config uses Celsius)
                double csTemp = WorldHelper.getRoughTemperatureAt(level, pos);
                if (!Double.isNaN(csTemp)) return csTemp;
            } catch (Exception ignored) {
            }
        }

        // 3. Vanilla biome base temperature (range 0.0-2.0) × 40 ≈ rough Celsius
        var biomeHolder = level.getBiome(pos);
        float biomeBase = biomeHolder.value().getBaseTemperature();
        return (biomeBase - 0.5) * 40.0; // 0.5 base = ~20°C, 0.0 = ~-20°C, 2.0 = ~60°C
    }

    /**
     * Get the Thermodynamica visual temperature at a position.
     * Returns NaN if Thermodynamica is unavailable or position is untracked.
     * Used for rendering heat shimmer effects.
     */
    public static double getVisualCelsius(Level level, BlockPos pos) {
        if (!isThermodynamicaLoaded()) return Double.NaN;
        try {
            return HeatAPI.get().getVisualCelsius(level, pos);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ── Entity temperature ────────────────────────────────────────────────────

    /**
     * Get an entity's core temperature in Celsius.
     * <p>
     * CORE trait in ColdSweat represents the entity's internal temperature —
     * separate from the BODY trait (body surface) and WORLD trait (ambient).
     * Blazes, luxtructosaurus, etc. have high CORE values registered via
     * ColdSweat's EntityTempData codec.
     * <p>
     * Falls back to ambient at entity position if ColdSweat is unavailable.
     */
    public static double getEntityCoreCelsius(LivingEntity entity) {
        if (isColdSweatLoaded()) {
            try {
                return Temperature.get(entity, Temperature.Trait.CORE);
            } catch (Exception ignored) {
            }
        }
        // Fallback: ambient at entity position
        return getAmbientCelsius(entity.level(), entity.blockPosition());
    }

    /**
     * Get an entity's body (surface) temperature in Celsius.
     * This is what other entities feel when near this entity.
     */
    public static double getEntityBodyCelsius(LivingEntity entity) {
        if (isColdSweatLoaded()) {
            try {
                return Temperature.get(entity, Temperature.Trait.BODY);
            } catch (Exception ignored) {
            }
        }
        return getAmbientCelsius(entity.level(), entity.blockPosition());
    }

    /**
     * Get the world temperature ColdSweat perceives at an entity's position.
     * This is the biome/block/weather-modified ambient temperature the entity
     * is experiencing, before insulation or body modifiers.
     */
    public static double getEntityWorldCelsius(LivingEntity entity) {
        if (isColdSweatLoaded()) {
            try {
                return Temperature.get(entity, Temperature.Trait.WORLD);
            } catch (Exception ignored) {
            }
        }
        return getAmbientCelsius(entity.level(), entity.blockPosition());
    }

    /**
     * Set an entity's core temperature in Celsius.
     * Used by the entity heat emitter to push heat into ColdSweat's cap.
     */
    public static void setEntityCoreCelsius(LivingEntity entity, double celsius) {
        if (isColdSweatLoaded()) {
            try {
                Temperature.set(entity, Temperature.Trait.CORE, celsius);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Whether this entity type is tracked by ColdSweat's temperature system.
     * Non-tracked entities (most mobs by default) won't have a temperature cap.
     */
    public static boolean isEntityTemperatureTracked(LivingEntity entity) {
        if (!isColdSweatLoaded()) return false;
        try {
            return EntityTempManager.isTemperatureEnabled(entity);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the temperature registered for this entity type in ColdSweat's
     * EntityTempData registry. Returns NaN if no data is registered.
     * This is the raw "heat emission" temperature — how hot this entity's
     * core is defined to be, not the current live cap value.
     */
    public static double getRegisteredEntityEmissionCelsius(LivingEntity entity) {
        if (!isColdSweatLoaded()) return Double.NaN;
        try {
            var entityTemps = com.momosoftworks.coldsweat.config.ConfigSettings
                    .ENTITY_TEMPERATURES.get();
            var entityType = entity.getType();
            var tempDataCollection = entityTemps.get(entityType);
            if (tempDataCollection == null || tempDataCollection.isEmpty())
                return Double.NaN;

            // Take the highest temperature effect defined for this entity type.
            // Use reflection — getTemperatureEffect's signature varies across ColdSweat versions.
            double maxTemp = Double.NaN;
            for (var data : tempDataCollection) {
                try {
                    double temp = Double.NaN;
                    for (var method : data.getClass().getMethods()) {
                        if (method.getName().equals("getTemperatureEffect")) {
                            var params = method.getParameterTypes();
                            if (params.length == 1) {
                                temp = ((Number) method.invoke(data, entity)).doubleValue();
                            } else if (params.length == 2) {
                                // try (entity, distance) and (distance, entity)
                                if (params[0] == double.class || params[0] == Double.class) {
                                    temp = ((Number) method.invoke(data, 0.0, entity)).doubleValue();
                                } else {
                                    temp = ((Number) method.invoke(data, entity, 0.0)).doubleValue();
                                }
                            }
                            break;
                        }
                    }
                    if (!Double.isNaN(temp) && (Double.isNaN(maxTemp) || temp > maxTemp))
                        maxTemp = temp;
                } catch (Exception ignored) {
                }
            }
            return maxTemp;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ── Item temperature ──────────────────────────────────────────────────────

    /**
     * Get the current temperature of an ItemStack in Celsius.
     * Returns the ambient temperature if no capability is present.
     *
     * @param ambientCelsius the ambient temperature to return as fallback.
     *                       Pass NaN to get NaN on fallback instead.
     */
    public static double getItemCelsius(ItemStack stack, double ambientCelsius) {
        if (stack.isEmpty()) return ambientCelsius;
        LazyOptional<ItemTemperatureCapability> cap =
                stack.getCapability(ItemTemperatureCapability.CAPABILITY);
        return cap.map(ItemTemperatureCapability::getCelsius)
                .orElse(ambientCelsius);
    }

    /**
     * Set the temperature of an ItemStack in Celsius.
     * If the item doesn't have the capability, this is a no-op.
     */
    public static void setItemCelsius(ItemStack stack, double celsius) {
        if (stack.isEmpty()) return;
        stack.getCapability(ItemTemperatureCapability.CAPABILITY)
                .ifPresent(cap -> cap.setCelsius(celsius));
    }

    /**
     * Get how many ticks this item has spent at or above its firing temperature.
     * Used for ambient firing progression.
     */
    public static int getItemTicksAtFiringTemp(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        return stack.getCapability(ItemTemperatureCapability.CAPABILITY)
                .map(ItemTemperatureCapability::getTicksAtFiringTemp)
                .orElse(0);
    }

    /**
     * Increment the item's firing ticks counter.
     */
    public static void incrementItemFiringTicks(ItemStack stack) {
        if (stack.isEmpty()) return;
        stack.getCapability(ItemTemperatureCapability.CAPABILITY)
                .ifPresent(ItemTemperatureCapability::incrementFiringTicks);
    }

    /**
     * Whether this item is currently above the ambient firing temperature
     * for its clay type. Checks the item's own temperature against the
     * thresholds defined in this API.
     */
    public static boolean isItemAtFiringTemperature(ItemStack stack, double ambientCelsius) {
        double itemTemp = getItemCelsius(stack, ambientCelsius);
        // Determine firing threshold by item tag
        if (isFiredClay(stack, "fireclay")) return itemTemp >= FIRECLAY_FIRING_CELSIUS;
        if (isFiredClay(stack, "ceramic")) return itemTemp >= CERAMIC_FIRING_CELSIUS;
        if (isFiredClay(stack, "clay")) return itemTemp >= CLAY_FIRING_CELSIUS;
        return false;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Calculate the heat emission radius for an entity based on its
     * model volume and core temperature excess above ambient.
     * <p>
     * Formula:
     * radius = cbrt(width * width * height) * sqrt(excess / 100)
     * clamped to [0, 128] blocks
     * <p>
     * cbrt(volume) gives the characteristic length scale of the entity —
     * the side length of an equivalent cube. sqrt(excess/100) scales the
     * temperature contribution sublinearly so extremely hot entities don't
     * produce runaway radii. Together they give physically grounded scaling
     * across entities ranging from a blaze to the luxtructosaurus.
     * <p>
     * Examples (at ~20°C cave ambient):
     * Blaze (0.6×0.6×1.8, ~600°C core):
     * cbrt(0.648) × sqrt(5.8) ≈ 0.865 × 2.41 ≈ 2.1 blocks
     * Small direct injection, BFS propagates heat outward from there.
     * <p>
     * Wither (0.9×0.9×4, ~800°C core):
     * cbrt(3.24) × sqrt(7.8) ≈ 1.48 × 2.79 ≈ 4.1 blocks
     * <p>
     * Ender Dragon (16×16×8, ~300°C core):
     * cbrt(2048) × sqrt(2.8) ≈ 12.7 × 1.67 ≈ 21.2 blocks
     * <p>
     * Luxtructosaurus (15×15×15, 860°C core):
     * cbrt(3375) × sqrt(8.4) ≈ 15.0 × 2.90 ≈ 43.5 blocks
     * Heats an entire cave chamber — appropriate for a creature this size.
     *
     * @param entity         the entity emitting heat
     * @param coreCelsius    the entity's core temperature
     * @param ambientCelsius ambient temperature at the entity's position
     * @return emission radius in blocks, [0, 128]
     */
    public static double computeEntityEmissionRadius(LivingEntity entity,
                                                     double coreCelsius,
                                                     double ambientCelsius) {
        float w = entity.getBbWidth();
        float h = entity.getBbHeight();
        double volume = (double) w * w * h;
        double cbrtVolume = Math.cbrt(volume);
        double excess = Math.max(0.0, coreCelsius - ambientCelsius);
        double radius = cbrtVolume * Math.sqrt(excess / 100.0);
        return Math.max(0.0, Math.min(128.0, radius));
    }

    /**
     * Linearly interpolate item temperature toward a target at a rate
     * determined by the material's thermal mass.
     * <p>
     * thermalMass: higher = slower temperature change
     * stone/clay: ~50
     * metal:      ~20
     * water:      ~100
     */
    public static double lerpTemperature(double current, double target,
                                         double thermalMass, int elapsedTicks) {
        double rate = 1.0 / thermalMass;
        double factor = 1.0 - Math.pow(1.0 - rate, elapsedTicks);
        return current + (target - current) * factor;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static boolean isFiredClay(ItemStack stack, String clayTypeKeyword) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        return path.contains("unfired") && path.contains(clayTypeKeyword);
    }
}
