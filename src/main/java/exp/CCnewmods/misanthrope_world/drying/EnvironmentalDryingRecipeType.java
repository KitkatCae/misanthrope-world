package exp.CCnewmods.misanthrope_world.drying;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Recipe type registration for {@code misanthrope_world:environmental_drying}.
 *
 * <p>Wire up in {@code Misanthrope_core.java} inside the mod constructor:
 * <pre>{@code
 *   EnvironmentalDryingRecipeType.register(modEventBus);
 * }</pre>
 *
 * <p>This fully replaces {@code tinkers_thinking:drying_rack}. All TT drying
 * rack recipes should be overridden via datapack using this type. The BE mixin
 * {@code DryingRackBlockEntityMixin} queries this type's recipe list, not TT's.
 */
public class EnvironmentalDryingRecipeType implements RecipeType<EnvironmentalDryingRecipe> {

    public static final EnvironmentalDryingRecipeType INSTANCE =
            new EnvironmentalDryingRecipeType();
    public static final ResourceLocation ID =
            new ResourceLocation(Misanthrope_world.MODID, "environmental_drying");

    private static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, Misanthrope_world.MODID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, Misanthrope_world.MODID);

    @SuppressWarnings("unused")
    public static final RegistryObject<RecipeType<EnvironmentalDryingRecipe>> TYPE =
            RECIPE_TYPES.register("environmental_drying", () -> INSTANCE);

    @SuppressWarnings("unused")
    public static final RegistryObject<RecipeSerializer<EnvironmentalDryingRecipe>> SERIALIZER =
            RECIPE_SERIALIZERS.register("environmental_drying",
                    () -> EnvironmentalDryingRecipeSerializer.INSTANCE);

    private EnvironmentalDryingRecipeType() {
    }

    public static void register(IEventBus modEventBus) {
        RECIPE_TYPES.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
    }

    @Override
    public String toString() {
        return ID.toString();
    }
}
