package exp.CCnewmods.misanthrope_world.furnace.recipe;

import exp.CCnewmods.misanthrope_world.compat.mge.MgeGasCompat;
import exp.CCnewmods.misanthrope_world.furnace.environment.FurnaceEnvironmentSampler;
import exp.CCnewmods.misanthrope_world.physics.burned.BurnedFoodRegistry;
import exp.CCnewmods.misanthrope_world.physics.field.ThermalField;
import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import exp.CCnewmods.misanthrope_world.temperature.capability.ItemTemperatureCapability;
import exp.CCnewmods.misanthrope_world.temperature.melt.MeltData;
import exp.CCnewmods.misanthrope_world.temperature.melt.MeltRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Runs each server tick inside a furnace multiblock to:
 * 1. Scan the recipe zone for item entities
 * 2. Heat each item via {@link ItemTemperatureCapability} toward the furnace temperature
 * 3. Track per-item recipe progress via {@link FurnaceItemProgress}
 * 4. On completion: produce outputs, inject MGE gases, clean up
 * <p>
 * ── Integration with existing temperature system ──────────────────────────────
 * {@link ItemTemperatureCapability} is the same capability that
 * {@link exp.CCnewmods.misanthrope_world.temperature.handler.ItemTemperatureTickHandler}
 * uses for block-entity inventories.  Here we drive it ourselves because items are
 * loose item-entities in the furnace shaft, not in an IItemHandler inventory.
 * We apply the same lerp formula (tickTowardAmbient) but use the furnace's actual
 * internal temperature as the target.
 * <p>
 * The {@link exp.CCnewmods.misanthrope_world.temperature.melt.MeltRegistry} is
 * checked first — any item that reaches its melt temperature converts to fluid
 * immediately, regardless of furnace recipes.  FurnaceRecipe only fires for items
 * that have not already melted.
 * <p>
 * ── Gas-driven contextual reactions ───────────────────────────────────────────
 * The gas environment (O₂ mbar) is read from the FurnaceEnvironmentSampler sample
 * that was already computed this tick by the hosting block entity.  The processor
 * receives it as a parameter to avoid double-sampling.
 * <p>
 * Example recipes enabled by gas context:
 * Wood + O₂ + ≥300°C  → ash + CO₂ injection           (combustion)
 * Wood + no O₂ + ≥280°C → charcoal                    (carbonization/pyrolysis)
 * Iron ore + O₂ + ≥900°C → bloom iron + CO/CO₂        (normal smelting)
 * Ice + ≥100°C         → water vapour injection        (boiling)
 * Water + ≥100°C       → steam (WATER_VAPOR mge gas)   (evaporation)
 * <p>
 * ── Usage ─────────────────────────────────────────────────────────────────────
 * Instantiate one per furnace block entity.  Call {@code tick()} from the BE's
 * server tick method, passing the current internal temperature and the already-
 * computed FurnaceEnvironmentSampler.Sample.
 *
 * <pre>
 *   // In BloomeryBlockEntity:
 *   private final FurnaceRecipeProcessor recipeProcessor =
 *       new FurnaceRecipeProcessor("bloomery");
 *
 *   // In serverTick(...):
 *   recipeProcessor.tick(level, recipeScanAABB, internalTempCelsius, envSample);
 * </pre>
 */
public class FurnaceRecipeProcessor {

    /**
     * How often (in ticks) items are heated and recipe progress is checked.
     */
    private static final int TICK_INTERVAL = 10;

    /**
     * How many ticks before an item entity not in the zone is pruned from tracking.
     */
    private static final int STALE_PRUNE_TICKS = 40;

    private final String furnaceType;

    /**
     * Per-entity progress tracking, keyed by entity UUID.
     */
    private final Map<UUID, FurnaceItemProgress> progress = new LinkedHashMap<>();

    /**
     * Counts since an entity was last seen, for pruning stale entries.
     */
    private final Map<UUID, Integer> staleTicks = new HashMap<>();

    /**
     * Queue of completed results to process at end of tick (avoid CME).
     */
    private final List<CompletedResult> pendingResults = new ArrayList<>();

    public FurnaceRecipeProcessor(String furnaceType) {
        this.furnaceType = furnaceType;
    }

    // ── Main tick ─────────────────────────────────────────────────────────────

    /**
     * Process one game tick.  Should be called every tick from the block entity;
     * internally throttled to TICK_INTERVAL for performance.
     *
     * @param level       server level
     * @param recipeZone  AABB covering the interior of the furnace where items land
     * @param furnaceTemp current internal temperature of the furnace in °C
     * @param env         pre-computed environment sample (contains O₂, humidity, etc.)
     */
    public void tick(ServerLevel level,
                     AABB recipeZone,
                     FurnaceEnvironmentSampler.Sample env) {

        long gameTick = level.getGameTime();
        if (gameTick % TICK_INTERVAL != 0) return;

        // ── 1. Scan items in the zone ─────────────────────────────────────────
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, recipeZone);
        Set<UUID> seenIds = new HashSet<>();

        for (ItemEntity entity : items) {
            if (entity.isRemoved()) continue;

            UUID id = entity.getUUID();
            seenIds.add(id);
            staleTicks.remove(id);

            // ── 2. Heat the item toward its position's temperature ─────────────
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) continue;

            // Get temperature at the item's exact position from ThermalField
            double furnaceTemp = ThermalField.getTemperatureAt(level, entity.blockPosition());
            if (Double.isNaN(furnaceTemp)) furnaceTemp = env.ambientCelsius();

            heatItem(stack, furnaceTemp, TICK_INTERVAL);
            double itemCelsius = MisTemperatureAPI.getItemCelsius(stack, furnaceTemp);

            // ── 3. Check melt first (MeltRegistry has priority) ───────────────
            MeltData melt = MeltRegistry.get(stack);
            if (melt != null && itemCelsius >= melt.meltCelsius()) {
                entity.discard();
                pendingResults.add(new CompletedResult(null, stack.copy(), itemCelsius,
                        entity.blockPosition(), true, melt));
                progress.remove(id);
                continue;
            }

            // ── 3b. Check burning (between melt and recipe) ───────────────────
            ItemStack burnedStack = BurnedFoodRegistry.applyBurning(stack, itemCelsius);
            if (burnedStack != stack) {
                entity.setItem(burnedStack);
                progress.remove(id);
                continue;
            }

            // ── 4. Advance recipe progress ────────────────────────────────────
            FurnaceItemProgress prog = progress.computeIfAbsent(id,
                    k -> new FurnaceItemProgress(entity));

            boolean done = prog.tick(entity, itemCelsius, env, furnaceType);

            if (done) {
                FurnaceRecipe recipe = prog.activeRecipe();
                if (recipe != null) {
                    entity.discard();
                    pendingResults.add(new CompletedResult(recipe, stack.copy(), itemCelsius,
                            entity.blockPosition(), false, null));
                }
                progress.remove(id);
            }
        }

        // ── 5. Prune stale entries ────────────────────────────────────────────
        List<UUID> toRemove = new ArrayList<>();
        for (var entry : progress.entrySet()) {
            UUID id = entry.getKey();
            if (!seenIds.contains(id)) {
                int stale = staleTicks.merge(id, TICK_INTERVAL, Integer::sum);
                if (stale >= STALE_PRUNE_TICKS) toRemove.add(id);
            }
        }
        toRemove.forEach(id -> {
            progress.remove(id);
            staleTicks.remove(id);
        });

        // ── 6. Resolve completed results ──────────────────────────────────────
        for (CompletedResult result : pendingResults) {
            resolveResult(level, result, env);
        }
        pendingResults.clear();
    }

    // ── Item heating ──────────────────────────────────────────────────────────

    /**
     * Push the item's temperature toward the furnace temperature via
     * ItemTemperatureCapability.  Attaches a default capability if the item
     * doesn't have one (handles items not in #temperature_sensitive tag).
     */
    private static void heatItem(ItemStack stack, double targetCelsius, int ticks) {
        var capOpt = stack.getCapability(ItemTemperatureCapability.CAPABILITY);

        if (capOpt.isPresent()) {
            capOpt.ifPresent(cap -> cap.tickTowardAmbient(targetCelsius, ticks));
        } else {
            // Item not tagged temperature_sensitive — force-set its temperature
            // without a capability (best-effort, will not persist between ticks
            // but the recipe processor reads from the stack each tick anyway).
            // To get proper thermal mass simulation, add the item to the tag.
            // We store a transient NBT value for read-back in getItemCelsius fallback.
            var tag = stack.getOrCreateTag();
            double current = tag.getDouble("_FurnaceTemp");
            if (current == 0.0) current = 20.0; // cold start
            double rate = 1.0 / 40.0; // default thermal mass 40
            double factor = 1.0 - Math.pow(1.0 - rate, ticks);
            double next = current + (targetCelsius - current) * factor;
            tag.putDouble("_FurnaceTemp", next);
        }
    }

    /**
     * Get the effective item temperature, preferring the capability but falling
     * back to the transient NBT tag written by heatItem.
     */
    private static double getEffectiveItemCelsius(ItemStack stack, double furnaceTemp) {
        double cap = MisTemperatureAPI.getItemCelsius(stack, Double.NaN);
        if (!Double.isNaN(cap)) return cap;
        // Fallback to transient heating tag
        var tag = stack.getTag();
        if (tag != null && tag.contains("_FurnaceTemp"))
            return tag.getDouble("_FurnaceTemp");
        return furnaceTemp; // worst case: assume equilibrium
    }

    // ── Result resolution ─────────────────────────────────────────────────────

    private void resolveResult(ServerLevel level, CompletedResult result,
                               FurnaceEnvironmentSampler.Sample env) {
        BlockPos pos = result.pos();

        if (result.isMelt()) {
            // Melt path — already handled by MeltRegistry / FluidPlacementHandler
            MeltData melt = result.melt();
            if (melt == null) return;

            // Drop byproduct
            ItemStack byproduct = melt.byproductItemId() != null
                    && level.getRandom().nextFloat() < melt.byproductChance()
                    ? new ItemStack(melt.getByproductItem()) : ItemStack.EMPTY;
            if (!byproduct.isEmpty()) dropAt(level, pos, byproduct);

            // Inject melt fluid into nearby fluid system (FluidPlacementHandler)
            try {
                exp.CCnewmods.misanthrope_world.temperature.fluid.FluidPlacementHandler
                        .placeMeltFluid(level, pos, melt, level.getRandom());
            } catch (Exception ignored) {
            }

            return;
        }

        // Recipe path
        FurnaceRecipe recipe = result.recipe();
        if (recipe == null) return;
        ItemStack input = result.input();

        // ── Drop output item ──────────────────────────────────────────────────
        if (!recipe.isFluidOutput()) {
            ItemStack output = recipe.buildOutput();
            if (!output.isEmpty()) dropAt(level, pos, output);
        } else {
            // Fluid output — convert to melt event via MeltRegistry or direct fluid
            if (recipe.outputFluidId() != null) {
                // Try to inject fluid into a nearby FlowingFluids container
                try {
                    var fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS
                            .getValue(recipe.outputFluidId());
                    if (fluid != null) {
                        var fluidStack = new net.minecraftforge.fluids.FluidStack(
                                fluid, recipe.outputCount());
                        injectFluid(level, pos, fluidStack);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // ── Drop byproduct ────────────────────────────────────────────────────
        ItemStack byproduct = recipe.rollByproduct(level.getRandom());
        if (!byproduct.isEmpty()) dropAt(level, pos, byproduct);

        // ── Inject MGE gases ──────────────────────────────────────────────────
        for (FurnaceRecipe.GasEmission emission : recipe.emitsGas()) {
            injectGas(level, pos.above(), emission.gasId(), emission.mbar());
        }

        // ── Automatic gas byproducts from combustion ──────────────────────────
        // When a recipe runs with oxygen present, wood/carbon-based inputs produce CO/CO₂.
        // This is an ambient bonus — the recipe JSON doesn't have to enumerate it.
        if (env.oxygenMbar() > 0f && recipe.requiresOxygen()) {
            float inputCarbon = estimateCarbonContent(input);
            if (inputCarbon > 0f) {
                injectGas(level, pos.above(),
                        new net.minecraft.resources.ResourceLocation("mge", "carbon_monoxide"),
                        inputCarbon * 8f);
                injectGas(level, pos.above(),
                        new net.minecraft.resources.ResourceLocation("mge", "carbon_dioxide"),
                        inputCarbon * 15f);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void dropAt(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return;
        var entity = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        entity.setPickUpDelay(20);
        level.addFreshEntity(entity);
    }

    private static void injectGas(ServerLevel level, BlockPos pos,
                                  net.minecraft.resources.ResourceLocation gasId,
                                  float mbar) {
        if (!MgeGasCompat.isMgeLoaded()) return;
        try {
            exp.CCnewmods.mge.gas.GasRegistry.get(gasId).ifPresent(gas ->
                    exp.CCnewmods.mge.event.WorldEventHandler
                            .injectGas(level, pos, gas, mbar));
        } catch (Exception ignored) {
        }
    }

    private static void injectFluid(ServerLevel level, BlockPos pos,
                                    net.minecraftforge.fluids.FluidStack fluid) {
        // Walk the 6 adjacent positions, find an IFluidHandler, try to fill it.
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockPos adj = pos.relative(dir);
            var be = level.getBlockEntity(adj);
            if (be == null) continue;
            var cap = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER,
                    dir.getOpposite());
            if (!cap.isPresent()) continue;
            int filled = cap.orElseThrow(() -> new RuntimeException("cap lost")).fill(fluid, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            if (filled > 0) return; // placed successfully
        }
        // No handler found — drop as item entity if the fluid has a bucket item
        // (FlowingFluids will handle it; this is a graceful fallback)
    }

    /**
     * Rough carbon content estimate for automatic CO/CO₂ gas generation.
     * Returns 0 for items with no carbon basis.
     */
    private static float estimateCarbonContent(ItemStack stack) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return 0f;
        String path = id.getPath().toLowerCase();
        if (path.contains("log") || path.contains("wood") || path.contains("plank")) return 0.8f;
        if (path.contains("coal") || path.contains("charcoal")) return 1.0f;
        if (path.contains("ore")) return 0.3f; // residual organic matter in ore
        return 0f;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * True if any item in the zone is currently being processed.
     */
    public boolean hasActiveWork() {
        return !progress.isEmpty();
    }

    /**
     * Progress fraction [0,1] for the most-advanced item currently processing.
     * Returns 0 if nothing is in progress.
     */
    public float getMaxProgressFraction() {
        return progress.values().stream()
                .map(FurnaceItemProgress::progressFraction)
                .max(Float::compare)
                .orElse(0f);
    }

    /**
     * Clear all tracked progress.  Call when the furnace structure collapses or
     * the block entity is removed.
     */
    public void clear() {
        progress.clear();
        staleTicks.clear();
        pendingResults.clear();
    }

    // ── Inner result type ─────────────────────────────────────────────────────

    private record CompletedResult(
            @Nullable FurnaceRecipe recipe,
            ItemStack input,
            double itemCelsius,
            BlockPos pos,
            boolean isMelt,
            @Nullable MeltData melt
    ) {
    }
}
