package exp.CCnewmods.misanthrope_world.wet_sand;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers all wet-sand block variants and wires them into WetSandRegistry.
 * <p>
 * Each soil type gets four variants: moist / wet / soaked / saturated.
 * The blocks are registered in ForgeRegistries, then handed to
 * WetSandRegistry.registerHardcoded() during common setup so the propagation
 * and drain systems can look them up.
 * <p>
 * To add a new soil type from another mod whose classes are available at
 * compile time, follow the pattern below. For mods only available at runtime,
 * use a data/misanthrope_core/wet_sand/*.json file instead.
 * <p>
 * NOTE: BlockItems are intentionally NOT registered for wet variants.
 * Wet blocks drop their dry equivalent (handled in WettableFallingBlock).
 * If you want wet sand to be an obtainable item in creative mode, add it
 * to MisanthropeCreativeTab manually.
 */
public class WetSandRegistration {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Misanthrope_world.MODID);

    // -------------------------------------------------------------------------
    // Helper: shared block property builders
    // -------------------------------------------------------------------------

    /**
     * Properties matching vanilla sand, adjusted for wetness.
     */
    private static BlockBehaviour.Properties sandProps(MapColor color) {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(0.5F)
                .sound(SoundType.SAND)
                .requiresCorrectToolForDrops();
        // No noOcclusion — wet sand is a full solid block like dry sand
    }

    /**
     * Properties for gravel/dirt-like soils.
     */
    private static BlockBehaviour.Properties dirtProps(MapColor color) {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(0.5F)
                .sound(SoundType.GRAVEL)
                .requiresCorrectToolForDrops();
    }

    // -------------------------------------------------------------------------
    // Vanilla: minecraft:sand
    // -------------------------------------------------------------------------

    public static final RegistryObject<WettableFallingBlock> MOIST_SAND =
            BLOCKS.register("moist_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST,
                            sandProps(MapColor.SAND)));

    public static final RegistryObject<WettableFallingBlock> WET_SAND =
            BLOCKS.register("wet_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET,
                            sandProps(MapColor.SAND)));

    public static final RegistryObject<WettableFallingBlock> SOAKED_SAND =
            BLOCKS.register("soaked_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED,
                            sandProps(MapColor.SAND)));

    public static final RegistryObject<WettableFallingBlock> SATURATED_SAND =
            BLOCKS.register("saturated_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED,
                            sandProps(MapColor.SAND)));

    // -------------------------------------------------------------------------
    // Vanilla: minecraft:red_sand
    // -------------------------------------------------------------------------

    public static final RegistryObject<WettableFallingBlock> MOIST_RED_SAND =
            BLOCKS.register("moist_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST,
                            sandProps(MapColor.COLOR_ORANGE)));

    public static final RegistryObject<WettableFallingBlock> WET_RED_SAND =
            BLOCKS.register("wet_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET,
                            sandProps(MapColor.COLOR_ORANGE)));

    public static final RegistryObject<WettableFallingBlock> SOAKED_RED_SAND =
            BLOCKS.register("soaked_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED,
                            sandProps(MapColor.COLOR_ORANGE)));

    public static final RegistryObject<WettableFallingBlock> SATURATED_RED_SAND =
            BLOCKS.register("saturated_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED,
                            sandProps(MapColor.COLOR_ORANGE)));

    // -------------------------------------------------------------------------
    // Vanilla: minecraft:gravel
    // -------------------------------------------------------------------------

    public static final RegistryObject<WettableFallingBlock> MOIST_GRAVEL =
            BLOCKS.register("moist_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST,
                            dirtProps(MapColor.STONE)));

    public static final RegistryObject<WettableFallingBlock> WET_GRAVEL =
            BLOCKS.register("wet_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.WET,
                            dirtProps(MapColor.STONE)));

    public static final RegistryObject<WettableFallingBlock> SOAKED_GRAVEL =
            BLOCKS.register("soaked_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED,
                            dirtProps(MapColor.STONE)));

    public static final RegistryObject<WettableFallingBlock> SATURATED_GRAVEL =
            BLOCKS.register("saturated_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED,
                            dirtProps(MapColor.STONE)));

    // -------------------------------------------------------------------------
    // Vanilla: minecraft:dirt
    // -------------------------------------------------------------------------

    public static final RegistryObject<WettableFallingBlock> MOIST_DIRT =
            BLOCKS.register("moist_dirt",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST,
                            dirtProps(MapColor.DIRT)));

    public static final RegistryObject<WettableFallingBlock> WET_DIRT =
            BLOCKS.register("wet_dirt",
                    () -> new WettableFallingBlock(WetnessLevel.WET,
                            dirtProps(MapColor.DIRT)));

    public static final RegistryObject<WettableFallingBlock> SOAKED_DIRT =
            BLOCKS.register("soaked_dirt",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED,
                            dirtProps(MapColor.DIRT)));

    public static final RegistryObject<WettableFallingBlock> SATURATED_DIRT =
            BLOCKS.register("saturated_dirt",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED,
                            dirtProps(MapColor.DIRT)));

    // -------------------------------------------------------------------------
    // Registration wiring
    // -------------------------------------------------------------------------

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }

    /**
     * Wire all hardcoded entries into WetSandRegistry.
     * Must be called after ForgeRegistries are frozen (i.e. in FMLCommonSetupEvent
     * via enqueueWork, or in a postRegistryEvent). The RegistryObjects must be
     * resolved at this point.
     */
    public static void registerWetSandEntries() {
        WetSandRegistry registry = WetSandRegistry.INSTANCE;

        // minecraft:sand
        registry.registerHardcoded(
                Blocks.SAND,
                MOIST_SAND.get(), WET_SAND.get(), SOAKED_SAND.get(), SATURATED_SAND.get());

        // minecraft:red_sand
        registry.registerHardcoded(
                Blocks.RED_SAND,
                MOIST_RED_SAND.get(), WET_RED_SAND.get(), SOAKED_RED_SAND.get(), SATURATED_RED_SAND.get());

        // minecraft:gravel
        registry.registerHardcoded(
                Blocks.GRAVEL,
                MOIST_GRAVEL.get(), WET_GRAVEL.get(), SOAKED_GRAVEL.get(), SATURATED_GRAVEL.get());

        // minecraft:dirt
        registry.registerHardcoded(
                Blocks.DIRT,
                MOIST_DIRT.get(), WET_DIRT.get(), SOAKED_DIRT.get(), SATURATED_DIRT.get());

        // ── Add mod blocks below as mods are loaded ────────────────────────
        // Pattern for a conditionally-loaded mod block:
        //
        //   if (ModList.get().isLoaded("quark")) {
        //       Block drySiltSand = ForgeRegistries.BLOCKS.getValue(
        //               new ResourceLocation("quark", "silt"));
        //       if (drySiltSand != null && drySiltSand != Blocks.AIR) {
        //           registry.registerHardcoded(
        //               drySiltSand,
        //               MOIST_SILT.get(), WET_SILT.get(),
        //               SOAKED_SILT.get(), SATURATED_SILT.get());
        //       }
        //   }
        //
        // Add the four WettableFallingBlock RegistryObjects above first,
        // then call registerHardcoded here.
    }

    // ── immersive_weathering ─────────────────────────────────────────
    // immersive_weathering:earthen_clay
    public static final RegistryObject<WettableSoilBlock> MOIST_EARTHEN_CLAY =
            BLOCKS.register("moist_earthen_clay",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.CLAY)));
    public static final RegistryObject<WettableSoilBlock> WET_EARTHEN_CLAY =
            BLOCKS.register("wet_earthen_clay",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.CLAY)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_EARTHEN_CLAY =
            BLOCKS.register("soaked_earthen_clay",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.CLAY)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_EARTHEN_CLAY =
            BLOCKS.register("saturated_earthen_clay",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.CLAY)));

    // immersive_weathering:loam
    public static final RegistryObject<WettableSoilBlock> MOIST_LOAM =
            BLOCKS.register("moist_loam",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_LOAM =
            BLOCKS.register("wet_loam",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_LOAM =
            BLOCKS.register("soaked_loam",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_LOAM =
            BLOCKS.register("saturated_loam",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // immersive_weathering:permafrost
    public static final RegistryObject<WettableFallingBlock> MOIST_PERMAFROST =
            BLOCKS.register("moist_permafrost",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> WET_PERMAFROST =
            BLOCKS.register("wet_permafrost",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_PERMAFROST =
            BLOCKS.register("soaked_permafrost",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_PERMAFROST =
            BLOCKS.register("saturated_permafrost",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.SNOW)));

    // immersive_weathering:silt
    public static final RegistryObject<WettableSoilBlock> MOIST_SILT =
            BLOCKS.register("moist_silt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_SILT =
            BLOCKS.register("wet_silt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SILT =
            BLOCKS.register("soaked_silt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SILT =
            BLOCKS.register("saturated_silt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // ── biomesoplenty ─────────────────────────────────────────
    // biomesoplenty:black_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_BLACK_SAND =
            BLOCKS.register("moist_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> WET_BLACK_SAND =
            BLOCKS.register("wet_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_BLACK_SAND =
            BLOCKS.register("soaked_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_BLACK_SAND =
            BLOCKS.register("saturated_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_BLACK)));

    // biomesoplenty:orange_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_ORANGE_SAND =
            BLOCKS.register("moist_orange_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableFallingBlock> WET_ORANGE_SAND =
            BLOCKS.register("wet_orange_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_ORANGE_SAND =
            BLOCKS.register("soaked_orange_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_ORANGE_SAND =
            BLOCKS.register("saturated_orange_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_ORANGE)));

    // biomesoplenty:white_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_WHITE_SAND =
            BLOCKS.register("moist_white_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> WET_WHITE_SAND =
            BLOCKS.register("wet_white_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_WHITE_SAND =
            BLOCKS.register("soaked_white_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_WHITE_SAND =
            BLOCKS.register("saturated_white_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.SNOW)));

    // biomesoplenty:mossy_black_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_MOSSY_BLACK_SAND =
            BLOCKS.register("moist_mossy_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> WET_MOSSY_BLACK_SAND =
            BLOCKS.register("wet_mossy_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_MOSSY_BLACK_SAND =
            BLOCKS.register("soaked_mossy_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_MOSSY_BLACK_SAND =
            BLOCKS.register("saturated_mossy_black_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_BLACK)));

    // ── aether ─────────────────────────────────────────
    // aether:aether_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_AETHER_DIRT =
            BLOCKS.register("moist_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_AETHER_DIRT =
            BLOCKS.register("wet_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_AETHER_DIRT =
            BLOCKS.register("soaked_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_AETHER_DIRT =
            BLOCKS.register("saturated_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // aether:quicksoil
    public static final RegistryObject<WettableFallingBlock> MOIST_QUICKSOIL =
            BLOCKS.register("moist_quicksoil",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> WET_QUICKSOIL =
            BLOCKS.register("wet_quicksoil",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_QUICKSOIL =
            BLOCKS.register("soaked_quicksoil",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_QUICKSOIL =
            BLOCKS.register("saturated_quicksoil",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.SAND)));

    // ── aether_redux ─────────────────────────────────────────
    // aether_redux:coarse_aether_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_COARSE_AETHER_DIRT =
            BLOCKS.register("moist_coarse_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_COARSE_AETHER_DIRT =
            BLOCKS.register("wet_coarse_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_COARSE_AETHER_DIRT =
            BLOCKS.register("soaked_coarse_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_COARSE_AETHER_DIRT =
            BLOCKS.register("saturated_coarse_aether_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // aether_redux:holysilt
    public static final RegistryObject<WettableFallingBlock> MOIST_HOLYSILT =
            BLOCKS.register("moist_holysilt",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> WET_HOLYSILT =
            BLOCKS.register("wet_holysilt",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_HOLYSILT =
            BLOCKS.register("soaked_holysilt",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_HOLYSILT =
            BLOCKS.register("saturated_holysilt",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.SAND)));

    // ── regions_unexplored ─────────────────────────────────────────
    // regions_unexplored:ash
    public static final RegistryObject<WettableFallingBlock> MOIST_ASH =
            BLOCKS.register("moist_ash",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> WET_ASH =
            BLOCKS.register("wet_ash",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_ASH =
            BLOCKS.register("soaked_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_ASH =
            BLOCKS.register("saturated_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.STONE)));

    // regions_unexplored:ashen_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_ASHEN_DIRT =
            BLOCKS.register("moist_ashen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_ASHEN_DIRT =
            BLOCKS.register("wet_ashen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_ASHEN_DIRT =
            BLOCKS.register("soaked_ashen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_ASHEN_DIRT =
            BLOCKS.register("saturated_ashen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // regions_unexplored:peat_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_PEAT_DIRT =
            BLOCKS.register("moist_peat_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_PEAT_DIRT =
            BLOCKS.register("wet_peat_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_PEAT_DIRT =
            BLOCKS.register("soaked_peat_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_PEAT_DIRT =
            BLOCKS.register("saturated_peat_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // regions_unexplored:silt_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_SILT_DIRT =
            BLOCKS.register("moist_silt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_SILT_DIRT =
            BLOCKS.register("wet_silt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SILT_DIRT =
            BLOCKS.register("soaked_silt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SILT_DIRT =
            BLOCKS.register("saturated_silt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // regions_unexplored:volcanic_ash
    public static final RegistryObject<WettableFallingBlock> MOIST_VOLCANIC_ASH =
            BLOCKS.register("moist_volcanic_ash",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> WET_VOLCANIC_ASH =
            BLOCKS.register("wet_volcanic_ash",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_VOLCANIC_ASH =
            BLOCKS.register("soaked_volcanic_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_VOLCANIC_ASH =
            BLOCKS.register("saturated_volcanic_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.STONE)));

    // ── blue_skies ─────────────────────────────────────────
    // blue_skies:crystal_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_CRYSTAL_SAND =
            BLOCKS.register("moist_crystal_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableFallingBlock> WET_CRYSTAL_SAND =
            BLOCKS.register("wet_crystal_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_CRYSTAL_SAND =
            BLOCKS.register("soaked_crystal_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_CRYSTAL_SAND =
            BLOCKS.register("saturated_crystal_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.DIAMOND)));

    // blue_skies:midnight_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_MIDNIGHT_SAND =
            BLOCKS.register("moist_midnight_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> WET_MIDNIGHT_SAND =
            BLOCKS.register("wet_midnight_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_MIDNIGHT_SAND =
            BLOCKS.register("soaked_midnight_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_BLACK)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_MIDNIGHT_SAND =
            BLOCKS.register("saturated_midnight_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_BLACK)));

    // blue_skies:lunar_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_LUNAR_DIRT =
            BLOCKS.register("moist_lunar_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_LUNAR_DIRT =
            BLOCKS.register("wet_lunar_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_LUNAR_DIRT =
            BLOCKS.register("soaked_lunar_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_LUNAR_DIRT =
            BLOCKS.register("saturated_lunar_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // blue_skies:turquoise_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_TURQUOISE_DIRT =
            BLOCKS.register("moist_turquoise_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_TURQUOISE_DIRT =
            BLOCKS.register("wet_turquoise_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_TURQUOISE_DIRT =
            BLOCKS.register("soaked_turquoise_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_TURQUOISE_DIRT =
            BLOCKS.register("saturated_turquoise_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // ── caverns_and_chasms ─────────────────────────────────────────
    // caverns_and_chasms:rocky_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_ROCKY_DIRT =
            BLOCKS.register("moist_rocky_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_ROCKY_DIRT =
            BLOCKS.register("wet_rocky_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_ROCKY_DIRT =
            BLOCKS.register("soaked_rocky_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_ROCKY_DIRT =
            BLOCKS.register("saturated_rocky_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));


    // ── iceandfire ──
    // iceandfire:ash
    public static final RegistryObject<WettableFallingBlock> MOIST_IAF_ASH =
            BLOCKS.register("moist_iaf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> WET_IAF_ASH =
            BLOCKS.register("wet_iaf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_IAF_ASH =
            BLOCKS.register("soaked_iaf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_IAF_ASH =
            BLOCKS.register("saturated_iaf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.STONE)));

    // iceandfire:chared_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_CHARED_DIRT =
            BLOCKS.register("moist_chared_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_CHARED_DIRT =
            BLOCKS.register("wet_chared_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_CHARED_DIRT =
            BLOCKS.register("soaked_chared_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_CHARED_DIRT =
            BLOCKS.register("saturated_chared_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // iceandfire:chared_gravel
    public static final RegistryObject<WettableFallingBlock> MOIST_CHARED_GRAVEL =
            BLOCKS.register("moist_chared_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> WET_CHARED_GRAVEL =
            BLOCKS.register("wet_chared_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_CHARED_GRAVEL =
            BLOCKS.register("soaked_chared_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_CHARED_GRAVEL =
            BLOCKS.register("saturated_chared_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.STONE)));

    // iceandfire:crackled_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_CRACKLED_DIRT =
            BLOCKS.register("moist_crackled_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_CRACKLED_DIRT =
            BLOCKS.register("wet_crackled_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_CRACKLED_DIRT =
            BLOCKS.register("soaked_crackled_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_CRACKLED_DIRT =
            BLOCKS.register("saturated_crackled_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // iceandfire:crackled_gravel
    public static final RegistryObject<WettableFallingBlock> MOIST_CRACKLED_GRAVEL =
            BLOCKS.register("moist_crackled_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> WET_CRACKLED_GRAVEL =
            BLOCKS.register("wet_crackled_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_CRACKLED_GRAVEL =
            BLOCKS.register("soaked_crackled_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_CRACKLED_GRAVEL =
            BLOCKS.register("saturated_crackled_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.STONE)));

    // iceandfire:frozen_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_FROZEN_DIRT =
            BLOCKS.register("moist_frozen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> WET_FROZEN_DIRT =
            BLOCKS.register("wet_frozen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_FROZEN_DIRT =
            BLOCKS.register("soaked_frozen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_FROZEN_DIRT =
            BLOCKS.register("saturated_frozen_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.SNOW)));

    // iceandfire:frozen_gravel
    public static final RegistryObject<WettableFallingBlock> MOIST_FROZEN_GRAVEL =
            BLOCKS.register("moist_frozen_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> WET_FROZEN_GRAVEL =
            BLOCKS.register("wet_frozen_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_FROZEN_GRAVEL =
            BLOCKS.register("soaked_frozen_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.SNOW)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_FROZEN_GRAVEL =
            BLOCKS.register("saturated_frozen_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.SNOW)));

    // iceandfire:graveyard_soil
    public static final RegistryObject<WettableSoilBlock> MOIST_GRAVEYARD_SOIL =
            BLOCKS.register("moist_graveyard_soil",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_GRAVEYARD_SOIL =
            BLOCKS.register("wet_graveyard_soil",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_GRAVEYARD_SOIL =
            BLOCKS.register("soaked_graveyard_soil",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_GRAVEYARD_SOIL =
            BLOCKS.register("saturated_graveyard_soil",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // ── sculkhorde ──
    // sculkhorde:infested_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_INFESTED_SAND =
            BLOCKS.register("moist_infested_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> WET_INFESTED_SAND =
            BLOCKS.register("wet_infested_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_INFESTED_SAND =
            BLOCKS.register("soaked_infested_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_INFESTED_SAND =
            BLOCKS.register("saturated_infested_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_GREEN)));

    // sculkhorde:infested_gravel
    public static final RegistryObject<WettableFallingBlock> MOIST_INFESTED_GRAVEL =
            BLOCKS.register("moist_infested_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> WET_INFESTED_GRAVEL =
            BLOCKS.register("wet_infested_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_INFESTED_GRAVEL =
            BLOCKS.register("soaked_infested_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_INFESTED_GRAVEL =
            BLOCKS.register("saturated_infested_gravel",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_GREEN)));

    // sculkhorde:infested_red_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_INFESTED_RED_SAND =
            BLOCKS.register("moist_infested_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> WET_INFESTED_RED_SAND =
            BLOCKS.register("wet_infested_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_INFESTED_RED_SAND =
            BLOCKS.register("soaked_infested_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_INFESTED_RED_SAND =
            BLOCKS.register("saturated_infested_red_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_GREEN)));

    // sculkhorde:infested_clay
    public static final RegistryObject<WettableSoilBlock> MOIST_INFESTED_CLAY =
            BLOCKS.register("moist_infested_clay",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> WET_INFESTED_CLAY =
            BLOCKS.register("wet_infested_clay",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_INFESTED_CLAY =
            BLOCKS.register("soaked_infested_clay",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_INFESTED_CLAY =
            BLOCKS.register("saturated_infested_clay",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GREEN)));

    // sculkhorde:infested_mud
    public static final RegistryObject<WettableSoilBlock> MOIST_INFESTED_MUD =
            BLOCKS.register("moist_infested_mud",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> WET_INFESTED_MUD =
            BLOCKS.register("wet_infested_mud",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_INFESTED_MUD =
            BLOCKS.register("soaked_infested_mud",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_INFESTED_MUD =
            BLOCKS.register("saturated_infested_mud",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GREEN)));

    // ── natures_spirit ──
    // natures_spirit:pink_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_PINK_SAND =
            BLOCKS.register("moist_pink_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_PINK)));
    public static final RegistryObject<WettableFallingBlock> WET_PINK_SAND =
            BLOCKS.register("wet_pink_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_PINK)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_PINK_SAND =
            BLOCKS.register("soaked_pink_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_PINK)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_PINK_SAND =
            BLOCKS.register("saturated_pink_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_PINK)));

    // natures_spirit:sandy_soil
    public static final RegistryObject<WettableSoilBlock> MOIST_SANDY_SOIL =
            BLOCKS.register("moist_sandy_soil",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.SAND)));
    public static final RegistryObject<WettableSoilBlock> WET_SANDY_SOIL =
            BLOCKS.register("wet_sandy_soil",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.SAND)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SANDY_SOIL =
            BLOCKS.register("soaked_sandy_soil",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.SAND)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SANDY_SOIL =
            BLOCKS.register("saturated_sandy_soil",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.SAND)));

    // ── witherstormmod ──
    // witherstormmod:tainted_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_TAINTED_SAND =
            BLOCKS.register("moist_tainted_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> WET_TAINTED_SAND =
            BLOCKS.register("wet_tainted_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_TAINTED_SAND =
            BLOCKS.register("soaked_tainted_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_TAINTED_SAND =
            BLOCKS.register("saturated_tainted_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_GRAY)));

    // witherstormmod:tainted_dust
    public static final RegistryObject<WettableFallingBlock> MOIST_TAINTED_DUST =
            BLOCKS.register("moist_tainted_dust",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> WET_TAINTED_DUST =
            BLOCKS.register("wet_tainted_dust",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_TAINTED_DUST =
            BLOCKS.register("soaked_tainted_dust",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_TAINTED_DUST =
            BLOCKS.register("saturated_tainted_dust",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_GRAY)));

    // witherstormmod:tainted_dust_block
    public static final RegistryObject<WettableFallingBlock> MOIST_TAINTED_DUST_BLOCK =
            BLOCKS.register("moist_tainted_dust_block",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> WET_TAINTED_DUST_BLOCK =
            BLOCKS.register("wet_tainted_dust_block",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_TAINTED_DUST_BLOCK =
            BLOCKS.register("soaked_tainted_dust_block",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_TAINTED_DUST_BLOCK =
            BLOCKS.register("saturated_tainted_dust_block",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.COLOR_GRAY)));

    // witherstormmod:tainted_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_TAINTED_DIRT =
            BLOCKS.register("moist_tainted_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableSoilBlock> WET_TAINTED_DIRT =
            BLOCKS.register("wet_tainted_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_TAINTED_DIRT =
            BLOCKS.register("soaked_tainted_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GRAY)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_TAINTED_DIRT =
            BLOCKS.register("saturated_tainted_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GRAY)));

    // ── tconstruct ──
    // tconstruct:earth_slime_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_EARTH_SLIME_DIRT =
            BLOCKS.register("moist_earth_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> WET_EARTH_SLIME_DIRT =
            BLOCKS.register("wet_earth_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_EARTH_SLIME_DIRT =
            BLOCKS.register("soaked_earth_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_EARTH_SLIME_DIRT =
            BLOCKS.register("saturated_earth_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GREEN)));

    // tconstruct:sky_slime_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_SKY_SLIME_DIRT =
            BLOCKS.register("moist_sky_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableSoilBlock> WET_SKY_SLIME_DIRT =
            BLOCKS.register("wet_sky_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SKY_SLIME_DIRT =
            BLOCKS.register("soaked_sky_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SKY_SLIME_DIRT =
            BLOCKS.register("saturated_sky_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIAMOND)));

    // tconstruct:ender_slime_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_ENDER_SLIME_DIRT =
            BLOCKS.register("moist_ender_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> WET_ENDER_SLIME_DIRT =
            BLOCKS.register("wet_ender_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_ENDER_SLIME_DIRT =
            BLOCKS.register("soaked_ender_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_ENDER_SLIME_DIRT =
            BLOCKS.register("saturated_ender_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_PURPLE)));

    // tconstruct:ichor_slime_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_ICHOR_SLIME_DIRT =
            BLOCKS.register("moist_ichor_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableSoilBlock> WET_ICHOR_SLIME_DIRT =
            BLOCKS.register("wet_ichor_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_ICHOR_SLIME_DIRT =
            BLOCKS.register("soaked_ichor_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_ICHOR_SLIME_DIRT =
            BLOCKS.register("saturated_ichor_slime_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_ORANGE)));

    // tconstruct:earth_congealed_slime
    public static final RegistryObject<WettableSoilBlock> MOIST_EARTH_CONGEALED_SLIME =
            BLOCKS.register("moist_earth_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> WET_EARTH_CONGEALED_SLIME =
            BLOCKS.register("wet_earth_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_EARTH_CONGEALED_SLIME =
            BLOCKS.register("soaked_earth_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_EARTH_CONGEALED_SLIME =
            BLOCKS.register("saturated_earth_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GREEN)));

    // tconstruct:sky_congealed_slime
    public static final RegistryObject<WettableSoilBlock> MOIST_SKY_CONGEALED_SLIME =
            BLOCKS.register("moist_sky_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableSoilBlock> WET_SKY_CONGEALED_SLIME =
            BLOCKS.register("wet_sky_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SKY_CONGEALED_SLIME =
            BLOCKS.register("soaked_sky_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIAMOND)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SKY_CONGEALED_SLIME =
            BLOCKS.register("saturated_sky_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIAMOND)));

    // tconstruct:ender_congealed_slime
    public static final RegistryObject<WettableSoilBlock> MOIST_ENDER_CONGEALED_SLIME =
            BLOCKS.register("moist_ender_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> WET_ENDER_CONGEALED_SLIME =
            BLOCKS.register("wet_ender_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_ENDER_CONGEALED_SLIME =
            BLOCKS.register("soaked_ender_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_ENDER_CONGEALED_SLIME =
            BLOCKS.register("saturated_ender_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_PURPLE)));

    // tconstruct:ichor_congealed_slime
    public static final RegistryObject<WettableSoilBlock> MOIST_ICHOR_CONGEALED_SLIME =
            BLOCKS.register("moist_ichor_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableSoilBlock> WET_ICHOR_CONGEALED_SLIME =
            BLOCKS.register("wet_ichor_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_ICHOR_CONGEALED_SLIME =
            BLOCKS.register("soaked_ichor_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_ICHOR_CONGEALED_SLIME =
            BLOCKS.register("saturated_ichor_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_ORANGE)));

    // tconstruct:blood_congealed_slime
    public static final RegistryObject<WettableSoilBlock> MOIST_BLOOD_CONGEALED_SLIME =
            BLOCKS.register("moist_blood_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_RED)));
    public static final RegistryObject<WettableSoilBlock> WET_BLOOD_CONGEALED_SLIME =
            BLOCKS.register("wet_blood_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_RED)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_BLOOD_CONGEALED_SLIME =
            BLOCKS.register("soaked_blood_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_RED)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_BLOOD_CONGEALED_SLIME =
            BLOCKS.register("saturated_blood_congealed_slime",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_RED)));

    // ── prehistoricfauna ──
    // prehistoricfauna:ash
    public static final RegistryObject<WettableFallingBlock> MOIST_PF_ASH =
            BLOCKS.register("moist_pf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> WET_PF_ASH =
            BLOCKS.register("wet_pf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_PF_ASH =
            BLOCKS.register("soaked_pf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_PF_ASH =
            BLOCKS.register("saturated_pf_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.STONE)));

    // prehistoricfauna:silt
    public static final RegistryObject<WettableFallingBlock> MOIST_PF_SILT =
            BLOCKS.register("moist_pf_silt",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> WET_PF_SILT =
            BLOCKS.register("wet_pf_silt",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_PF_SILT =
            BLOCKS.register("soaked_pf_silt",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_PF_SILT =
            BLOCKS.register("saturated_pf_silt",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.DIRT)));

    // prehistoricfauna:loam
    public static final RegistryObject<WettableSoilBlock> MOIST_PF_LOAM =
            BLOCKS.register("moist_pf_loam",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_PF_LOAM =
            BLOCKS.register("wet_pf_loam",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_PF_LOAM =
            BLOCKS.register("soaked_pf_loam",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_PF_LOAM =
            BLOCKS.register("saturated_pf_loam",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // prehistoricfauna:hardened_silt
    public static final RegistryObject<WettableSoilBlock> MOIST_HARDENED_SILT =
            BLOCKS.register("moist_hardened_silt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_HARDENED_SILT =
            BLOCKS.register("wet_hardened_silt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_HARDENED_SILT =
            BLOCKS.register("soaked_hardened_silt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_HARDENED_SILT =
            BLOCKS.register("saturated_hardened_silt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // prehistoricfauna:chalk_regolith
    public static final RegistryObject<WettableSoilBlock> MOIST_CHALK_REGOLITH =
            BLOCKS.register("moist_chalk_regolith",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.SAND)));
    public static final RegistryObject<WettableSoilBlock> WET_CHALK_REGOLITH =
            BLOCKS.register("wet_chalk_regolith",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.SAND)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_CHALK_REGOLITH =
            BLOCKS.register("soaked_chalk_regolith",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.SAND)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_CHALK_REGOLITH =
            BLOCKS.register("saturated_chalk_regolith",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.SAND)));

    // prehistoricfauna:siltstone_regolith
    public static final RegistryObject<WettableFallingBlock> MOIST_SILTSTONE_REGOLITH =
            BLOCKS.register("moist_siltstone_regolith",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> WET_SILTSTONE_REGOLITH =
            BLOCKS.register("wet_siltstone_regolith",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_SILTSTONE_REGOLITH =
            BLOCKS.register("soaked_siltstone_regolith",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_SILTSTONE_REGOLITH =
            BLOCKS.register("saturated_siltstone_regolith",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.DIRT)));

    // ── unusual_prehistory ──
    // unusual_prehistory:mossy_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_UP_MOSSY_DIRT =
            BLOCKS.register("moist_up_mossy_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_UP_MOSSY_DIRT =
            BLOCKS.register("wet_up_mossy_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_UP_MOSSY_DIRT =
            BLOCKS.register("soaked_up_mossy_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_UP_MOSSY_DIRT =
            BLOCKS.register("saturated_up_mossy_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // ── marvelous_menagerie ──
    // marvelous_menagerie:mudstone
    public static final RegistryObject<WettableSoilBlock> MOIST_MM_MUDSTONE =
            BLOCKS.register("moist_mm_mudstone",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_MM_MUDSTONE =
            BLOCKS.register("wet_mm_mudstone",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_MM_MUDSTONE =
            BLOCKS.register("soaked_mm_mudstone",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_MM_MUDSTONE =
            BLOCKS.register("saturated_mm_mudstone",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // marvelous_menagerie:siltstone
    public static final RegistryObject<WettableFallingBlock> MOIST_MM_SILTSTONE =
            BLOCKS.register("moist_mm_siltstone",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> WET_MM_SILTSTONE =
            BLOCKS.register("wet_mm_siltstone",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_MM_SILTSTONE =
            BLOCKS.register("soaked_mm_siltstone",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.DIRT)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_MM_SILTSTONE =
            BLOCKS.register("saturated_mm_siltstone",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.DIRT)));

    // marvelous_menagerie:permafrost
    public static final RegistryObject<WettableSoilBlock> MOIST_MM_PERMAFROST =
            BLOCKS.register("moist_mm_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> WET_MM_PERMAFROST =
            BLOCKS.register("wet_mm_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_MM_PERMAFROST =
            BLOCKS.register("soaked_mm_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_MM_PERMAFROST =
            BLOCKS.register("saturated_mm_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.SNOW)));

    // ── blastfromthepast ──
    // blastfromthepast:permafrost
    public static final RegistryObject<WettableSoilBlock> MOIST_BFTP_PERMAFROST =
            BLOCKS.register("moist_bftp_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> WET_BFTP_PERMAFROST =
            BLOCKS.register("wet_bftp_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_BFTP_PERMAFROST =
            BLOCKS.register("soaked_bftp_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.SNOW)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_BFTP_PERMAFROST =
            BLOCKS.register("saturated_bftp_permafrost",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.SNOW)));

    // ── betternether ──
    // betternether:veined_sand
    public static final RegistryObject<WettableFallingBlock> MOIST_VEINED_SAND =
            BLOCKS.register("moist_veined_sand",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> WET_VEINED_SAND =
            BLOCKS.register("wet_veined_sand",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_VEINED_SAND =
            BLOCKS.register("soaked_veined_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.SAND)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_VEINED_SAND =
            BLOCKS.register("saturated_veined_sand",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.SAND)));

    // ── twilightforest ──
    // twilightforest:uberous_soil
    public static final RegistryObject<WettableSoilBlock> MOIST_UBEROUS_SOIL =
            BLOCKS.register("moist_uberous_soil",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_UBEROUS_SOIL =
            BLOCKS.register("wet_uberous_soil",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_UBEROUS_SOIL =
            BLOCKS.register("soaked_uberous_soil",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_UBEROUS_SOIL =
            BLOCKS.register("saturated_uberous_soil",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // ── burnt ──
    // burnt:burnt_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_BURNT_DIRT =
            BLOCKS.register("moist_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_BURNT_DIRT =
            BLOCKS.register("wet_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_BURNT_DIRT =
            BLOCKS.register("soaked_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_BURNT_DIRT =
            BLOCKS.register("saturated_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // burnt:semi_burnt_dirt
    public static final RegistryObject<WettableSoilBlock> MOIST_SEMI_BURNT_DIRT =
            BLOCKS.register("moist_semi_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_SEMI_BURNT_DIRT =
            BLOCKS.register("wet_semi_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SEMI_BURNT_DIRT =
            BLOCKS.register("soaked_semi_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SEMI_BURNT_DIRT =
            BLOCKS.register("saturated_semi_burnt_dirt",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // burnt:burnt_grass
    public static final RegistryObject<WettableSoilBlock> MOIST_BURNT_GRASS =
            BLOCKS.register("moist_burnt_grass",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> WET_BURNT_GRASS =
            BLOCKS.register("wet_burnt_grass",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_BURNT_GRASS =
            BLOCKS.register("soaked_burnt_grass",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.DIRT)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_BURNT_GRASS =
            BLOCKS.register("saturated_burnt_grass",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.DIRT)));

    // burnt:charred_mycelium
    public static final RegistryObject<WettableSoilBlock> MOIST_CHARRED_MYCELIUM =
            BLOCKS.register("moist_charred_mycelium",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> WET_CHARRED_MYCELIUM =
            BLOCKS.register("wet_charred_mycelium",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_CHARRED_MYCELIUM =
            BLOCKS.register("soaked_charred_mycelium",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_PURPLE)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_CHARRED_MYCELIUM =
            BLOCKS.register("saturated_charred_mycelium",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_PURPLE)));

    // burnt:burnt_moss
    public static final RegistryObject<WettableSoilBlock> MOIST_BURNT_MOSS =
            BLOCKS.register("moist_burnt_moss",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> WET_BURNT_MOSS =
            BLOCKS.register("wet_burnt_moss",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_BURNT_MOSS =
            BLOCKS.register("soaked_burnt_moss",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_BURNT_MOSS =
            BLOCKS.register("saturated_burnt_moss",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GREEN)));

    // burnt:smoldering_moss
    public static final RegistryObject<WettableSoilBlock> MOIST_SMOLDERING_MOSS =
            BLOCKS.register("moist_smoldering_moss",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> WET_SMOLDERING_MOSS =
            BLOCKS.register("wet_smoldering_moss",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SMOLDERING_MOSS =
            BLOCKS.register("soaked_smoldering_moss",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SMOLDERING_MOSS =
            BLOCKS.register("saturated_smoldering_moss",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GREEN)));

    // ── supplementaries ──
    // supplementaries:ash
    public static final RegistryObject<WettableFallingBlock> MOIST_SUPP_ASH =
            BLOCKS.register("moist_supp_ash",
                    () -> new WettableFallingBlock(WetnessLevel.MOIST, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> WET_SUPP_ASH =
            BLOCKS.register("wet_supp_ash",
                    () -> new WettableFallingBlock(WetnessLevel.WET, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SOAKED_SUPP_ASH =
            BLOCKS.register("soaked_supp_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SOAKED, sandProps(MapColor.STONE)));
    public static final RegistryObject<WettableFallingBlock> SATURATED_SUPP_ASH =
            BLOCKS.register("saturated_supp_ash",
                    () -> new WettableFallingBlock(WetnessLevel.SATURATED, sandProps(MapColor.STONE)));

    // supplementaries:raked_gravel
    public static final RegistryObject<WettableSoilBlock> MOIST_RAKED_GRAVEL =
            BLOCKS.register("moist_raked_gravel",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.STONE)));
    public static final RegistryObject<WettableSoilBlock> WET_RAKED_GRAVEL =
            BLOCKS.register("wet_raked_gravel",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.STONE)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_RAKED_GRAVEL =
            BLOCKS.register("soaked_raked_gravel",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.STONE)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_RAKED_GRAVEL =
            BLOCKS.register("saturated_raked_gravel",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.STONE)));

    // ── vanillabackport ──
    // vanillabackport:pale_moss_block
    public static final RegistryObject<WettableSoilBlock> MOIST_PALE_MOSS_BLOCK =
            BLOCKS.register("moist_pale_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_LIGHT_GRAY)));
    public static final RegistryObject<WettableSoilBlock> WET_PALE_MOSS_BLOCK =
            BLOCKS.register("wet_pale_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_LIGHT_GRAY)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_PALE_MOSS_BLOCK =
            BLOCKS.register("soaked_pale_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_LIGHT_GRAY)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_PALE_MOSS_BLOCK =
            BLOCKS.register("saturated_pale_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_LIGHT_GRAY)));

    // ── minecraft ──
    // minecraft:moss_block
    public static final RegistryObject<WettableSoilBlock> MOIST_MOSS_BLOCK =
            BLOCKS.register("moist_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> WET_MOSS_BLOCK =
            BLOCKS.register("wet_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_MOSS_BLOCK =
            BLOCKS.register("soaked_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_GREEN)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_MOSS_BLOCK =
            BLOCKS.register("saturated_moss_block",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_GREEN)));

    // minecraft:sculk
    public static final RegistryObject<WettableSoilBlock> MOIST_SCULK =
            BLOCKS.register("moist_sculk",
                    () -> new WettableSoilBlock(WetnessLevel.MOIST, dirtProps(MapColor.COLOR_BLUE)));
    public static final RegistryObject<WettableSoilBlock> WET_SCULK =
            BLOCKS.register("wet_sculk",
                    () -> new WettableSoilBlock(WetnessLevel.WET, dirtProps(MapColor.COLOR_BLUE)));
    public static final RegistryObject<WettableSoilBlock> SOAKED_SCULK =
            BLOCKS.register("soaked_sculk",
                    () -> new WettableSoilBlock(WetnessLevel.SOAKED, dirtProps(MapColor.COLOR_BLUE)));
    public static final RegistryObject<WettableSoilBlock> SATURATED_SCULK =
            BLOCKS.register("saturated_sculk",
                    () -> new WettableSoilBlock(WetnessLevel.SATURATED, dirtProps(MapColor.COLOR_BLUE)));

    // ── Registration wiring (mod-compat) ─────────────────────────────────

    /**
     * Wire all mod-compat hardcoded entries into WetSandRegistry.
     * Must be called after ForgeRegistries are frozen (i.e. in FMLCommonSetupEvent
     * via enqueueWork).
     */
    public static void registerModCompatEntries() {
        WetSandRegistry registry = WetSandRegistry.INSTANCE;

        // immersive_weathering
        if (ModList.get().isLoaded("immersive_weathering")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("immersive_weathering", "earthen_clay"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_EARTHEN_CLAY.get(), WET_EARTHEN_CLAY.get(),
                        SOAKED_EARTHEN_CLAY.get(), SATURATED_EARTHEN_CLAY.get());
        }
        if (ModList.get().isLoaded("immersive_weathering")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("immersive_weathering", "loam"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_LOAM.get(), WET_LOAM.get(),
                        SOAKED_LOAM.get(), SATURATED_LOAM.get());
        }
        if (ModList.get().isLoaded("immersive_weathering")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("immersive_weathering", "permafrost"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_PERMAFROST.get(), WET_PERMAFROST.get(),
                        SOAKED_PERMAFROST.get(), SATURATED_PERMAFROST.get());
        }
        if (ModList.get().isLoaded("immersive_weathering")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("immersive_weathering", "silt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SILT.get(), WET_SILT.get(),
                        SOAKED_SILT.get(), SATURATED_SILT.get());
        }
        // biomesoplenty
        if (ModList.get().isLoaded("biomesoplenty")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("biomesoplenty", "black_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_BLACK_SAND.get(), WET_BLACK_SAND.get(),
                        SOAKED_BLACK_SAND.get(), SATURATED_BLACK_SAND.get());
        }
        if (ModList.get().isLoaded("biomesoplenty")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("biomesoplenty", "orange_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ORANGE_SAND.get(), WET_ORANGE_SAND.get(),
                        SOAKED_ORANGE_SAND.get(), SATURATED_ORANGE_SAND.get());
        }
        if (ModList.get().isLoaded("biomesoplenty")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("biomesoplenty", "white_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_WHITE_SAND.get(), WET_WHITE_SAND.get(),
                        SOAKED_WHITE_SAND.get(), SATURATED_WHITE_SAND.get());
        }
        if (ModList.get().isLoaded("biomesoplenty")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("biomesoplenty", "mossy_black_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_MOSSY_BLACK_SAND.get(), WET_MOSSY_BLACK_SAND.get(),
                        SOAKED_MOSSY_BLACK_SAND.get(), SATURATED_MOSSY_BLACK_SAND.get());
        }
        // aether
        if (ModList.get().isLoaded("aether")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("aether", "aether_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_AETHER_DIRT.get(), WET_AETHER_DIRT.get(),
                        SOAKED_AETHER_DIRT.get(), SATURATED_AETHER_DIRT.get());
        }
        if (ModList.get().isLoaded("aether")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("aether", "quicksoil"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_QUICKSOIL.get(), WET_QUICKSOIL.get(),
                        SOAKED_QUICKSOIL.get(), SATURATED_QUICKSOIL.get());
        }
        // aether_redux
        if (ModList.get().isLoaded("aether_redux")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("aether_redux", "coarse_aether_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_COARSE_AETHER_DIRT.get(), WET_COARSE_AETHER_DIRT.get(),
                        SOAKED_COARSE_AETHER_DIRT.get(), SATURATED_COARSE_AETHER_DIRT.get());
        }
        if (ModList.get().isLoaded("aether_redux")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("aether_redux", "holysilt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_HOLYSILT.get(), WET_HOLYSILT.get(),
                        SOAKED_HOLYSILT.get(), SATURATED_HOLYSILT.get());
        }
        // regions_unexplored
        if (ModList.get().isLoaded("regions_unexplored")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("regions_unexplored", "ash"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ASH.get(), WET_ASH.get(),
                        SOAKED_ASH.get(), SATURATED_ASH.get());
        }
        if (ModList.get().isLoaded("regions_unexplored")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("regions_unexplored", "ashen_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ASHEN_DIRT.get(), WET_ASHEN_DIRT.get(),
                        SOAKED_ASHEN_DIRT.get(), SATURATED_ASHEN_DIRT.get());
        }
        if (ModList.get().isLoaded("regions_unexplored")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("regions_unexplored", "peat_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_PEAT_DIRT.get(), WET_PEAT_DIRT.get(),
                        SOAKED_PEAT_DIRT.get(), SATURATED_PEAT_DIRT.get());
        }
        if (ModList.get().isLoaded("regions_unexplored")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("regions_unexplored", "silt_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SILT_DIRT.get(), WET_SILT_DIRT.get(),
                        SOAKED_SILT_DIRT.get(), SATURATED_SILT_DIRT.get());
        }
        if (ModList.get().isLoaded("regions_unexplored")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("regions_unexplored", "volcanic_ash"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_VOLCANIC_ASH.get(), WET_VOLCANIC_ASH.get(),
                        SOAKED_VOLCANIC_ASH.get(), SATURATED_VOLCANIC_ASH.get());
        }
        // blue_skies
        if (ModList.get().isLoaded("blue_skies")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("blue_skies", "crystal_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_CRYSTAL_SAND.get(), WET_CRYSTAL_SAND.get(),
                        SOAKED_CRYSTAL_SAND.get(), SATURATED_CRYSTAL_SAND.get());
        }
        if (ModList.get().isLoaded("blue_skies")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("blue_skies", "midnight_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_MIDNIGHT_SAND.get(), WET_MIDNIGHT_SAND.get(),
                        SOAKED_MIDNIGHT_SAND.get(), SATURATED_MIDNIGHT_SAND.get());
        }
        if (ModList.get().isLoaded("blue_skies")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("blue_skies", "lunar_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_LUNAR_DIRT.get(), WET_LUNAR_DIRT.get(),
                        SOAKED_LUNAR_DIRT.get(), SATURATED_LUNAR_DIRT.get());
        }
        if (ModList.get().isLoaded("blue_skies")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("blue_skies", "turquoise_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_TURQUOISE_DIRT.get(), WET_TURQUOISE_DIRT.get(),
                        SOAKED_TURQUOISE_DIRT.get(), SATURATED_TURQUOISE_DIRT.get());
        }
        // caverns_and_chasms
        if (ModList.get().isLoaded("caverns_and_chasms")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("caverns_and_chasms", "rocky_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ROCKY_DIRT.get(), WET_ROCKY_DIRT.get(),
                        SOAKED_ROCKY_DIRT.get(), SATURATED_ROCKY_DIRT.get());
        }
        // iceandfire
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "ash"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_IAF_ASH.get(), WET_IAF_ASH.get(), SOAKED_IAF_ASH.get(), SATURATED_IAF_ASH.get());
        }
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "chared_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_CHARED_DIRT.get(), WET_CHARED_DIRT.get(), SOAKED_CHARED_DIRT.get(), SATURATED_CHARED_DIRT.get());
        }
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "chared_gravel"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_CHARED_GRAVEL.get(), WET_CHARED_GRAVEL.get(), SOAKED_CHARED_GRAVEL.get(), SATURATED_CHARED_GRAVEL.get());
        }
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "crackled_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_CRACKLED_DIRT.get(), WET_CRACKLED_DIRT.get(), SOAKED_CRACKLED_DIRT.get(), SATURATED_CRACKLED_DIRT.get());
        }
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "crackled_gravel"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_CRACKLED_GRAVEL.get(), WET_CRACKLED_GRAVEL.get(), SOAKED_CRACKLED_GRAVEL.get(), SATURATED_CRACKLED_GRAVEL.get());
        }
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "frozen_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_FROZEN_DIRT.get(), WET_FROZEN_DIRT.get(), SOAKED_FROZEN_DIRT.get(), SATURATED_FROZEN_DIRT.get());
        }
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "frozen_gravel"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_FROZEN_GRAVEL.get(), WET_FROZEN_GRAVEL.get(), SOAKED_FROZEN_GRAVEL.get(), SATURATED_FROZEN_GRAVEL.get());
        }
        if (ModList.get().isLoaded("iceandfire")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("iceandfire", "graveyard_soil"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_GRAVEYARD_SOIL.get(), WET_GRAVEYARD_SOIL.get(), SOAKED_GRAVEYARD_SOIL.get(), SATURATED_GRAVEYARD_SOIL.get());
        }
        // sculkhorde
        if (ModList.get().isLoaded("sculkhorde")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("sculkhorde", "infested_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_INFESTED_SAND.get(), WET_INFESTED_SAND.get(), SOAKED_INFESTED_SAND.get(), SATURATED_INFESTED_SAND.get());
        }
        if (ModList.get().isLoaded("sculkhorde")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("sculkhorde", "infested_gravel"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_INFESTED_GRAVEL.get(), WET_INFESTED_GRAVEL.get(), SOAKED_INFESTED_GRAVEL.get(), SATURATED_INFESTED_GRAVEL.get());
        }
        if (ModList.get().isLoaded("sculkhorde")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("sculkhorde", "infested_red_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_INFESTED_RED_SAND.get(), WET_INFESTED_RED_SAND.get(), SOAKED_INFESTED_RED_SAND.get(), SATURATED_INFESTED_RED_SAND.get());
        }
        if (ModList.get().isLoaded("sculkhorde")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("sculkhorde", "infested_clay"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_INFESTED_CLAY.get(), WET_INFESTED_CLAY.get(), SOAKED_INFESTED_CLAY.get(), SATURATED_INFESTED_CLAY.get());
        }
        if (ModList.get().isLoaded("sculkhorde")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("sculkhorde", "infested_mud"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_INFESTED_MUD.get(), WET_INFESTED_MUD.get(), SOAKED_INFESTED_MUD.get(), SATURATED_INFESTED_MUD.get());
        }
        // natures_spirit
        if (ModList.get().isLoaded("natures_spirit")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("natures_spirit", "pink_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_PINK_SAND.get(), WET_PINK_SAND.get(), SOAKED_PINK_SAND.get(), SATURATED_PINK_SAND.get());
        }
        if (ModList.get().isLoaded("natures_spirit")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("natures_spirit", "sandy_soil"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SANDY_SOIL.get(), WET_SANDY_SOIL.get(), SOAKED_SANDY_SOIL.get(), SATURATED_SANDY_SOIL.get());
        }
        // witherstormmod
        if (ModList.get().isLoaded("witherstormmod")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("witherstormmod", "tainted_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_TAINTED_SAND.get(), WET_TAINTED_SAND.get(), SOAKED_TAINTED_SAND.get(), SATURATED_TAINTED_SAND.get());
        }
        if (ModList.get().isLoaded("witherstormmod")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("witherstormmod", "tainted_dust"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_TAINTED_DUST.get(), WET_TAINTED_DUST.get(), SOAKED_TAINTED_DUST.get(), SATURATED_TAINTED_DUST.get());
        }
        if (ModList.get().isLoaded("witherstormmod")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("witherstormmod", "tainted_dust_block"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_TAINTED_DUST_BLOCK.get(), WET_TAINTED_DUST_BLOCK.get(), SOAKED_TAINTED_DUST_BLOCK.get(), SATURATED_TAINTED_DUST_BLOCK.get());
        }
        if (ModList.get().isLoaded("witherstormmod")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("witherstormmod", "tainted_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_TAINTED_DIRT.get(), WET_TAINTED_DIRT.get(), SOAKED_TAINTED_DIRT.get(), SATURATED_TAINTED_DIRT.get());
        }
        // tconstruct
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "earth_slime_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_EARTH_SLIME_DIRT.get(), WET_EARTH_SLIME_DIRT.get(), SOAKED_EARTH_SLIME_DIRT.get(), SATURATED_EARTH_SLIME_DIRT.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "sky_slime_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SKY_SLIME_DIRT.get(), WET_SKY_SLIME_DIRT.get(), SOAKED_SKY_SLIME_DIRT.get(), SATURATED_SKY_SLIME_DIRT.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "ender_slime_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ENDER_SLIME_DIRT.get(), WET_ENDER_SLIME_DIRT.get(), SOAKED_ENDER_SLIME_DIRT.get(), SATURATED_ENDER_SLIME_DIRT.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "ichor_slime_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ICHOR_SLIME_DIRT.get(), WET_ICHOR_SLIME_DIRT.get(), SOAKED_ICHOR_SLIME_DIRT.get(), SATURATED_ICHOR_SLIME_DIRT.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "earth_congealed_slime"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_EARTH_CONGEALED_SLIME.get(), WET_EARTH_CONGEALED_SLIME.get(), SOAKED_EARTH_CONGEALED_SLIME.get(), SATURATED_EARTH_CONGEALED_SLIME.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "sky_congealed_slime"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SKY_CONGEALED_SLIME.get(), WET_SKY_CONGEALED_SLIME.get(), SOAKED_SKY_CONGEALED_SLIME.get(), SATURATED_SKY_CONGEALED_SLIME.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "ender_congealed_slime"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ENDER_CONGEALED_SLIME.get(), WET_ENDER_CONGEALED_SLIME.get(), SOAKED_ENDER_CONGEALED_SLIME.get(), SATURATED_ENDER_CONGEALED_SLIME.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "ichor_congealed_slime"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_ICHOR_CONGEALED_SLIME.get(), WET_ICHOR_CONGEALED_SLIME.get(), SOAKED_ICHOR_CONGEALED_SLIME.get(), SATURATED_ICHOR_CONGEALED_SLIME.get());
        }
        if (ModList.get().isLoaded("tconstruct")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tconstruct", "blood_congealed_slime"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_BLOOD_CONGEALED_SLIME.get(), WET_BLOOD_CONGEALED_SLIME.get(), SOAKED_BLOOD_CONGEALED_SLIME.get(), SATURATED_BLOOD_CONGEALED_SLIME.get());
        }
        // prehistoricfauna
        if (ModList.get().isLoaded("prehistoricfauna")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prehistoricfauna", "ash"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_PF_ASH.get(), WET_PF_ASH.get(), SOAKED_PF_ASH.get(), SATURATED_PF_ASH.get());
        }
        if (ModList.get().isLoaded("prehistoricfauna")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prehistoricfauna", "silt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_PF_SILT.get(), WET_PF_SILT.get(), SOAKED_PF_SILT.get(), SATURATED_PF_SILT.get());
        }
        if (ModList.get().isLoaded("prehistoricfauna")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prehistoricfauna", "loam"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_PF_LOAM.get(), WET_PF_LOAM.get(), SOAKED_PF_LOAM.get(), SATURATED_PF_LOAM.get());
        }
        if (ModList.get().isLoaded("prehistoricfauna")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prehistoricfauna", "hardened_silt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_HARDENED_SILT.get(), WET_HARDENED_SILT.get(), SOAKED_HARDENED_SILT.get(), SATURATED_HARDENED_SILT.get());
        }
        if (ModList.get().isLoaded("prehistoricfauna")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prehistoricfauna", "chalk_regolith"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_CHALK_REGOLITH.get(), WET_CHALK_REGOLITH.get(), SOAKED_CHALK_REGOLITH.get(), SATURATED_CHALK_REGOLITH.get());
        }
        if (ModList.get().isLoaded("prehistoricfauna")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prehistoricfauna", "siltstone_regolith"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SILTSTONE_REGOLITH.get(), WET_SILTSTONE_REGOLITH.get(), SOAKED_SILTSTONE_REGOLITH.get(), SATURATED_SILTSTONE_REGOLITH.get());
        }
        // unusual_prehistory
        if (ModList.get().isLoaded("unusual_prehistory")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("unusual_prehistory", "mossy_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_UP_MOSSY_DIRT.get(), WET_UP_MOSSY_DIRT.get(), SOAKED_UP_MOSSY_DIRT.get(), SATURATED_UP_MOSSY_DIRT.get());
        }
        // marvelous_menagerie
        if (ModList.get().isLoaded("marvelous_menagerie")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("marvelous_menagerie", "mudstone"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_MM_MUDSTONE.get(), WET_MM_MUDSTONE.get(), SOAKED_MM_MUDSTONE.get(), SATURATED_MM_MUDSTONE.get());
        }
        if (ModList.get().isLoaded("marvelous_menagerie")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("marvelous_menagerie", "siltstone"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_MM_SILTSTONE.get(), WET_MM_SILTSTONE.get(), SOAKED_MM_SILTSTONE.get(), SATURATED_MM_SILTSTONE.get());
        }
        if (ModList.get().isLoaded("marvelous_menagerie")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("marvelous_menagerie", "permafrost"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_MM_PERMAFROST.get(), WET_MM_PERMAFROST.get(), SOAKED_MM_PERMAFROST.get(), SATURATED_MM_PERMAFROST.get());
        }
        // blastfromthepast
        if (ModList.get().isLoaded("blastfromthepast")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("blastfromthepast", "permafrost"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_BFTP_PERMAFROST.get(), WET_BFTP_PERMAFROST.get(), SOAKED_BFTP_PERMAFROST.get(), SATURATED_BFTP_PERMAFROST.get());
        }
        // betternether
        if (ModList.get().isLoaded("betternether")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("betternether", "veined_sand"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_VEINED_SAND.get(), WET_VEINED_SAND.get(), SOAKED_VEINED_SAND.get(), SATURATED_VEINED_SAND.get());
        }
        // twilightforest
        if (ModList.get().isLoaded("twilightforest")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("twilightforest", "uberous_soil"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_UBEROUS_SOIL.get(), WET_UBEROUS_SOIL.get(), SOAKED_UBEROUS_SOIL.get(), SATURATED_UBEROUS_SOIL.get());
        }
        // burnt
        if (ModList.get().isLoaded("burnt")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("burnt", "burnt_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_BURNT_DIRT.get(), WET_BURNT_DIRT.get(), SOAKED_BURNT_DIRT.get(), SATURATED_BURNT_DIRT.get());
        }
        if (ModList.get().isLoaded("burnt")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("burnt", "semi_burnt_dirt"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SEMI_BURNT_DIRT.get(), WET_SEMI_BURNT_DIRT.get(), SOAKED_SEMI_BURNT_DIRT.get(), SATURATED_SEMI_BURNT_DIRT.get());
        }
        if (ModList.get().isLoaded("burnt")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("burnt", "burnt_grass"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_BURNT_GRASS.get(), WET_BURNT_GRASS.get(), SOAKED_BURNT_GRASS.get(), SATURATED_BURNT_GRASS.get());
        }
        if (ModList.get().isLoaded("burnt")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("burnt", "charred_mycelium"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_CHARRED_MYCELIUM.get(), WET_CHARRED_MYCELIUM.get(), SOAKED_CHARRED_MYCELIUM.get(), SATURATED_CHARRED_MYCELIUM.get());
        }
        if (ModList.get().isLoaded("burnt")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("burnt", "burnt_moss"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_BURNT_MOSS.get(), WET_BURNT_MOSS.get(), SOAKED_BURNT_MOSS.get(), SATURATED_BURNT_MOSS.get());
        }
        if (ModList.get().isLoaded("burnt")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("burnt", "smoldering_moss"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SMOLDERING_MOSS.get(), WET_SMOLDERING_MOSS.get(), SOAKED_SMOLDERING_MOSS.get(), SATURATED_SMOLDERING_MOSS.get());
        }
        // supplementaries
        if (ModList.get().isLoaded("supplementaries")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("supplementaries", "ash"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_SUPP_ASH.get(), WET_SUPP_ASH.get(), SOAKED_SUPP_ASH.get(), SATURATED_SUPP_ASH.get());
        }
        if (ModList.get().isLoaded("supplementaries")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("supplementaries", "raked_gravel"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_RAKED_GRAVEL.get(), WET_RAKED_GRAVEL.get(), SOAKED_RAKED_GRAVEL.get(), SATURATED_RAKED_GRAVEL.get());
        }
        // vanillabackport
        if (ModList.get().isLoaded("vanillabackport")) {
            Block dry = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("vanillabackport", "pale_moss_block"));
            if (dry != null && dry != Blocks.AIR)
                registry.registerHardcoded(dry,
                        MOIST_PALE_MOSS_BLOCK.get(), WET_PALE_MOSS_BLOCK.get(), SOAKED_PALE_MOSS_BLOCK.get(), SATURATED_PALE_MOSS_BLOCK.get());
        }
        // minecraft
        registry.registerHardcoded(Blocks.MOSS_BLOCK,
                MOIST_MOSS_BLOCK.get(), WET_MOSS_BLOCK.get(), SOAKED_MOSS_BLOCK.get(), SATURATED_MOSS_BLOCK.get());
        registry.registerHardcoded(Blocks.SCULK,
                MOIST_SCULK.get(), WET_SCULK.get(), SOAKED_SCULK.get(), SATURATED_SCULK.get());
    }
}
