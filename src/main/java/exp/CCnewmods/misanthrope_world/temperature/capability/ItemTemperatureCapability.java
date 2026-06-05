package exp.CCnewmods.misanthrope_world.temperature.capability;

import exp.CCnewmods.misanthrope_world.temperature.heatstate.HeatState;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Capability tracking the temperature of a temperature-sensitive ItemStack.
 * <p>
 * ── Attachment ────────────────────────────────────────────────────────────────
 * Attached only to items tagged {@code #misanthrope_core:temperature_sensitive}.
 * All other items return {@code LazyOptional.empty()} with no performance cost.
 * <p>
 * ── Thermal mass ─────────────────────────────────────────────────────────────
 * {@code thermalMass} controls how quickly the item's temperature changes toward
 * the ambient. Higher values = slower change.
 * <p>
 * The temperature lerp formula used by ItemTemperatureTickHandler:
 * <pre>
 *   rate = 1.0 / thermalMass
 *   new  = old + (ambient - old) × rate
 * </pre>
 * <p>
 * ── Heat state ────────────────────────────────────────────────────────────────
 * {@code heatState} is recomputed each tick from raw Celsius and cached here
 * so client rendering (tint mixin, overlay renderer) can read it cheaply
 * without redoing the threshold math.
 * <p>
 * ── Cooking / burning / charring progress ─────────────────────────────────────
 * {@code cookProgressTicks}  — ticks spent above cook threshold (toward cook_result)
 * {@code burnProgressTicks}  — ticks spent above burn threshold (toward burn_result)
 * {@code charProgressTicks}  — ticks spent above char threshold (toward char_result)
 * <p>
 * Progress counters reset when temperature drops below the relevant threshold.
 * Burn progress resets to zero when switching from the cook → burn transition.
 * <p>
 * ── Freeze progress ───────────────────────────────────────────────────────────
 * {@code freezeProgressTicks} — ticks spent below freeze threshold (toward freeze_result)
 * Resets when temperature rises above freeze threshold.
 * <p>
 * ── Firing progression (pottery clay) ────────────────────────────────────────
 * {@code ticksAtFiringTemp} — accumulated ticks at firing temperature for clay items.
 * Counter resets if item falls below firing temperature.
 * <p>
 * ── NBT layout ────────────────────────────────────────────────────────────────
 * {@code "MisTemperature"}     double  — current Celsius
 * {@code "MisThermalMass"}     double  — thermal mass constant
 * {@code "MisHeatState"}       byte    — HeatState ordinal
 * {@code "MisFiringTicks"}     int     — accumulated ticks at firing temperature
 * {@code "MisCookTicks"}       int     — cooking progress ticks
 * {@code "MisBurnTicks"}       int     — burn progress ticks
 * {@code "MisCharTicks"}       int     — char progress ticks
 * {@code "MisFreezeTicks"}     int     — freeze progress ticks
 */
public class ItemTemperatureCapability implements ICapabilitySerializable<CompoundTag> {

    // ── Capability key ────────────────────────────────────────────────────────

    public static final Capability<ItemTemperatureCapability> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {
            });

    public static final ResourceLocation CAPABILITY_ID =
            new ResourceLocation("misanthrope_core", "item_temperature");

    // ── State ─────────────────────────────────────────────────────────────────

    private double celsius;
    private double thermalMass;
    private HeatState heatState;

    // Pottery-specific progress counter (kept from original)
    private int ticksAtFiringTemp;

    // Food/wood heat progression counters
    private int cookProgressTicks;
    private int burnProgressTicks;
    private int charProgressTicks;
    private int freezeProgressTicks;

    // ── Thermal mass defaults ─────────────────────────────────────────────────

    public static final double CLAY_THERMAL_MASS = 50.0;
    public static final double METAL_THERMAL_MASS = 25.0;
    public static final double STONE_THERMAL_MASS = 60.0;
    public static final double FOOD_THERMAL_MASS = 30.0;  // food heats/cools moderately fast
    public static final double WOOD_THERMAL_MASS = 20.0;  // wood heats quickly (low density)
    public static final double ICE_THERMAL_MASS = 80.0;  // ice is slow to melt

    // ── Lazy optional ─────────────────────────────────────────────────────────

    private final LazyOptional<ItemTemperatureCapability> holder =
            LazyOptional.of(() -> this);

    // ── Constructor ───────────────────────────────────────────────────────────

    public ItemTemperatureCapability(double initialCelsius, double thermalMass) {
        this.celsius = initialCelsius;
        this.thermalMass = thermalMass;
        this.heatState = HeatState.fromCelsius(initialCelsius);
    }

    public ItemTemperatureCapability() {
        this(20.0, CLAY_THERMAL_MASS);
    }

    // ── ICapabilitySerializable ───────────────────────────────────────────────

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap,
                                             @Nullable Direction side) {
        return CAPABILITY.orEmpty(cap, holder);
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("MisTemperature", celsius);
        tag.putDouble("MisThermalMass", thermalMass);
        tag.putByte("MisHeatState", heatState.id());
        tag.putInt("MisFiringTicks", ticksAtFiringTemp);
        tag.putInt("MisCookTicks", cookProgressTicks);
        tag.putInt("MisBurnTicks", burnProgressTicks);
        tag.putInt("MisCharTicks", charProgressTicks);
        tag.putInt("MisFreezeTicks", freezeProgressTicks);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        celsius = tag.getDouble("MisTemperature");
        thermalMass = tag.getDouble("MisThermalMass");
        heatState = HeatState.fromId(tag.getByte("MisHeatState"));
        ticksAtFiringTemp = tag.getInt("MisFiringTicks");
        cookProgressTicks = tag.getInt("MisCookTicks");
        burnProgressTicks = tag.getInt("MisBurnTicks");
        charProgressTicks = tag.getInt("MisCharTicks");
        freezeProgressTicks = tag.getInt("MisFreezeTicks");
    }

    // ── Core temperature logic ────────────────────────────────────────────────

    /**
     * Move temperature toward a target and recompute heatState.
     * Called every N ticks by {@link exp.CCnewmods.misanthrope_core.temperature.handler.ItemTemperatureTickHandler}.
     */
    public void tickTowardAmbient(double ambientCelsius, int elapsedTicks) {
        if (thermalMass <= 0) {
            celsius = ambientCelsius;
        } else {
            double rate = 1.0 / thermalMass;
            double factor = 1.0 - Math.pow(1.0 - rate, elapsedTicks);
            celsius = celsius + (ambientCelsius - celsius) * factor;
        }
        heatState = HeatState.fromCelsius(celsius);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public double getCelsius() {
        return celsius;
    }

    public double getThermalMass() {
        return thermalMass;
    }

    public HeatState getHeatState() {
        return heatState;
    }

    public void setCelsius(double c) {
        this.celsius = c;
        heatState = HeatState.fromCelsius(c);
    }

    public void setThermalMass(double m) {
        this.thermalMass = m;
    }

    // ── Firing ticks (pottery) ────────────────────────────────────────────────

    public int getTicksAtFiringTemp() {
        return ticksAtFiringTemp;
    }

    public void incrementFiringTicks() {
        ticksAtFiringTemp++;
    }

    public void resetFiringTicks() {
        ticksAtFiringTemp = 0;
    }

    // ── Cook / burn / char progress ───────────────────────────────────────────

    public int getCookProgressTicks() {
        return cookProgressTicks;
    }

    public void incrementCookProgress() {
        cookProgressTicks++;
    }

    public void resetCookProgress() {
        cookProgressTicks = 0;
        burnProgressTicks = 0;
    }

    public int getBurnProgressTicks() {
        return burnProgressTicks;
    }

    public void incrementBurnProgress() {
        burnProgressTicks++;
    }

    public void resetBurnProgress() {
        burnProgressTicks = 0;
        charProgressTicks = 0;
    }

    public int getCharProgressTicks() {
        return charProgressTicks;
    }

    public void incrementCharProgress() {
        charProgressTicks++;
    }

    public void resetCharProgress() {
        charProgressTicks = 0;
    }

    // ── Freeze progress ───────────────────────────────────────────────────────

    public int getFreezeProgressTicks() {
        return freezeProgressTicks;
    }

    public void incrementFreezeProgress() {
        freezeProgressTicks++;
    }

    public void resetFreezeProgress() {
        freezeProgressTicks = 0;
    }

    // ── Convenience progress fractions (0–1) for HUD ─────────────────────────

    public float cookFraction(int required) {
        return required <= 0 ? 0f : Math.min(1f, cookProgressTicks / (float) required);
    }

    public float burnFraction(int required) {
        return required <= 0 ? 0f : Math.min(1f, burnProgressTicks / (float) required);
    }

    public float charFraction(int required) {
        return required <= 0 ? 0f : Math.min(1f, charProgressTicks / (float) required);
    }

    public float freezeFraction(int required) {
        return required <= 0 ? 0f : Math.min(1f, freezeProgressTicks / (float) required);
    }

    // ── Registration ─────────────────────────────────────────────────────────

    /**
     * No-op in Forge 47.x — registration is automatic via field init.
     */
    public static void register() {
    }

    public static net.minecraftforge.common.capabilities.ICapabilityProvider createProvider(
            net.minecraft.world.item.ItemStack stack) {
        double mass = AttachHandler.determineThermalMassStatic(stack);
        return new ItemTemperatureCapability(20.0, mass);
    }

    // ── Attachment event ──────────────────────────────────────────────────────

    @Mod.EventBusSubscriber(modid = "misanthrope_world",
            bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class AttachHandler {

        private static final ResourceLocation TEMPERATURE_SENSITIVE_TAG =
                new ResourceLocation("misanthrope_core", "temperature_sensitive");

        @SubscribeEvent
        public static void onAttachCapabilities(AttachCapabilitiesEvent<ItemStack> event) {
            ItemStack stack = event.getObject();
            if (stack.isEmpty()) return;
            if (!isTemperatureSensitive(stack)) return;
            double mass = determineThermalMass(stack);
            event.addCapability(CAPABILITY_ID,
                    new ItemTemperatureCapability(20.0, mass));
        }

        private static boolean isTemperatureSensitive(ItemStack stack) {
            return stack.is(net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ITEM,
                    TEMPERATURE_SENSITIVE_TAG));
        }

        static double determineThermalMassStatic(ItemStack stack) {
            return determineThermalMass(stack);
        }

        private static double determineThermalMass(ItemStack stack) {
            // Check registered behavior first — it may carry an explicit thermalMass
            // (not stored in ItemHeatBehavior currently, but future extension point)

            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getKey(stack.getItem());
            if (id == null) return CLAY_THERMAL_MASS;
            String path = id.getPath();

            if (path.contains("ingot") || path.contains("blank") || path.contains("billet"))
                return METAL_THERMAL_MASS;
            if (path.contains("stone") || path.contains("rock") || path.contains("ceramic"))
                return STONE_THERMAL_MASS;
            if (path.contains("ice") || path.contains("snow"))
                return ICE_THERMAL_MASS;
            if (path.contains("log") || path.contains("plank") || path.contains("wood"))
                return WOOD_THERMAL_MASS;
            // Food heuristic: most food items
            if (path.contains("meat") || path.contains("fish") || path.contains("stew")
                    || path.contains("soup") || path.contains("bread") || path.contains("berry")
                    || path.contains("cooked") || path.contains("raw"))
                return FOOD_THERMAL_MASS;

            return CLAY_THERMAL_MASS; // default
        }
    }
}
