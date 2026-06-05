package exp.CCnewmods.misanthrope_world.temperature;

import exp.CCnewmods.misanthrope_world.temperature.capability.ItemTemperatureCapability;
import exp.CCnewmods.misanthrope_world.temperature.storage.ThermalStorageRegistry;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * One-stop registration for the entire temperature system.
 * <p>
 * Call TemperatureSystemSetup.register() from your FMLCommonSetupEvent handler
 * inside enqueueWork().
 * <p>
 * ── What gets registered here ────────────────────────────────────────────────
 * 1. ItemTemperatureCapability — Forge capability for item-level temperature
 * 2. Built-in dynamic temperature providers — crucible, furnace, kiln, etc.
 * <p>
 * ── What self-registers via @Mod.EventBusSubscriber ──────────────────────────
 * ItemTemperatureCapability.AttachHandler  → AttachCapabilitiesEvent<ItemStack>
 * ThermodynamicaToColdSweatBridge          → BlockTempRegisterEvent
 * EntityHeatEmitter                        → LivingTickEvent
 * ItemTemperatureTickHandler               → LevelTickEvent + LivingTickEvent
 * MeltRegistry                             → AddReloadListenerEvent
 * ItemHeatBehaviorRegistry                 → AddReloadListenerEvent
 * ThermalStorageRegistry                   → AddReloadListenerEvent
 * ThermalMaterialRegistry                  → AddReloadListenerEvent
 * ItemTemperatureColorHandler              → RegisterColorHandlersEvent (CLIENT)
 * <p>
 * ── Tags to create ────────────────────────────────────────────────────────────
 * <p>
 * data/misanthrope_core/tags/items/temperature_sensitive.json
 * → All unfired clay items, forging blanks, knapped parts, ice variants
 * → Items in this tag get ItemTemperatureCapability attached automatically
 * <p>
 * data/misanthrope_core/tags/blocks/kiln_block.json
 * → ceramics:kiln, minecraft:furnace, minecraft:blast_furnace,
 * minecraft:smoker, minecraft:campfire, misanthrope_core:crucible (all tiers)
 * → Items inside fire at KILN_FIRING_TICKS rate and don't crack
 * <p>
 * data/misanthrope_core/tags/items/thermal_material/<name>.json
 * → One tag per material: clay, iron, copper, gold, glass, stone, ice
 * → Items in #misanthrope_core:thermal_material/clay get clay heat tints
 * <p>
 * ── Dynamic temperature provider registration ─────────────────────────────────
 * Block entities that track their own temperature must self-register:
 * <p>
 * ThermalStorageRegistry.registerDynamicProvider(
 * new ResourceLocation("misanthrope_core", "crucible"),
 * be -> be instanceof CrucibleBlockEntity c ? c.getCurrentCelsius() : Double.NaN
 * );
 * <p>
 * Call this from your block entity's class static initializer or from
 * FMLCommonSetupEvent (after this setup call, so the registry exists).
 * <p>
 * ── Thermal storage data files ────────────────────────────────────────────────
 * data/misanthrope_core/thermal_storage/<n>.json
 * Pre-defined entries: crucible, kiln, blast_furnace, furnace, campfire,
 * soul_campfire, chest, barrel, smoker.
 * Add more for any block that acts as thermally significant storage.
 */
public class TemperatureSystemSetup {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(TemperatureSystemSetup::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Register the Forge capability
            ItemTemperatureCapability.register();

            // Register built-in dynamic temperature providers.
            // These map provider IDs (used in thermal_storage JSON files) to
            // lambdas that read the actual temperature from the block entity.

            // Vanilla furnace / blast furnace / smoker
            // Vanilla AbstractFurnaceBlockEntity tracks cookingProgress/totalCookTime
            // but not temperature directly — we derive it from lit state.
            ThermalStorageRegistry.registerDynamicProvider(
                    new net.minecraft.resources.ResourceLocation("misanthrope_core", "vanilla_furnace"),
                    be -> {
                        if (!(be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace))
                            return Double.NaN;
                        // litTime > 0 means the furnace is burning
                        // Vanilla furnace burning temp ≈ 800°C (approximation)
                        // Access litTime via reflection since it's package-private
                        try {
                            var field = net.minecraft.world.level.block.entity
                                    .AbstractFurnaceBlockEntity.class
                                    .getDeclaredField("f_58374_"); // litTime SRG name
                            field.setAccessible(true);
                            int litTime = (int) field.get(furnace);
                            return litTime > 0 ? 800.0 : Double.NaN;
                        } catch (Exception e) {
                            return Double.NaN;
                        }
                    });

            // Ceramics kiln — similar to furnace
            ThermalStorageRegistry.registerDynamicProvider(
                    new net.minecraft.resources.ResourceLocation("misanthrope_core", "ceramics_kiln"),
                    be -> {
                        // KilnBlockEntity extends AbstractFurnaceBlockEntity indirectly
                        // Same lit-state check
                        var cls = be.getClass();
                        if (!cls.getName().contains("KilnBlockEntity")) return Double.NaN;
                        try {
                            var field = cls.getSuperclass().getDeclaredField("f_58374_");
                            field.setAccessible(true);
                            int litTime = (int) field.get(be);
                            return litTime > 0 ? 900.0 : Double.NaN; // kiln hotter than furnace
                        } catch (Exception e) {
                            return Double.NaN;
                        }
                    });

            // Crucible — registered by CrucibleBlockEntity itself
            // (placeholder here; CrucibleBlockEntity calls registerDynamicProvider
            //  during its own initialization with the actual temperature reading)
        });
    }
}

