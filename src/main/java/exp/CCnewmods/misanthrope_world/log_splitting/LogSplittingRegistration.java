package exp.CCnewmods.misanthrope_world.log_splitting;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
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
 * Registration for the Log Splitting system.
 *
 * <h3>Wiring</h3>
 * Call {@link #register(IEventBus)} from the mod constructor.
 * <p>
 * In {@code MisanthropeClient.onClientSetup}:
 * <pre>
 *   BlockEntityRenderers.register(
 *       LogSplittingRegistration.LOG_SPLITTING_SLAB_BE.get(),
 *       LogSplittingSlabRenderer::new);
 * </pre>
 * <p>
 * {@link LogSplittingHandler} uses {@code @Mod.EventBusSubscriber} and
 * self-registers — no manual wiring needed.
 *
 * <h3>Note on the block item</h3>
 * {@code log_splitting_slab} has no {@link BlockItem} registered — it cannot
 * be obtained in the player's inventory. It only exists as a placed block,
 * created programmatically by {@link LogSplittingHandler}.
 */
public final class LogSplittingRegistration {

    private LogSplittingRegistration() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Misanthrope_world.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Misanthrope_world.MODID);

    // -----------------------------------------------------------------------
    // Block (no BlockItem — not obtainable in inventory)
    // -----------------------------------------------------------------------

    public static final RegistryObject<LogSplittingSlabBlock> LOG_SPLITTING_SLAB =
            BLOCKS.register("log_splitting_slab", () -> new LogSplittingSlabBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f, 2.0f)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
            ));

    // -----------------------------------------------------------------------
    // Block entity
    // -----------------------------------------------------------------------

    public static final RegistryObject<BlockEntityType<LogSplittingSlabBlockEntity>>
            LOG_SPLITTING_SLAB_BE = BLOCK_ENTITIES.register("log_splitting_slab", () ->
            BlockEntityType.Builder
                    .of(LogSplittingSlabBlockEntity::new,
                            LOG_SPLITTING_SLAB.get())
                    .build(null));

    // -----------------------------------------------------------------------
    // Registration entry point
    // -----------------------------------------------------------------------

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
