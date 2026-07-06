package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classifies a {@link BlockState} into a material category for the impact
 * system, and provides the associated hardness factor and elasticity coefficient.
 *
 * <h3>Design</h3>
 * Keeps three independent numeric properties per material:
 * <ul>
 *   <li><b>hardnessFactor</b> [1, N] — multiplied into the minimum kinetic
 *       energy required to excavate one block of this material. Higher =
 *       harder to excavate.</li>
 *   <li><b>elasticity</b> [0, 1] — fraction of perpendicular impact velocity
 *       reflected back on bounce. 0 = perfectly inelastic; 1 = perfect mirror.</li>
 *   <li><b>excavatable</b> — if false, the block resists ALL excavation (obsidian,
 *       bedrock, reinforced deepslate). The ship craters around it but cannot
 *       remove the block.</li>
 * </ul>
 *
 * <h3>Fallback</h3>
 * If no specific rule matches, the {@link #STONE} profile is used as a sensible
 * generic solid.
 *
 * <h3>BlockPhysicsRegistry integration</h3>
 * Ported from MVSE, where this class reached into Misanthrope Core's
 * BlockPhysicsRegistry via reflection (Core no longer has one at all — it was
 * superseded by MWorld's own material_properties system). Now that this class
 * lives in MWorld itself, it calls {@link BlockPhysicsRegistry} directly and
 * uses the real {@code compressiveStrengthKpa} / {@code fractureToughness}
 * structural fields.
 *
 * <h3>Missing structural data is a data gap, not a supported case</h3>
 * Every material_properties file is expected to carry structural data. A
 * block with none isn't treated as an intentional "unauthored, use a sensible
 * default" state — {@link #logMissingStructuralData} logs a warning (once per
 * block type, so a busy area doesn't spam the log) naming the exact block so
 * it can be authored, and the vanilla-tag heuristics below only exist as a
 * crash-avoidance fallback for that gap, not as permanent supported behavior.
 */
public final class MaterialProfile {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/MaterialProfile");
    private static final Set<net.minecraft.resources.ResourceLocation> WARNED_MISSING_DATA =
            ConcurrentHashMap.newKeySet();

    /**
     * Logs (once per block type) that a block has no structural data at all,
     * so it can be identified and authored. Does not affect the fallback value
     * used — callers still need to return something to avoid crashing.
     */
    private static void logMissingStructuralData(BlockState state) {
        var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !WARNED_MISSING_DATA.add(id)) return;
        LOGGER.warn("[MaterialProfile] {} has no structural data in material_properties — "
                + "author an entry for it. Falling back to a vanilla-tag heuristic in the "
                + "meantime, which is not a substitute for real data.", id);
    }

    // -------------------------------------------------------------------------
    // Enum
    // -------------------------------------------------------------------------

    public enum Category {
        /** Bedrock, barriers — completely indestructible. */
        INDESTRUCTIBLE(99.0, 0.0, false),
        /** Obsidian, reinforced deepslate, crying obsidian. */
        ULTRA_HARD(8.0, 0.05, false),
        /** Blocks needing diamond tool: diamond/iron ore veins, deepslate. */
        HARD_STONE(3.0, 0.1, true),
        /** Normal stone, andesite, diorite, granite, cobblestone. */
        STONE(1.5, 0.15, true),
        /** Sand, gravel, soul sand, soul soil, clay. */
        SOFT_EARTH(0.5, 0.4, true),
        /** Dirt, grass, farmland, podzol, coarse dirt. */
        EARTH(0.7, 0.2, true),
        /** Snow, powder snow, ice, frosted ice. */
        ICE_SNOW(0.3, 0.55, true),
        /** Wood, logs, planks, leaves. */
        WOOD(0.6, 0.1, true),
        /** Metal blocks: iron, gold, netherite, copper. */
        METAL(2.5, 0.25, true),
        /** Glass, glass panes, sea lantern, amethyst. */
        GLASS(0.2, 0.05, true),
        /** Water, lava — fluid bodies deflect but don't crater. */
        FLUID(0.1, 0.9, false);

        public final double hardnessFactor;
        public final double elasticity;
        public final boolean excavatable;

        Category(double hardnessFactor, double elasticity, boolean excavatable) {
            this.hardnessFactor = hardnessFactor;
            this.elasticity     = elasticity;
            this.excavatable    = excavatable;
        }
    }

    // -------------------------------------------------------------------------
    // Classification
    // -------------------------------------------------------------------------

    /**
     * Classifies a block state into a {@link Category}.
     *
     * <p>Checks {@link BlockPhysicsRegistry} first for precise structural
     * values; falls back to vanilla block tags, MapColor, and destroy-speed
     * heuristics for blocks that don't have structural data authored yet.</p>
     */
    public static Category classify(BlockState state) {
        Block block = state.getBlock();

        // ── Absolute indestructibles ─────────────────────────────────────────
        if (state.getDestroySpeed(null, BlockPos.ZERO) < 0) return Category.INDESTRUCTIBLE;
        if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.REINFORCED_DEEPSLATE
                || block == Blocks.ANCIENT_DEBRIS) {
            return Category.ULTRA_HARD;
        }

        // ── Fluids ───────────────────────────────────────────────────────────
        if (!state.getFluidState().isEmpty()) return Category.FLUID;

        // ── Ice / snow ───────────────────────────────────────────────────────
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE || block == Blocks.SNOW
                || block == Blocks.SNOW_BLOCK || block == Blocks.POWDER_SNOW) {
            return Category.ICE_SNOW;
        }

        // ── Glass ────────────────────────────────────────────────────────────
        MapColor color = state.getMapColor(null, BlockPos.ZERO);
        if (block == Blocks.GLASS || block == Blocks.GLASS_PANE
                || block == Blocks.SEA_LANTERN || block == Blocks.AMETHYST_BLOCK
                || block == Blocks.BUDDING_AMETHYST || block == Blocks.TINTED_GLASS
                || state.is(BlockTags.IMPERMEABLE)) {
            return Category.GLASS;
        }

        // ── Metal blocks ─────────────────────────────────────────────────────
        if (color == MapColor.METAL || color == MapColor.GOLD || color == MapColor.COLOR_GRAY) {
            // Also catches iron blocks, gold blocks, netherite, copper
            if (state.getDestroySpeed(null, BlockPos.ZERO) >= 5f) {
                return Category.METAL;
            }
        }

        // ── BlockPhysicsRegistry, if this block has structural data ──────────
        BlockPhysicsData.StructuralData sd = BlockPhysicsRegistry.get(state).structural;
        if (sd != null) {
            // Map compressive strength (kPa) → hardness category. Reference
            // values from material_properties_template.json: dirt~100,
            // sandstone~20000, limestone~50000, granite~200000, steel~250000.
            double kpa = sd.compressiveStrengthKpa();
            if (kpa < 5000)   return Category.SOFT_EARTH;
            if (kpa < 30000)  return Category.EARTH;
            if (kpa < 100000) return Category.STONE;
            if (kpa < 220000) return Category.HARD_STONE;
            return Category.ULTRA_HARD;
        }

        // ── No structural data authored for this block — log it, then fall
        // back to vanilla-tag heuristics purely to avoid crashing ───────────
        logMissingStructuralData(state);
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            // Sand, gravel, soul sand, clay, dirt family
            if (state.is(BlockTags.SAND) || block == Blocks.GRAVEL
                    || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL) {
                return Category.SOFT_EARTH;
            }
            return Category.EARTH;
        }

        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LEAVES)) {
            return Category.WOOD;
        }

        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return Category.HARD_STONE;
        }

        // Default: treat as generic stone
        return Category.STONE;
    }

    // -------------------------------------------------------------------------
    // Derived metrics
    // -------------------------------------------------------------------------

    /**
     * Returns the hardness factor for a block state.
     * Combines category hardness with destroy speed for finer granularity.
     *
     * @param state   the block state to evaluate
     * @param cfgHard the config multiplier for {@link Category#HARD_STONE} blocks
     * @return hardness factor ≥ 1.0
     */
    public static double hardnessFactor(BlockState state, double cfgHard) {
        Category cat = classify(state);
        double base  = cat.hardnessFactor;
        if (cat == Category.HARD_STONE) base *= cfgHard;
        // Blend with actual destroy speed for sub-category granularity
        float ds = state.getDestroySpeed(null, BlockPos.ZERO);
        if (ds > 0) {
            base *= Math.max(1.0, Math.log1p(ds) / Math.log1p(5));
        }
        return base;
    }

    /**
     * Returns the fracture toughness for a block state, or empty if this block
     * has no structural data at all. Used ONLY by the crack-shell integration
     * (this project's own addition on top of ported MVSE logic, not part of
     * MVSE's original crater/embed sizing) — when empty, callers skip
     * registering a crack source entirely rather than fabricate a number for
     * data that doesn't exist. Logs once per block type when this happens.
     * MVSE's original {@link #classify}/{@link #hardnessFactor} heuristic
     * fallback is unaffected by this — that's pre-existing crater/embed sizing
     * behavior, not new "default info" invented for a system I just added.
     */
    public static java.util.OptionalDouble fractureToughness(BlockState state) {
        BlockPhysicsData.StructuralData sd = BlockPhysicsRegistry.get(state).structural;
        if (sd != null) return java.util.OptionalDouble.of(Math.max(0.05, sd.fractureToughness()));
        logMissingStructuralData(state);
        return java.util.OptionalDouble.empty();
    }

    /**
     * Returns the compressive strength (kPa) for a block state, or empty if
     * this block has no structural data. Same skip-don't-fabricate contract
     * as {@link #fractureToughness}, used by {@code ImpactFractureBridge}.
     */
    public static java.util.OptionalDouble compressiveStrengthKpa(BlockState state) {
        BlockPhysicsData.StructuralData sd = BlockPhysicsRegistry.get(state).structural;
        if (sd != null) return java.util.OptionalDouble.of(sd.compressiveStrengthKpa());
        logMissingStructuralData(state);
        return java.util.OptionalDouble.empty();
    }

    /**
     * Returns the elasticity (bounce coefficient) of the dominant material at
     * the impact face. Takes the {@link ImpactConfig#softMaterialElasticity}
     * config value for overriding soft-earth elasticity specifically.
     */
    public static double elasticity(BlockState state, double cfgSoftElasticity) {
        Category cat = classify(state);
        if (cat == Category.SOFT_EARTH) return cfgSoftElasticity;
        return cat.elasticity;
    }

    /** Returns true if this block can be excavated by an impact. */
    public static boolean isExcavatable(BlockState state) {
        return classify(state).excavatable;
    }

    // Private constructor: utility class
    private MaterialProfile() {}
}
