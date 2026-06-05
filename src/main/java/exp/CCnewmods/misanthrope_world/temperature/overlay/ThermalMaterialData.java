package exp.CCnewmods.misanthrope_world.temperature.overlay;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// ═══════════════════════════════════════════════════════════════════════════════
// ThermalMaterialData
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Defines temperature-driven visual overlay tints for a material type.
 * <p>
 * ── JSON format ───────────────────────────────────────────────────────────────
 * data/misanthrope_core/thermal_materials/<n>.json
 * <p>
 * {
 * "material_id": "misanthrope_core:clay",
 * "heat_stages": [
 * { "min_celsius": 50,   "tint_argb": "0x40FF4400" },
 * { "min_celsius": 200,  "tint_argb": "0x80FF6600" },
 * { "min_celsius": 600,  "tint_argb": "0xC0FF8800" },
 * { "min_celsius": 1000, "tint_argb": "0xFFFFCC44" }
 * ],
 * "cold_stages": [
 * { "min_celsius": -9999, "tint_argb": "0x8088CCFF" },
 * { "min_celsius": -10,   "tint_argb": "0x4044AAFF" }
 * ]
 * }
 * <p>
 * ── Tint format ───────────────────────────────────────────────────────────────
 * ARGB hex string: "0xAARRGGBB"
 * AA = alpha (00=transparent, FF=opaque)
 * RR = red, GG = green, BB = blue
 * <p>
 * The tint is applied as a colour multiply over the item's existing sprite.
 * Alpha controls intensity — use lower alpha for subtle heat shimmer,
 * higher alpha for clearly glowing items.
 * <p>
 * ── Stage matching ────────────────────────────────────────────────────────────
 * For heat stages: the LAST stage whose min_celsius <= item temperature applies.
 * For cold stages: the FIRST stage whose min_celsius <= item temperature applies.
 * Both lists should be sorted ascending by min_celsius.
 * <p>
 * ── Items declare their material ─────────────────────────────────────────────
 * Items are associated with a material via item tag:
 * #misanthrope_core:thermal_material/clay
 * #misanthrope_core:thermal_material/iron
 * #misanthrope_core:thermal_material/copper
 * #misanthrope_core:thermal_material/glass
 * etc.
 * <p>
 * The tag path after "thermal_material/" must match the material_id path.
 * A material_id of "misanthrope_core:clay" corresponds to tag
 * "#misanthrope_core:thermal_material/clay".
 */

public record ThermalMaterialData(
        ResourceLocation materialId,
        List<TintStage> heatStages,
        List<TintStage> coldStages
) {
    public record TintStage(double minCelsius, int tintArgb) {
    }

    /**
     * Get the ARGB tint for the given item temperature.
     * Returns 0 (fully transparent) if no stage applies.
     * <p>
     * The returned int is in ARGB format: 0xAARRGGBB
     */
    public int getTintForTemperature(double celsius) {
        // Heat: find last stage whose minCelsius <= celsius
        if (celsius > 0 && !heatStages.isEmpty()) {
            int result = 0;
            for (TintStage stage : heatStages) {
                if (celsius >= stage.minCelsius()) result = stage.tintArgb();
                else break;
            }
            if (result != 0) return result;
        }

        // Cold: find last stage whose minCelsius <= celsius (list sorted ascending)
        if (celsius < 0 && !coldStages.isEmpty()) {
            int result = 0;
            for (TintStage stage : coldStages) {
                if (celsius >= stage.minCelsius()) result = stage.tintArgb();
            }
            if (result != 0) return result;
        }

        return 0; // no tint — item is at normal temperature
    }

    /**
     * Whether this material produces any visible tint at the given temperature.
     */
    public boolean hasTint(double celsius) {
        return getTintForTemperature(celsius) != 0;
    }
}
