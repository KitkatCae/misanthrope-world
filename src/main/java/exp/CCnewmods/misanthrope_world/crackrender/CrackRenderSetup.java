package exp.CCnewmods.misanthrope_world.crackrender;

import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Entry point for the global crack render system.
 * Call CrackRenderSetup.commonSetup(event) from Misanthrope_core.commonSetup().
 * <p>
 * The mixin registrations happen automatically via misanthrope_core.mixins.json.
 * This class only handles runtime registration (network channel).
 */
public class CrackRenderSetup {

    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(CrackNetwork::register);
    }
}
