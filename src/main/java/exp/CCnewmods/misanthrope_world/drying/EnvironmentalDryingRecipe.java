package exp.CCnewmods.misanthrope_world.drying;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * Replaces {@code tinkers_thinking:drying_rack} entirely.
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "type": "misanthrope_world:environmental_drying",
 *   "ingredient": { "item": "misanthrope_core:raw_hide" },
 *   "result":     { "item": "misanthrope_core:dried_hide" },
 *   "base_ticks": 2400,
 *   "min_temp_celsius": 15.0,
 *   "max_humidity_mbar": 12.0,
 *   "requires_airflow": true,
 *   "open_air_allowed": false,
 *   "rack_speed_multiplier": 1.0
 * }
 * }</pre>
 *
 * <p>{@code base_ticks} is the drying time under perfect conditions.
 * The BE mixin scales this by actual vs. ideal environmental conditions.
 * Omitting environmental fields means the item dries freely (vanilla behaviour).
 */
public class EnvironmentalDryingRecipe implements Recipe<SimpleContainer> {

    private final ResourceLocation id;
    private final Ingredient ingredient;
    private final ItemStack result;
    /**
     * Ticks to complete at ideal conditions (min temp met, max humidity met, airflow present).
     */
    private final int baseTicks;
    private final double minTempCelsius;
    private final double maxHumidityMbar;
    private final boolean requiresAirflow;
    private final boolean openAirAllowed;
    /**
     * Multiplier applied to progress per tick when conditions are met. >1 = faster than base.
     */
    private final double rackSpeedMultiplier;

    public EnvironmentalDryingRecipe(ResourceLocation id, Ingredient ingredient, ItemStack result,
                                     int baseTicks, double minTempCelsius, double maxHumidityMbar,
                                     boolean requiresAirflow, boolean openAirAllowed,
                                     double rackSpeedMultiplier) {
        this.id = id;
        this.ingredient = ingredient;
        this.result = result;
        this.baseTicks = baseTicks;
        this.minTempCelsius = minTempCelsius;
        this.maxHumidityMbar = maxHumidityMbar;
        this.requiresAirflow = requiresAirflow;
        this.openAirAllowed = openAirAllowed;
        this.rackSpeedMultiplier = rackSpeedMultiplier;
    }

    // ── Recipe<SimpleContainer> ───────────────────────────────────────────────

    /**
     * True if slot 0 of the container matches our ingredient.
     */
    @Override
    public boolean matches(SimpleContainer container, Level level) {
        return ingredient.test(container.getItem(0));
    }

    @Override
    public ItemStack assemble(SimpleContainer container, RegistryAccess access) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        return result.copy();
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return EnvironmentalDryingRecipeSerializer.INSTANCE;
    }

    @Override
    public RecipeType<?> getType() {
        return EnvironmentalDryingRecipeType.INSTANCE;
    }

    // ── Environmental condition checking ─────────────────────────────────────

    /**
     * Returns true if the supplied environment satisfies this recipe's requirements.
     * When false the BE mixin freezes (and resets) progress.
     */
    public boolean conditionsMet(double ambientCelsius, double humidityMbar, boolean hasAirflow) {
        if (ambientCelsius < minTempCelsius) return false;
        if (humidityMbar > maxHumidityMbar) return false;
        if (requiresAirflow && !hasAirflow) return false;
        return true;
    }

    /**
     * Returns a 0–1 quality factor for the current conditions.
     * 1.0 = ideal (temp exactly at or above min, humidity exactly at or below max).
     * Used by the BE mixin to scale progress speed above the baseline.
     *
     * <p>Formula: geometric mean of temp excess fraction and humidity headroom fraction,
     * each clamped to [0, 1]. So a 40°C environment when min is 15°C scores better than 16°C.
     */
    public double conditionQuality(double ambientCelsius, double humidityMbar) {
        // Temp quality: 0 at minTemp, 1 at minTemp+30, capped at 1
        double tempQ = Math.min(1.0, (ambientCelsius - minTempCelsius) / 30.0);
        // Humidity quality: 0 at maxHumidity, 1 at maxHumidity-10, capped at 1
        double humQ = Math.min(1.0, (maxHumidityMbar - humidityMbar) / 10.0);
        // Geometric mean so both matter
        return Math.max(0.0, Math.sqrt(Math.max(0, tempQ) * Math.max(0, humQ)));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Ingredient getIngredient() {
        return ingredient;
    }

    public ItemStack getResult() {
        return result;
    }

    public int getBaseTicks() {
        return baseTicks;
    }

    public double getMinTempCelsius() {
        return minTempCelsius;
    }

    public double getMaxHumidityMbar() {
        return maxHumidityMbar;
    }

    public boolean isRequiresAirflow() {
        return requiresAirflow;
    }

    public boolean isOpenAirAllowed() {
        return openAirAllowed;
    }

    public double getRackSpeedMultiplier() {
        return rackSpeedMultiplier;
    }
}
