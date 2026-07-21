package exp.CCnewmods.misanthrope_world.drying;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

public class EnvironmentalDryingRecipeSerializer
        implements RecipeSerializer<EnvironmentalDryingRecipe> {

    public static final EnvironmentalDryingRecipeSerializer INSTANCE =
            new EnvironmentalDryingRecipeSerializer();

    private EnvironmentalDryingRecipeSerializer() {
    }

    @Override
    public EnvironmentalDryingRecipe fromJson(ResourceLocation id, JsonObject j) {
        Ingredient ingredient = Ingredient.fromJson(j.get("ingredient"));
        ItemStack result = ShapedRecipe.itemStackFromJson(j.getAsJsonObject("result"));
        int baseTicks = j.has("base_ticks") ? j.get("base_ticks").getAsInt() : 1200;
        double minTemp = j.has("min_temp_celsius") ? j.get("min_temp_celsius").getAsDouble() : -999.0;
        double maxHumidity = j.has("max_humidity_mbar") ? j.get("max_humidity_mbar").getAsDouble() : 999.0;
        boolean airflow = j.has("requires_airflow") && j.get("requires_airflow").getAsBoolean();
        boolean openAir = j.has("open_air_allowed") && j.get("open_air_allowed").getAsBoolean();
        double speedMult = j.has("rack_speed_multiplier") ? j.get("rack_speed_multiplier").getAsDouble() : 1.0;
        return new EnvironmentalDryingRecipe(id, ingredient, result, baseTicks,
                minTemp, maxHumidity, airflow, openAir, speedMult);
    }

    @Override
    public EnvironmentalDryingRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        Ingredient ingredient = Ingredient.fromNetwork(buf);
        ItemStack result = buf.readItem();
        int baseTicks = buf.readInt();
        double minTemp = buf.readDouble();
        double maxHumidity = buf.readDouble();
        boolean airflow = buf.readBoolean();
        boolean openAir = buf.readBoolean();
        double speedMult = buf.readDouble();
        return new EnvironmentalDryingRecipe(id, ingredient, result, baseTicks,
                minTemp, maxHumidity, airflow, openAir, speedMult);
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf, EnvironmentalDryingRecipe r) {
        r.getIngredient().toNetwork(buf);
        buf.writeItem(r.getResult());
        buf.writeInt(r.getBaseTicks());
        buf.writeDouble(r.getMinTempCelsius());
        buf.writeDouble(r.getMaxHumidityMbar());
        buf.writeBoolean(r.isRequiresAirflow());
        buf.writeBoolean(r.isOpenAirAllowed());
        buf.writeDouble(r.getRackSpeedMultiplier());
    }
}
