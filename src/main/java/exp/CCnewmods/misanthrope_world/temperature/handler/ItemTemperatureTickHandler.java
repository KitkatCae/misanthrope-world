package exp.CCnewmods.misanthrope_world.temperature.handler;

import exp.CCnewmods.misanthrope_world.temperature.api.MisTemperatureAPI;
import exp.CCnewmods.misanthrope_world.temperature.behavior.ItemHeatBehavior;
import exp.CCnewmods.misanthrope_world.temperature.behavior.ItemHeatBehaviorRegistry;
import exp.CCnewmods.misanthrope_world.temperature.capability.ItemTemperatureCapability;
import exp.CCnewmods.misanthrope_world.temperature.fluid.FluidPlacementHandler;
import exp.CCnewmods.misanthrope_world.temperature.heatstate.HeatState;
import exp.CCnewmods.misanthrope_world.temperature.melt.MeltData;
import exp.CCnewmods.misanthrope_world.temperature.melt.MeltRegistry;
import exp.CCnewmods.misanthrope_world.temperature.storage.ThermalStorageData;
import exp.CCnewmods.misanthrope_world.temperature.storage.ThermalStorageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticks item temperatures across all contexts and handles all state transitions.
 * <p>
 * ── Contexts handled ──────────────────────────────────────────────────────────
 * 1. Block entity inventories (every 20 ticks)
 * 2. Player inventory + held items (every 40 ticks, via LivingTickEvent)
 * 3. Dropped item entities (every 40 ticks)
 * <p>
 * ── State transitions (all contexts) ─────────────────────────────────────────
 * COOKING:    item above cook_min_celsius for cook_ticks → cook_result
 * BURNING:    item above burn_min_celsius for burn_ticks → burn_result
 * CHARRING:   item above char_min_celsius for char_ticks → char_result
 * FREEZING:   item below freeze_max_celsius for freeze_ticks → freeze_result
 * MELTING:    item above melt_celsius → fluid placement (MeltRegistry)
 * CLAY CRACK: unfired clay above cracking threshold outside kilns → shards
 * CLAY FIRE:  unfired clay at firing temperature for sufficient ticks → fired
 * <p>
 * ── Player damage from held/worn items ───────────────────────────────────────
 * Items in VERY_HOT+ or DEEP_FROZEN state deal damage when held in the mainhand,
 * offhand, or any armor slot. Damage rate is per-second (converted to per-tick
 * damage at the check interval). Items tagged with no-damage immunity are excluded.
 * <p>
 * Damage type: {@code misanthrope_core:heat_contact} (hot items)
 *              {@code misanthrope_core:frost_contact} (deep-frozen items)
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ItemTemperatureTickHandler {

    // ── Tick intervals ────────────────────────────────────────────────────────
    private static final int BE_INTERVAL = 20;
    private static final int ENTITY_INTERVAL = 40;
    private static final int ITEM_ENTITY_INTERVAL = 40;
    /**
     * How often (ticks) we check player damage from held items.
     */
    private static final int DAMAGE_CHECK_INTERVAL = 20; // once per second

    private static final double CERAMICS_CRACKING_CELSIUS = 650.0;

    private static final ResourceLocation KILN_TAG =
            new ResourceLocation("misanthrope_core", "kiln_block");
    private static final ResourceKey<DamageType> HEAT_CONTACT_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    new ResourceLocation("misanthrope_core", "heat_contact"));
    private static final ResourceKey<DamageType> FROST_CONTACT_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    new ResourceLocation("misanthrope_core", "frost_contact"));

    // ── Firing overrides (pottery) ────────────────────────────────────────────
    private static final Map<ResourceLocation, ResourceLocation> FIRING_OVERRIDES =
            new ConcurrentHashMap<>();

    public static void registerFiringOverride(ResourceLocation unfiredId, ResourceLocation firedId) {
        FIRING_OVERRIDES.put(unfiredId, firedId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LEVEL TICK — block entity inventories + dropped items
    // ─────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        long tick = level.getGameTime();

        if (tick % BE_INTERVAL == 0) {
            tickBlockEntityInventories(level, tick);
        }
        if (tick % ITEM_ENTITY_INTERVAL == 0) {
            tickDroppedItems(level);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockEntity> getBlockEntities(ServerLevel level) {
        try {
            var field = net.minecraft.world.level.Level.class.getDeclaredField("f_104557_");
            field.setAccessible(true);
            return (List<BlockEntity>) field.get(level);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    private static void tickBlockEntityInventories(ServerLevel level, long tick) {
        for (BlockEntity be : getBlockEntities(level)) {
            BlockPos pos = be.getBlockPos();

            double ambientCelsius = MisTemperatureAPI.getAmbientCelsius(level, pos);
            ThermalStorageData storage = ThermalStorageRegistry.getForBlockPos(level, pos);

            double effectiveCelsius;
            double rateMultiplier;
            boolean isKiln = isKiln(level, pos);
            BlockState beState = level.getBlockState(pos);

            if (storage != null) {
                effectiveCelsius = storage.effectiveTemperature(ambientCelsius, be, beState);
                rateMultiplier = storage.effectiveTickRateMultiplier(beState);
            } else {
                effectiveCelsius = ambientCelsius;
                rateMultiplier   = 1.0;
            }

            if (ambientCelsius >= CERAMICS_CRACKING_CELSIUS) {
                tryCeramicsCrackingHook(be, ambientCelsius);
            }

            be.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler ->
                    processInventory(level, pos, handler, effectiveCelsius,
                            rateMultiplier, BE_INTERVAL, isKiln));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIVING TICK — entity inventories + player damage check
    // ─────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        long tick = entity.tickCount;

        // Temperature equalization in inventory
        if (tick % ENTITY_INTERVAL == 0) {
            double entityBodyCelsius = MisTemperatureAPI.getEntityBodyCelsius(entity);
            if (entity instanceof Player player) {
                tickPlayerInventory(level, player, entityBodyCelsius);
            } else {
                tickEntityEquipment(level, entity, entityBodyCelsius);
            }
        }

        // Player damage from held/worn items
        if (entity instanceof Player player && tick % DAMAGE_CHECK_INTERVAL == 0) {
            checkPlayerContactDamage(level, player);
        }
    }

    // ── Player inventory temperature equalization ─────────────────────────────

    private static void tickPlayerInventory(ServerLevel level, Player player,
                                            double entityBodyCelsius) {
        var inventory = player.getInventory();
        List<ItemTransformation> transforms = new ArrayList<>();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            final int slot = i;
            stack.getCapability(ItemTemperatureCapability.CAPABILITY).ifPresent(cap -> {
                // Slightly slower equalization — clothing insulates
                cap.tickTowardAmbient(entityBodyCelsius, (int) (ENTITY_INTERVAL * 0.7));
                collectTransitions(level, player.blockPosition(), stack, cap,
                        entityBodyCelsius, false, transforms, slot, true);
            });
        }

        applyPlayerTransforms(level, player, inventory, transforms);
    }

    private static void tickEntityEquipment(ServerLevel level, LivingEntity entity,
                                            double entityBodyCelsius) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            stack.getCapability(ItemTemperatureCapability.CAPABILITY)
                    .ifPresent(cap -> cap.tickTowardAmbient(entityBodyCelsius, ENTITY_INTERVAL));
        }
    }

    // ── Player contact damage from hot/cold items ─────────────────────────────

    /**
     * Check all held and worn items; deal contact damage if any are dangerously hot or frozen.
     * <p>
     * Damage accumulates from all slots independently — holding a white-hot ingot
     * in both hands is twice as bad as holding one. Armor absorbs heat damage normally.
     * <p>
     * Called at {@link #DAMAGE_CHECK_INTERVAL} ticks = once per second, so
     * damage-per-second values map 1:1 to damage dealt here.
     */
    private static void checkPlayerContactDamage(ServerLevel level, Player player) {
        float totalHeatDamage = 0f;
        float totalFrostDamage = 0f;

        // Mainhand + offhand
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) continue;
            var result = computeContactDamage(stack);
            totalHeatDamage += result[0];
            totalFrostDamage += result[1];
        }

        // Armor slots — direct skin contact (no gloves check for now)
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            var result = computeContactDamage(stack);
            // Armor contact attenuated — wearing hot armor still hurts but less than holding
            totalHeatDamage += result[0] * 0.5f;
            totalFrostDamage += result[1] * 0.5f;
        }

        if (totalHeatDamage > 0f) {
            player.hurt(new DamageSource(
                            level.registryAccess()
                                    .registryOrThrow(Registries.DAMAGE_TYPE)
                                    .getHolderOrThrow(HEAT_CONTACT_DAMAGE)),
                    totalHeatDamage);
        }
        if (totalFrostDamage > 0f) {
            player.hurt(new DamageSource(
                            level.registryAccess()
                                    .registryOrThrow(Registries.DAMAGE_TYPE)
                                    .getHolderOrThrow(FROST_CONTACT_DAMAGE)),
                    totalFrostDamage);
        }
    }

    /**
     * Returns [heatDamage, frostDamage] for one item stack at its current temperature.
     * Values are per-second (since the check fires at 20-tick intervals).
     */
    private static float[] computeContactDamage(ItemStack stack) {
        var capOpt = stack.getCapability(ItemTemperatureCapability.CAPABILITY);
        if (!capOpt.isPresent()) return new float[]{0f, 0f};

        var cap = capOpt.orElseThrow(RuntimeException::new);
        HeatState state = cap.getHeatState();

        if (!state.damagesPlayer()) return new float[]{0f, 0f};

        ItemHeatBehavior behavior = ItemHeatBehaviorRegistry.get(stack);
        double dps = behavior != null
                ? behavior.damagePerSecond(state)
                : ItemHeatBehavior.HeatDamageDefaults.class
                .getDeclaredFields().length > 0   // always true — just ensures class is loaded
                ? defaultDps(state)
                : 0.0;

        if (dps <= 0.0) return new float[]{0f, 0f};

        if (state.isColdDamage()) return new float[]{0f, (float) dps};
        return new float[]{(float) dps, 0f};
    }

    private static double defaultDps(HeatState state) {
        return switch (state) {
            case VERY_HOT -> ItemHeatBehavior.HeatDamageDefaults.VERY_HOT;
            case RED_HOT -> ItemHeatBehavior.HeatDamageDefaults.RED_HOT;
            case ORANGE_HOT -> ItemHeatBehavior.HeatDamageDefaults.ORANGE_HOT;
            case YELLOW_HOT -> ItemHeatBehavior.HeatDamageDefaults.YELLOW_HOT;
            case WHITE_HOT -> ItemHeatBehavior.HeatDamageDefaults.WHITE_HOT;
            case BLUE_HOT -> ItemHeatBehavior.HeatDamageDefaults.BLUE_HOT;
            case DEEP_FROZEN -> ItemHeatBehavior.HeatDamageDefaults.DEEP_FROZEN;
            default -> 0.0;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DROPPED ITEM ENTITIES
    // ─────────────────────────────────────────────────────────────────────────

    private static void tickDroppedItems(ServerLevel level) {
        List<ItemEntity> toRemove = new ArrayList<>();

        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(
                        -30_000_000, level.getMinBuildHeight(), -30_000_000,
                        30_000_000, level.getMaxBuildHeight(), 30_000_000))) {

            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) continue;

            stack.getCapability(ItemTemperatureCapability.CAPABILITY).ifPresent(cap -> {
                BlockPos pos = itemEntity.blockPosition();
                double ambient = MisTemperatureAPI.getAmbientCelsius(level, pos);

                cap.tickTowardAmbient(ambient, ITEM_ENTITY_INTERVAL);
                double itemTemp = cap.getCelsius();

                // Melting (priority over cooking)
                MeltData melt = MeltRegistry.get(stack);
                if (melt != null && itemTemp >= melt.meltCelsius()) {
                    if (FluidPlacementHandler.placeMeltFluid(level, pos, melt, level.getRandom())) {
                        dropByproduct(level, pos, melt, level.getRandom());
                        toRemove.add(itemEntity);
                        return;
                    }
                }

                // Clay cracking
                if (itemTemp >= MisTemperatureAPI.CLAY_CRACKING_CELSIUS) {
                    ItemStack shards = getShardsForItem(stack);
                    if (shards != null) {
                        level.addFreshEntity(new ItemEntity(level,
                                itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), shards));
                        toRemove.add(itemEntity);
                        return;
                    }
                }

                // Clay firing progression
                if (MisTemperatureAPI.isItemAtFiringTemperature(stack, ambient)) {
                    cap.incrementFiringTicks();
                    if (cap.getTicksAtFiringTemp() >= MisTemperatureAPI.AMBIENT_FIRING_TICKS) {
                        ItemStack fired = getFiredVersion(stack);
                        if (fired != null) {
                            level.addFreshEntity(new ItemEntity(level,
                                    itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), fired));
                            toRemove.add(itemEntity);
                        }
                        cap.resetFiringTicks();
                    }
                } else {
                    cap.resetFiringTicks();
                }

                // Heat behavior transitions (cooking / burning / charring / freezing)
                ItemHeatBehavior behavior = ItemHeatBehaviorRegistry.get(stack);
                if (behavior != null) {
                    List<ItemTransformation> transforms = new ArrayList<>();
                    collectHeatBehaviorTransitions(stack, cap, behavior, 0, transforms);
                    if (!transforms.isEmpty()) {
                        ItemTransformation t = transforms.get(0);
                        if (!t.result().isEmpty()) {
                            itemEntity.setItem(t.result());
                        } else {
                            toRemove.add(itemEntity);
                        }
                    }
                }
            });
        }

        toRemove.forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORE INVENTORY PROCESSING
    // ─────────────────────────────────────────────────────────────────────────

    private static void processInventory(ServerLevel level, BlockPos pos,
                                         IItemHandler handler,
                                         double effectiveCelsius,
                                         double rateMultiplier,
                                         int baseTicks,
                                         boolean isKiln) {
        List<ItemTransformation> transforms = new ArrayList<>();
        int effectiveTicks = Math.max(1, (int) (baseTicks / Math.max(0.01, rateMultiplier)));

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            final int s = slot;
            stack.getCapability(ItemTemperatureCapability.CAPABILITY).ifPresent(cap -> {
                cap.tickTowardAmbient(effectiveCelsius, effectiveTicks);
                collectTransitions(level, pos, stack, cap, effectiveCelsius,
                        isKiln, transforms, s, false);
            });
        }

        for (ItemTransformation t : transforms) {
            ItemStack extracted = handler.extractItem(t.slot(), t.count(), false);
            if (!extracted.isEmpty()) {
                ItemStack remaining = handler.insertItem(t.slot(), t.result(), false);
                if (!remaining.isEmpty()) dropAtPos(level, pos, remaining);
            }
        }
    }

    /**
     * Evaluate all applicable transitions for one item slot and queue results.
     */
    private static void collectTransitions(ServerLevel level, BlockPos pos,
                                           ItemStack stack,
                                           ItemTemperatureCapability cap,
                                           double effectiveCelsius,
                                           boolean isKiln,
                                           List<ItemTransformation> out,
                                           int slot,
                                           boolean isPlayerInv) {
        double itemTemp = cap.getCelsius();

        // 1. Melting (top priority)
        MeltData melt = MeltRegistry.get(stack);
        if (melt != null && itemTemp >= melt.meltCelsius()) {
            if (FluidPlacementHandler.placeMeltFluid(level, pos, melt, level.getRandom())) {
                dropByproduct(level, pos, melt, level.getRandom());
                out.add(new ItemTransformation(slot, stack.getCount(), ItemStack.EMPTY));
                return;
            }
        }

        // 2. Clay cracking (outside kilns)
        if (!isKiln && itemTemp >= MisTemperatureAPI.CLAY_CRACKING_CELSIUS) {
            ItemStack shards = getShardsForItem(stack);
            if (shards != null) {
                out.add(new ItemTransformation(slot, stack.getCount(), shards));
                return;
            }
        }

        // 3. Clay firing progression
        if (MisTemperatureAPI.isItemAtFiringTemperature(stack, effectiveCelsius)) {
            cap.incrementFiringTicks();
            int required = isKiln ? MisTemperatureAPI.KILN_FIRING_TICKS
                    : MisTemperatureAPI.AMBIENT_FIRING_TICKS;
            if (cap.getTicksAtFiringTemp() >= required) {
                ItemStack fired = getFiredVersion(stack);
                if (fired != null) out.add(new ItemTransformation(slot, stack.getCount(), fired));
                cap.resetFiringTicks();
            }
        } else {
            cap.resetFiringTicks();
        }

        // 4. Food / wood heat behavior (cooking → burning → charring, freezing)
        ItemHeatBehavior behavior = ItemHeatBehaviorRegistry.get(stack);
        if (behavior != null) {
            collectHeatBehaviorTransitions(stack, cap, behavior, slot, out);
        }
    }

    /**
     * Evaluate cooking / burning / charring / freezing transitions for one item.
     * Mutually exclusive — first matching transition wins per tick.
     */
    private static void collectHeatBehaviorTransitions(ItemStack stack,
                                                       ItemTemperatureCapability cap,
                                                       ItemHeatBehavior behavior,
                                                       int slot,
                                                       List<ItemTransformation> out) {
        double temp = cap.getCelsius();

        // ── Charring (highest temp priority) ──────────────────────────────────
        if (behavior.charResult() != null && temp >= behavior.charMinCelsius()) {
            cap.incrementCharProgress();
            cap.resetCookProgress();
            cap.resetBurnProgress();
            if (cap.getCharProgressTicks() >= behavior.charTicks()) {
                ItemStack charred = makeStack(behavior.charResult(), stack.getCount());
                if (charred != null) {
                    out.add(new ItemTransformation(slot, stack.getCount(), charred));
                    cap.resetCharProgress();
                }
            }
            return;
        }
        cap.resetCharProgress();

        // ── Burning ────────────────────────────────────────────────────────────
        if (behavior.burnResult() != null && temp >= behavior.burnMinCelsius()) {
            cap.incrementBurnProgress();
            cap.resetCookProgress();
            if (cap.getBurnProgressTicks() >= behavior.burnTicks()) {
                ItemStack burned = makeStack(behavior.burnResult(), stack.getCount());
                if (burned != null) {
                    out.add(new ItemTransformation(slot, stack.getCount(), burned));
                    cap.resetBurnProgress();
                }
            }
            return;
        }
        cap.resetBurnProgress();

        // ── Cooking ────────────────────────────────────────────────────────────
        if (behavior.cookResult() != null && temp >= behavior.cookMinCelsius()) {
            cap.incrementCookProgress();
            if (cap.getCookProgressTicks() >= behavior.cookTicks()) {
                ItemStack cooked = makeStack(behavior.cookResult(), stack.getCount());
                if (cooked != null) {
                    out.add(new ItemTransformation(slot, stack.getCount(), cooked));
                    cap.resetCookProgress();
                }
            }
            return;
        }
        cap.resetCookProgress();

        // ── Freezing (only if not hot) ──────────────────────────────────────────
        if (behavior.freezeResult() != null && temp <= behavior.freezeMaxCelsius()) {
            cap.incrementFreezeProgress();
            if (cap.getFreezeProgressTicks() >= behavior.freezeTicks()) {
                ItemStack frozen = makeStack(behavior.freezeResult(), stack.getCount());
                if (frozen != null) {
                    out.add(new ItemTransformation(slot, stack.getCount(), frozen));
                    cap.resetFreezeProgress();
                }
            }
        } else {
            cap.resetFreezeProgress();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CERAMICS CRACKING HOOK
    // ─────────────────────────────────────────────────────────────────────────

    private static void tryCeramicsCrackingHook(BlockEntity be, double ambientCelsius) {
        try {
            Class<?> iCrackable = Class.forName(
                    "knightminer.ceramics.blocks.entity.CrackableBlockEntityHandler$ICrackableBlockEntity");
            if (!iCrackable.isInstance(be)) return;
            Object handler = iCrackable.getMethod("getCracksHandler").invoke(be);
            Class<?> handlerClass = Class.forName(
                    "knightminer.ceramics.blocks.entity.CrackableBlockEntityHandler");
            int currentCracks = (int) handlerClass.getMethod("getCracks").invoke(handler);
            if (currentCracks >= 3) return;
            handlerClass.getMethod("setCracksRaw", int.class)
                    .invoke(handler, currentCracks + 1);
        } catch (ClassNotFoundException ignored) {
        } catch (Exception ignored) {
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    private static ItemStack makeStack(ResourceLocation id, int count) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) return null;
        return new ItemStack(item, count);
    }

    private static void dropByproduct(ServerLevel level, BlockPos pos, MeltData melt,
                                      net.minecraft.util.RandomSource random) {
        if (melt.byproductItemId() == null) return;
        if (random.nextFloat() > melt.byproductChance()) return;
        var item = melt.getByproductItem();
        if (item == null) return;
        dropAtPos(level, pos, new ItemStack(item));
    }

    private static void dropAtPos(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return;
        var entity = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, stack);
        entity.setPickUpDelay(20);
        level.addFreshEntity(entity);
    }

    @Nullable
    private static ItemStack getFiredVersion(ItemStack unfired) {
        var id = ForgeRegistries.ITEMS.getKey(unfired.getItem());
        if (id == null) return null;
        ResourceLocation override = FIRING_OVERRIDES.get(id);
        if (override != null) {
            var item = ForgeRegistries.ITEMS.getValue(override);
            return item == null ? null : new ItemStack(item, unfired.getCount());
        }
        String path = id.getPath();
        String firedPath;
        if (path.startsWith("unfired_clay_")) firedPath = "terracotta_" + path.substring(13);
        else if (path.startsWith("unfired_ceramic_")) firedPath = "ceramic_" + path.substring(16);
        else if (path.startsWith("unfired_fireclay_")) firedPath = "fireceramic_" + path.substring(17);
        else if (path.startsWith("unfired_")) firedPath = path.substring(8);
        else return null;
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id.getNamespace(), firedPath));
        return item == null ? null : new ItemStack(item, unfired.getCount());
    }

    @Nullable
    private static ItemStack getShardsForItem(ItemStack stack) {
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !id.getPath().startsWith("unfired_")) return null;
        String path = id.getPath();
        String shardId = (path.contains("fireclay") || path.contains("fireceramic"))
                ? "misanthrope_core:fireclay_shard"
                : path.contains("ceramic")
                ? "misanthrope_core:ceramic_shard"
                : "misanthrope_core:terracotta_shard";
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(shardId));
        if (item == null) return null;
        return new ItemStack(item, Math.min(2 * stack.getCount(), 64));
    }

    private static boolean isKiln(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(
                net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.BLOCK, KILN_TAG));
    }

    private static void applyPlayerTransforms(ServerLevel level, Player player,
                                              net.minecraft.world.entity.player.Inventory inv,
                                              List<ItemTransformation> transforms) {
        for (ItemTransformation t : transforms) {
            inv.setItem(t.slot(), t.result().isEmpty() ? ItemStack.EMPTY : t.result());
        }
    }

    // ── Helper types ──────────────────────────────────────────────────────────

    private record ItemTransformation(int slot, int count, ItemStack result) {}
}
