package exp.CCnewmods.misanthrope_world.charcoal_pit;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registration for all Charcoal Pit / Log Splitting blocks, items, and BEs.
 *
 * <h3>Wiring</h3>
 * Call {@link #register(IEventBus)} from the mod constructor with the mod bus.
 * Also register {@link LogPileInteractionHandler} on the Forge bus — it uses
 * {@code @Mod.EventBusSubscriber} so it self-registers automatically.
 * <p>
 * In {@code MisanthropeClient.onClientSetup}, register the block entity renderer:
 * <pre>
 *   BlockEntityRenderers.register(
 *       CharcoalPitRegistration.LOG_PILE_BE.get(),
 *       LogPileBlockEntityRenderer::new);
 * </pre>
 * <p>
 * Also register the model location in {@code MisanthropeClient.onRegisterAdditionalModels}:
 * <pre>
 *   event.register(CharcoalPitRegistration.LOG_PILE_MODEL_LOC);
 * </pre>
 */
public final class CharcoalPitRegistration {

    private CharcoalPitRegistration() {
    }

    // -----------------------------------------------------------------------
    // Model location constant
    // -----------------------------------------------------------------------

    /**
     * ResourceLocation of the log pile template model.
     */
    public static final ResourceLocation LOG_PILE_MODEL_LOC =
            new ResourceLocation("misanthrope_world",
                    "block/template/log_pile/log_pile");

    // -----------------------------------------------------------------------
    // Registers
    // -----------------------------------------------------------------------

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Misanthrope_world.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Misanthrope_world.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Misanthrope_world.MODID);

    // -----------------------------------------------------------------------
    // Blocks
    // -----------------------------------------------------------------------

    public static final RegistryObject<LogPileBlock> LOG_PILE =
            BLOCKS.register("log_pile", () -> new LogPileBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f, 2.0f)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
            ));

    public static final RegistryObject<CharcoalLayerBlock> CHARCOAL_LAYER =
            BLOCKS.register("charcoal_layer", () -> new CharcoalLayerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(0.4f)
                            .sound(SoundType.GRAVEL)
                            .noOcclusion()
            ));

    // -----------------------------------------------------------------------
    // Items
    // -----------------------------------------------------------------------

    public static final RegistryObject<Item> LOG_PILE_ITEM =
            ITEMS.register("log_pile", () -> new BlockItem(LOG_PILE.get(), new Item.Properties()) {
                @Override
                @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
                public void initializeClient(
                        java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
                    consumer.accept(LogPileRenderer.CLIENT_EXTENSIONS);
                }
            });

    public static final RegistryObject<Item> CHARCOAL_LAYER_ITEM =
            ITEMS.register("charcoal_layer", () ->
                    new BlockItem(CHARCOAL_LAYER.get(), new Item.Properties()));

    /**
     * The single CutWoodItem class used for ALL wood types.
     * Wood type is stored as NBT — stacks only merge if WoodType matches.
     */
    public static final RegistryObject<CutWoodItem> CUT_WOOD_ITEM =
            ITEMS.register("cut_wood", () ->
                    new CutWoodItem(new Item.Properties()));

    // -----------------------------------------------------------------------
    // Block entities
    // -----------------------------------------------------------------------

    /**
     * Block entity for the interactive log pile (stores slot contents).
     * Replaces the burn-only BE from the charcoal pit — the log pile now
     * has ONE BE type that handles both storage and burning.
     */
    public static final RegistryObject<BlockEntityType<LogPileBlockEntity>> LOG_PILE_BE =
            BLOCK_ENTITIES.register("log_pile", () ->
                    BlockEntityType.Builder
                            .of(LogPileBlockEntity::new, LOG_PILE.get())
                            .build(null));

    // -----------------------------------------------------------------------
    // Registration entry point
    // -----------------------------------------------------------------------

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
