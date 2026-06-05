package exp.CCnewmods.misanthrope_world.wet_sand;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Central registry for the wet-sand system.
 * <p>
 * Maintains two maps:
 * dryToEntry  — dry block → WettableSoilEntry (for "is this block wettable?")
 * wetToEntry  — wet block → WettableSoilEntry (for "is this a wet variant?")
 * <p>
 * The Java-side hard-coded entries (vanilla sand, red sand, etc.) are added
 * during mod construction via registerHardcoded(). JSON entries from
 * data/misanthrope_core/wet_sand/*.json are merged in on server start/reload.
 * <p>
 * JSON format:
 * {
 * "dry":       "minecraft:sand",
 * "moist":     "misanthrope_core:moist_sand",
 * "wet":       "misanthrope_core:wet_sand",
 * "soaked":    "misanthrope_core:soaked_sand",
 * "saturated": "misanthrope_core:saturated_sand"
 * }
 */
public class WetSandRegistry extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogManager.getLogger(Misanthrope_world.MODID + "/WetSandRegistry");
    public static final WetSandRegistry INSTANCE = new WetSandRegistry();

    /**
     * dry block → entry
     */
    private final Map<Block, WettableSoilEntry> dryToEntry = new IdentityHashMap<>();
    /**
     * wet block (any level) → entry
     */
    private final Map<Block, WettableSoilEntry> wetToEntry = new IdentityHashMap<>();

    private WetSandRegistry() {
        super(GSON, "wet_sand");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true if this block is a wettable dry soil (vanilla/mod sand, dirt, etc.)
     * that has registered wet variants.
     */
    public boolean isDryWettable(Block block) {
        return dryToEntry.containsKey(block);
    }

    /**
     * Returns true if this block is any wet variant (moist/wet/soaked/saturated).
     */
    public boolean isWetVariant(Block block) {
        return wetToEntry.containsKey(block);
    }

    /**
     * Returns true if the block participates in the wet-sand system at all
     * (either as a dry source or a wet variant).
     */
    public boolean isWettable(Block block) {
        return isDryWettable(block) || isWetVariant(block);
    }

    /**
     * For a dry block, returns the wet-variant block at the given WetnessLevel,
     * or empty if not found / DRY passed.
     */
    public Optional<Block> getWetVariant(Block dryBlock, WetnessLevel level) {
        WettableSoilEntry entry = dryToEntry.get(dryBlock);
        if (entry == null) return Optional.empty();
        return entry.getWetVariant(level);
    }

    /**
     * For a wet-variant block, returns the dry source block, or empty.
     */
    public Optional<Block> getDryBlock(Block wetVariant) {
        WettableSoilEntry entry = wetToEntry.get(wetVariant);
        if (entry == null) return Optional.empty();
        return Optional.of(entry.dryBlock);
    }

    /**
     * For a wet-variant block, returns which WetnessLevel it represents,
     * or empty if it is not a known wet variant.
     */
    public Optional<WetnessLevel> getWetnessLevel(Block block) {
        WettableSoilEntry entry = wetToEntry.get(block);
        if (entry == null) return Optional.empty();
        return entry.getLevelOf(block);
    }

    /**
     * For a wet-variant block, returns the next-drier variant block (or the
     * original dry block if already at MOIST).
     * Returns empty if the input is not a wet variant.
     */
    public Optional<Block> getDrierVariant(Block wetBlock) {
        WettableSoilEntry entry = wetToEntry.get(wetBlock);
        if (entry == null) return Optional.empty();
        return entry.getLevelOf(wetBlock).flatMap(level -> {
            WetnessLevel drier = level.dryer();
            if (drier == WetnessLevel.DRY) return Optional.of(entry.dryBlock);
            return entry.getWetVariant(drier);
        });
    }

    /**
     * For a dry or wet block, returns the next-wetter variant block.
     * If the input is dry, returns the MOIST variant.
     * If already SATURATED, returns empty.
     */
    public Optional<Block> getWetterVariant(Block block) {
        // Could be dry or already wet
        if (isDryWettable(block)) {
            return getWetVariant(block, WetnessLevel.MOIST);
        }
        WettableSoilEntry entry = wetToEntry.get(block);
        if (entry == null) return Optional.empty();
        return entry.getLevelOf(block).flatMap(level -> {
            WetnessLevel wetter = level.wetter();
            if (wetter == WetnessLevel.SATURATED && level == WetnessLevel.SATURATED)
                return Optional.empty(); // already max
            return entry.getWetVariant(wetter);
        });
    }

    /**
     * Returns the wet-variant block appropriate for a given BFS distance from
     * water, applied to the given dry or wet block.
     * Distance 0 or > MAX_DISTANCE → returns the dry block (empty = no change needed
     * if already dry, but we return it for completeness).
     */
    public Optional<Block> getVariantForDistance(Block currentBlock, int distance) {
        WetnessLevel target = WetnessLevel.forDistance(distance);

        // Resolve the base dry block regardless of current wet state
        Block dry;
        if (isDryWettable(currentBlock)) {
            dry = currentBlock;
        } else if (isWetVariant(currentBlock)) {
            dry = getDryBlock(currentBlock).orElse(null);
            if (dry == null) return Optional.empty();
        } else {
            return Optional.empty(); // not a wettable block
        }

        if (target == WetnessLevel.DRY) return Optional.of(dry);
        return getWetVariant(dry, target);
    }

    // -------------------------------------------------------------------------
    // Registration (called from WetSandRegistration during mod init)
    // -------------------------------------------------------------------------

    /**
     * Register a dry→wet mapping from Java code. Call this during mod
     * construction (before server start) for all known/hardcoded blocks.
     * The wet variant blocks must already be registered in ForgeRegistries.BLOCKS.
     */
    public void registerHardcoded(Block dry,
                                  Block moist,
                                  Block wet,
                                  Block soaked,
                                  Block saturated) {
        EnumMap<WetnessLevel, Block> variants = new EnumMap<>(WetnessLevel.class);
        variants.put(WetnessLevel.MOIST, moist);
        variants.put(WetnessLevel.WET, wet);
        variants.put(WetnessLevel.SOAKED, soaked);
        variants.put(WetnessLevel.SATURATED, saturated);

        WettableSoilEntry entry = new WettableSoilEntry(dry, variants);
        dryToEntry.put(dry, entry);
        wetToEntry.put(moist, entry);
        wetToEntry.put(wet, entry);
        wetToEntry.put(soaked, entry);
        wetToEntry.put(saturated, entry);

        LOGGER.debug("Registered hardcoded wet-sand entry: {} → moist={}, wet={}, soaked={}, saturated={}",
                ForgeRegistries.BLOCKS.getKey(dry),
                ForgeRegistries.BLOCKS.getKey(moist),
                ForgeRegistries.BLOCKS.getKey(wet),
                ForgeRegistries.BLOCKS.getKey(soaked),
                ForgeRegistries.BLOCKS.getKey(saturated));
    }

    // -------------------------------------------------------------------------
    // JSON reload listener
    // -------------------------------------------------------------------------

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsonMap,
                         ResourceManager manager,
                         ProfilerFiller profiler) {
        // Don't clear hardcoded entries — only remove previously JSON-loaded ones
        // by tracking which entries came from JSON (simple approach: re-add all JSON on reload)
        // We use a separate set to avoid removing Java entries.
        Set<Block> jsonSourceDryBlocks = new HashSet<>();

        for (Map.Entry<ResourceLocation, JsonElement> fileEntry : jsonMap.entrySet()) {
            ResourceLocation file = fileEntry.getKey();
            try {
                JsonObject obj = fileEntry.getValue().getAsJsonObject();

                Block dry = resolveBlock(obj, "dry", file);
                Block moist = resolveBlock(obj, "moist", file);
                Block wet = resolveBlock(obj, "wet", file);
                Block soaked = resolveBlock(obj, "soaked", file);
                Block saturated = resolveBlock(obj, "saturated", file);

                if (dry == null || moist == null || wet == null
                        || soaked == null || saturated == null) {
                    LOGGER.warn("Skipping wet_sand entry {} — one or more block IDs could not be resolved", file);
                    continue;
                }

                // Don't overwrite a hardcoded entry that was already registered
                if (dryToEntry.containsKey(dry) && !jsonSourceDryBlocks.contains(dry)) {
                    LOGGER.debug("Skipping JSON wet_sand entry {} — dry block already registered in Java", file);
                    continue;
                }

                EnumMap<WetnessLevel, Block> variants = new EnumMap<>(WetnessLevel.class);
                variants.put(WetnessLevel.MOIST, moist);
                variants.put(WetnessLevel.WET, wet);
                variants.put(WetnessLevel.SOAKED, soaked);
                variants.put(WetnessLevel.SATURATED, saturated);

                WettableSoilEntry entry = new WettableSoilEntry(dry, variants);
                dryToEntry.put(dry, entry);
                wetToEntry.put(moist, entry);
                wetToEntry.put(wet, entry);
                wetToEntry.put(soaked, entry);
                wetToEntry.put(saturated, entry);
                jsonSourceDryBlocks.add(dry);

                LOGGER.info("Loaded JSON wet_sand entry: {}", file);

            } catch (Exception e) {
                LOGGER.error("Failed to parse wet_sand JSON entry {}: {}", file, e.getMessage());
            }
        }

        LOGGER.info("WetSandRegistry loaded {} entries ({} from JSON)",
                dryToEntry.size(), jsonSourceDryBlocks.size());
    }

    @Nullable
    private Block resolveBlock(JsonObject obj, String key, ResourceLocation file) {
        if (!obj.has(key)) {
            LOGGER.warn("wet_sand entry {} missing key '{}'", file, key);
            return null;
        }
        String id = obj.get(key).getAsString();
        ResourceLocation rl = new ResourceLocation(id);
        Block block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            LOGGER.warn("wet_sand entry {}: block '{}' not found in registry", file, id);
            return null;
        }
        return block;
    }
}
