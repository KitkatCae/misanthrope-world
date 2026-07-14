package exp.CCnewmods.misanthrope_world.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates mixins whose targets belong to optional soft dependencies.
 * <p>
 * Farmers' Delight, Pizza Delight, and Supplementaries are all optional at
 * runtime (see mods.toml). Mixin resolves each mixin's target class eagerly
 * when the config is processed, so simply marking those mods optional in
 * mods.toml is NOT enough — if the mod is absent, Mixin will still try to
 * load e.g. vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity
 * and throw a ClassNotFoundError, crashing the game.
 * <p>
 * This plugin tells Mixin to skip those specific mixins entirely when the
 * owning mod isn't present, via {@link #shouldApplyMixin}, which runs BEFORE
 * Mixin attempts to resolve/transform the target class.
 */
public class MisWorldMixinPlugin implements IMixinConfigPlugin {

    // Fully-qualified mixin class names gated behind an optional mod.
    private static final String FD_COOKING_POT_TICK =
            "exp.CCnewmods.misanthrope_world.mixin.farmersdelight.CookingPotTickMixin";
    private static final String FD_HEATABLE_BE =
            "exp.CCnewmods.misanthrope_world.mixin.farmersdelight.HeatableBlockEntityMixin";
    // Lives in the "farmersdelight" mixin package but targets pizzadelight's
    // RawPizzaBlock — gated on pizzadelight, not farmersdelight.
    private static final String PIZZADELIGHT_RAW_PIZZA =
            "exp.CCnewmods.misanthrope_world.mixin.farmersdelight.RawPizzaBlockMixin";
    private static final String SUPPLEMENTARIES_BELLOWS =
            "exp.CCnewmods.misanthrope_world.mixin.supplementaries.BellowsBlockTileMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        switch (mixinClassName) {
            case FD_COOKING_POT_TICK:
            case FD_HEATABLE_BE:
                return isModPresent("farmersdelight");
            case PIZZADELIGHT_RAW_PIZZA:
                return isModPresent("pizzadelight");
            case SUPPLEMENTARIES_BELLOWS:
                return isModPresent("supplementaries");
            default:
                return true;
        }
    }

    /**
     * Mixin's PREPARE phase (where shouldApplyMixin runs) happens during early
     * game bootstrap, well before FML has constructed mods and populated the
     * runtime {@code net.minecraftforge.fml.ModList} — calling
     * {@code ModList.get()} here returns null and throws.
     * <p>
     * {@link LoadingModList} is populated during mod file discovery/scanning,
     * which happens earlier than mixin preparation, so it's safe to query here.
     */
    private static boolean isModPresent(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
