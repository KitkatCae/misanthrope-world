package exp.CCnewmods.misanthrope_world.physics.pressure.creature;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Reads the existing {@code data/misanthrope_world/pressure_sealed/} JSONs
 * (same format used by the diving-suit entry) and exposes a query to check
 * how much pressure-damage reduction a living entity's worn armour provides.
 *
 * <h3>Existing JSON schema (unchanged)</h3>
 * <pre>{@code
 * {
 *   "required_pieces": [
 *     "alexscaves:diving_helmet",
 *     "alexscaves:diving_chestplate",
 *     "alexscaves:diving_leggings",
 *     "alexscaves:diving_boots"
 *   ],
 *   "damage_reduction": 0.70
 * }
 * }</pre>
 *
 * Multiple sets stack additively, capped at {@link #MAX_REDUCTION}.
 * A partial set (missing one or more required_pieces) contributes nothing.
 */
public final class PressureSealedRegistry extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("MisWorld/PressureSealed");
    private static final Gson   GSON   = new Gson();

    public static final PressureSealedRegistry INSTANCE = new PressureSealedRegistry();

    /** Additive reduction is capped at this fraction to avoid negative damage. */
    public static final float MAX_REDUCTION = 0.95f;

    private record SealedSet(List<String> requiredPieces, float reduction) {}

    private volatile List<SealedSet> sets = Collections.emptyList();

    private PressureSealedRegistry() {
        super(GSON, "pressure_sealed");
    }

    // ── SimpleJsonResourceReloadListener ─────────────────────────────────────

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> objects,
            ResourceManager manager,
            ProfilerFiller profiler) {

        List<SealedSet> loaded = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                float reduction = obj.has("damage_reduction")
                        ? obj.get("damage_reduction").getAsFloat() : 0f;

                List<String> pieces = new ArrayList<>();
                if (obj.has("required_pieces")) {
                    for (JsonElement el : obj.getAsJsonArray("required_pieces")) {
                        pieces.add(el.getAsString());
                    }
                }

                if (!pieces.isEmpty() && reduction > 0f) {
                    loaded.add(new SealedSet(pieces, Math.min(reduction, 1f)));
                }
            } catch (Exception e) {
                LOGGER.error("[MisWorld/PressureSealed] Failed to parse {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }

        this.sets = Collections.unmodifiableList(loaded);
        LOGGER.info("[MisWorld/PressureSealed] Loaded {} pressure-sealed armour set(s).", loaded.size());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the total damage-reduction fraction [0, {@link #MAX_REDUCTION}]
     * provided by the entity's currently equipped armour across all matching
     * sealed sets.
     *
     * <p>A sealed set only contributes if the entity is wearing <em>every</em>
     * required piece simultaneously. Multiple fully-matched sets stack additively.
     *
     * @param entity the living entity whose equipment slots are checked
     * @return total reduction in [0, {@link #MAX_REDUCTION}]
     */
    public float getDamageReduction(LivingEntity entity) {
        if (sets.isEmpty()) return 0f;

        // Collect entity's worn item registry keys
        Set<String> worn = new HashSet<>();
        for (ItemStack stack : entity.getArmorSlots()) {
            if (stack.isEmpty()) continue;
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null) worn.add(key.toString());
        }
        // Also check hand slots for held pressure equipment (unusual but supported)
        for (ItemStack stack : entity.getHandSlots()) {
            if (stack.isEmpty()) continue;
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null) worn.add(key.toString());
        }

        float total = 0f;
        for (SealedSet set : sets) {
            if (worn.containsAll(set.requiredPieces())) {
                total += set.reduction();
            }
        }
        return Math.min(total, MAX_REDUCTION);
    }
}
