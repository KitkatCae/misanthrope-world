package exp.CCnewmods.misanthrope_world.objects;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.physics.collapse.LatticeCollapseBlock;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class MisWorldBlocks {

    public static final DeferredRegister<net.minecraft.world.level.block.Block> DEF_REG =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Misanthrope_world.MODID);

    public static final RegistryObject<LatticeCollapseBlock> LATTICE_COLLAPSE_BLOCK =
            DEF_REG.register("lattice_collapse",
                    () -> new LatticeCollapseBlock(LatticeCollapseBlock.PROPS));
}
