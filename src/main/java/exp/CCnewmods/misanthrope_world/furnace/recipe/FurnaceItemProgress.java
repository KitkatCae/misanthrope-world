package exp.CCnewmods.misanthrope_world.furnace.recipe;

import exp.CCnewmods.misanthrope_world.furnace.environment.FurnaceEnvironmentSampler;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Tracks the processing state of a single ItemEntity inside a furnace multiblock.
 * <p>
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 * Created by FurnaceRecipeProcessor when an item entity lands in the recipe zone
 * and matches at least one FurnaceRecipe.  Discarded when the item is consumed,
 * the entity is removed, or it leaves the zone.
 * <p>
 * ── Progress model ────────────────────────────────────────────────────────────
 * progressTicks advances when:
 * - the item's current temperature >= recipe.minCelsius (and <= maxCelsius)
 * - the gas context (O₂) satisfies the recipe's gas requirements
 * <p>
 * Progress does NOT regress due to temperature dropping, BUT it DOES reset if the
 * temperature drops below (minCelsius - PROGRESS_RESET_HYSTERESIS).  This models
 * partial-sintering reversal — once the metal is well above threshold it stays
 * "warm" without full reset.  If a different gas context triggers a different recipe
 * (e.g. O₂ returns and switches from carbonization to combustion), progress resets
 * to 0 because the active recipe changed.
 */
public class FurnaceItemProgress {

    private final UUID entityId;
    private ItemStack cachedStack; // snapshot — refreshed each tick

    @Nullable
    private FurnaceRecipe activeRecipe;
    private int progressTicks;
    private boolean completed;

    public FurnaceItemProgress(ItemEntity entity) {
        this.entityId = entity.getUUID();
        this.cachedStack = entity.getItem().copy();
        this.activeRecipe = null;
        this.progressTicks = 0;
        this.completed = false;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    /**
     * Advance processing state for one tick.
     *
     * @param entity      the item entity being processed
     * @param itemCelsius the item's current temperature (from ItemTemperatureCapability)
     * @param sample      environment sample (carries live GasComposition)
     * @param furnaceType furnace type string (e.g. "bloomery")
     * @return true if processing completed this tick and an output should be produced
     */
    public boolean tick(ItemEntity entity,
                        double itemCelsius,
                        FurnaceEnvironmentSampler.Sample sample,
                        @Nullable String furnaceType) {
        if (completed || entity.isRemoved()) return false;

        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) return false;

        // Find the recipe that applies right now
        FurnaceRecipe candidate = FurnaceRecipeRegistry.findRecipe(
                stack, itemCelsius, sample, furnaceType);

        if (candidate == null) {
            // No active recipe — reset progress if temperature is in reset band
            if (activeRecipe != null && activeRecipe.shouldResetProgress(itemCelsius)) {
                progressTicks = 0;
                activeRecipe = null;
            }
            return false;
        }

        // Recipe changed → reset progress (different process kicked in)
        if (activeRecipe != null && !activeRecipe.recipeId().equals(candidate.recipeId())) {
            progressTicks = 0;
        }
        activeRecipe = candidate;

        progressTicks++;
        cachedStack = stack.copy();

        if (progressTicks >= activeRecipe.ticksRequired()) {
            completed = true;
            return true;
        }
        return false;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public UUID entityId() {
        return entityId;
    }

    public ItemStack cachedStack() {
        return cachedStack;
    }

    @Nullable
    public FurnaceRecipe activeRecipe() {
        return activeRecipe;
    }

    public int progressTicks() {
        return progressTicks;
    }

    public int ticksRequired() {
        return activeRecipe != null ? activeRecipe.ticksRequired() : 0;
    }

    public boolean isCompleted() {
        return completed;
    }

    /**
     * Progress fraction 0–1 for HUD/renderer.
     */
    public float progressFraction() {
        if (activeRecipe == null || activeRecipe.ticksRequired() <= 0) return 0f;
        return Math.min(1f, (float) progressTicks / activeRecipe.ticksRequired());
    }
}
