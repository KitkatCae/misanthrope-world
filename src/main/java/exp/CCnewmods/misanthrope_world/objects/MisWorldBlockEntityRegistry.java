package exp.CCnewmods.misanthrope_world.objects;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.physics.collapse.LatticeCollapseBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class MisWorldBlockEntityRegistry {

    public static final DeferredRegister<BlockEntityType<?>> DEF_REG =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Misanthrope_world.MODID);

    public static final RegistryObject<BlockEntityType<LatticeCollapseBlockEntity>>
            LATTICE_COLLAPSE_BE = DEF_REG.register("lattice_collapse",
                () -> BlockEntityType.Builder
                        .of((pos, state) -> new LatticeCollapseBlockEntity(pos, state),
                                MisWorldBlocks.LATTICE_COLLAPSE_BLOCK.get())
                        .build(null));
}
