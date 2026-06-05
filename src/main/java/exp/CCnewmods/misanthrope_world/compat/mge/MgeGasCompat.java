package exp.CCnewmods.misanthrope_world.compat.mge;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Soft-dependency bridge between Misanthrope Core and the MGE gas system.
 * <p>
 * All calls are safe to make even when MGE is not present — every public method
 * checks {@link #isMgeLoaded()} and returns immediately if the mod is absent.
 * <p>
 * ── Fluid → Gas mapping ────────────────────────────────────────────────────
 * The crucible stores gas as Forge {@link Fluid} instances (e.g. the Create
 * virtual fluids or misanthrope_core's own radon fluid). This class maps those
 * fluid registry names to the corresponding MGE {@code Gas} objects so the gas
 * system receives semantically correct data.
 * <p>
 * Mappings are registered at static init time. Any fluid whose registry name
 * contains a recognisable keyword is mapped automatically; unknown fluids are
 * mapped to {@code mge:biogas} as a catch-all organic-decomp gas.
 * <p>
 * ── Emission amounts ───────────────────────────────────────────────────────
 * The crucible tracks gas in millibuckets (mB). MGE uses millibar (mbar) as its
 * unit. We convert with the ratio: 1 mB ≈ 0.5 mbar for a 1 m³ atmosphere block.
 * This keeps quantities in a reasonable game-feel range (1000 mB → 500 mbar).
 */
public final class MgeGasCompat {

    public static final String MGE_MODID = "mge";

    /**
     * mB → mbar conversion factor for venting into a 1 m³ atmosphere block.
     */
    private static final float MB_TO_MBAR = 0.5f;

    private MgeGasCompat() {}

    public static boolean isMgeLoaded() {
        return ModList.get().isLoaded(MGE_MODID);
    }

    // ── Fluid → Gas name mapping ──────────────────────────────────────────────

    /**
     * Maps a Forge fluid registry path segment (lowercased) to an MGE gas ID path.
     * Checked in order — first match wins.
     */
    private static final Map<String, String> FLUID_KEYWORD_TO_GAS = new HashMap<>();

    static {
        // Decay / biological gases
        FLUID_KEYWORD_TO_GAS.put("methane", "methane");
        FLUID_KEYWORD_TO_GAS.put("hydrogen_sulfide", "hydrogen_sulfide");
        FLUID_KEYWORD_TO_GAS.put("ammonia", "ammonia");
        FLUID_KEYWORD_TO_GAS.put("carbon_dioxide", "carbon_dioxide");
        FLUID_KEYWORD_TO_GAS.put("co2", "carbon_dioxide");
        FLUID_KEYWORD_TO_GAS.put("carbon_monoxide", "carbon_monoxide");
        FLUID_KEYWORD_TO_GAS.put("sulfur_dioxide", "sulfur_dioxide");
        FLUID_KEYWORD_TO_GAS.put("phosphine", "phosphine");
        FLUID_KEYWORD_TO_GAS.put("methanethiol", "methanethiol");
        FLUID_KEYWORD_TO_GAS.put("dimethylsulfide", "dimethylsulfide");
        FLUID_KEYWORD_TO_GAS.put("trimethylamine", "trimethylamine");
        FLUID_KEYWORD_TO_GAS.put("hydrogen_cyanide", "hydrogen_cyanide");
        // Industrial
        FLUID_KEYWORD_TO_GAS.put("radon", "radon");
        FLUID_KEYWORD_TO_GAS.put("oxygen", "oxygen");
        FLUID_KEYWORD_TO_GAS.put("nitrogen", "nitrogen");
        FLUID_KEYWORD_TO_GAS.put("hydrogen", "hydrogen");
        FLUID_KEYWORD_TO_GAS.put("chlorine", "chlorine");
        FLUID_KEYWORD_TO_GAS.put("ammonia", "ammonia");
        FLUID_KEYWORD_TO_GAS.put("formaldehyde", "formaldehyde");
        FLUID_KEYWORD_TO_GAS.put("acetylene", "acetylene");
        FLUID_KEYWORD_TO_GAS.put("propane", "propane");
        FLUID_KEYWORD_TO_GAS.put("butane", "butane");
        FLUID_KEYWORD_TO_GAS.put("ethylene", "ethylene");
        FLUID_KEYWORD_TO_GAS.put("soul_smoke", "soul_smoke");
        FLUID_KEYWORD_TO_GAS.put("blaze_fume", "blaze_fume");
        FLUID_KEYWORD_TO_GAS.put("wither_miasma", "wither_miasma");
        FLUID_KEYWORD_TO_GAS.put("necrotic_acid", "necrotic_acid_vapor");
        FLUID_KEYWORD_TO_GAS.put("biogas", "biogas");
    }

    /**
     * Given a Forge Fluid, return the MGE gas ID path (just the path, no namespace).
     * Returns {@code "biogas"} for any unrecognised fluid.
     */
    private static String gasPathFor(Fluid fluid) {
        ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(fluid);
        if (fluidId == null) return "biogas";
        String path = fluidId.getPath().toLowerCase();
        for (Map.Entry<String, String> entry : FLUID_KEYWORD_TO_GAS.entrySet()) {
            if (path.contains(entry.getKey())) return entry.getValue();
        }
        return "biogas"; // catch-all for unknown organic/process gases
    }

    // ── Public vent helpers ───────────────────────────────────────────────────

    /**
     * Vent combustion byproduct gases (CO₂, CO, H₂O vapour) from a heat source.
     * Called from CrucibleBlockEntity every server tick while actively heating.
     * No-op if MGE is not loaded.
     *
     * @param co2Mbar   carbon dioxide mbar to add
     * @param coMbar    carbon monoxide mbar to add (incomplete combustion)
     * @param vaporMbar water vapour mbar to add (fuel moisture)
     */
    public static void ventCombustionGas(ServerLevel level, BlockPos ventPos,
                                         float co2Mbar, float coMbar, float vaporMbar) {
        if (!isMgeLoaded()) return;
        try {
            if (co2Mbar > 0f) injectByName(level, ventPos, "carbon_dioxide", co2Mbar);
            if (coMbar > 0f) injectByName(level, ventPos, "carbon_monoxide", coMbar);
            if (vaporMbar > 0f) injectByName(level, ventPos, "water_vapor", vaporMbar);
        } catch (Exception ignored) {
        }
    }

    /**
     * Vent a map of Forge fluid→mB amounts into the atmosphere block above
     * {@code ventPos}. Typically called when the crucible is unsealed or spills.
     * <p>
     * No-op if MGE is not loaded.
     *
     * @param level   server level
     * @param ventPos the block position to inject gas into (usually one block
     *                above the crucible's top opening)
     * @param gases   map of Fluid→mB to release
     */
    public static void ventGases(ServerLevel level, BlockPos ventPos,
                                 Map<Fluid, Integer> gases) {
        if (!isMgeLoaded() || gases.isEmpty()) return;
        doVentGases(level, ventPos, gases);
    }

    /**
     * Vent a single Forge fluid amount into the atmosphere above {@code ventPos}.
     * No-op if MGE is not loaded.
     */
    public static void ventGas(ServerLevel level, BlockPos ventPos,
                               Fluid fluid, int mB) {
        if (!isMgeLoaded() || mB <= 0) return;
        Map<Fluid, Integer> single = Map.of(fluid, mB);
        doVentGases(level, ventPos, single);
    }

    /**
     * Vent decay gases appropriate for the given {@link DecayProfile} into the
     * atmosphere above the corpse. Called from CorpseEntity decay transitions.
     * No-op if MGE is not loaded.
     *
     * @param level   server level
     * @param corpsePos position of the corpse entity (gas injected 1 block above)
     * @param profile which decay stage is being entered
     */
    public static void emitDecayGas(ServerLevel level, BlockPos corpsePos,
                                    DecayProfile profile) {
        if (!isMgeLoaded()) return;
        doEmitDecayGas(level, corpsePos.above(), profile);
    }

    // ── Private implementation (only loaded when MGE present) ─────────────────

    private static void doVentGases(ServerLevel level, BlockPos ventPos,
                                    Map<Fluid, Integer> gases) {
        try {
            for (Map.Entry<Fluid, Integer> entry : gases.entrySet()) {
                String gasPath = gasPathFor(entry.getKey());
                var gasOpt = exp.CCnewmods.mge.gas.GasRegistry.get(
                        new ResourceLocation(MGE_MODID, gasPath));
                if (gasOpt.isEmpty()) continue;
                float mbar = entry.getValue() * MB_TO_MBAR;
                exp.CCnewmods.mge.event.WorldEventHandler.injectGas(
                        level, ventPos, gasOpt.get(), mbar);
            }
        } catch (Exception ignored) {
            // MGE not fully loaded — silent no-op
        }
    }

    private static void doEmitDecayGas(ServerLevel level, BlockPos pos,
                                       DecayProfile profile) {
        try {
            var reg = exp.CCnewmods.mge.gas.GasRegistry.class;
            var inject = exp.CCnewmods.mge.event.WorldEventHandler.class;

            switch (profile) {
                case BLOATED -> {
                    // Anaerobic fermentation begins: methane, H₂S, ammonia
                    injectByName(level, pos, "methane", 12f);
                    injectByName(level, pos, "hydrogen_sulfide", 6f);
                    injectByName(level, pos, "ammonia", 4f);
                    injectByName(level, pos, "carbon_dioxide",     8f);
                    injectByName(level, pos, "cadaverine", 1f);
                }
                case DECAYED -> {
                    // Putrefaction in full swing: VOCs, sulfur compounds
                    injectByName(level, pos, "methane", 8f);
                    injectByName(level, pos, "hydrogen_sulfide", 10f);
                    injectByName(level, pos, "methanethiol", 6f);
                    injectByName(level, pos, "dimethylsulfide", 4f);
                    injectByName(level, pos, "trimethylamine", 3f);
                    injectByName(level, pos, "ammonia", 5f);
                    injectByName(level, pos, "carbon_dioxide",   10f);
                    injectByName(level, pos, "cadaverine", 4f);
                    injectByName(level, pos, "cadaverine", 12f);
                    injectByName(level, pos, "putrescine", 8f);
                }
                case SKELETAL -> {
                    // Most organics gone — residual bone-decomp gases
                    injectByName(level, pos, "phosphine", 4f);
                    injectByName(level, pos, "methane", 3f);
                    injectByName(level, pos, "carbon_dioxide", 4f);
                    injectByName(level, pos, "hydrogen_sulfide",   2f);
                }
                case BLOATED_CONTINUOUS -> {
                    // Sustained trickle throughout BLOATED stage (~25% of burst)
                    injectByName(level, pos, "methane", 3f);
                    injectByName(level, pos, "hydrogen_sulfide", 1.5f);
                    injectByName(level, pos, "ammonia", 1f);
                    injectByName(level, pos, "carbon_dioxide", 2f);
                    injectByName(level, pos, "cadaverine", 3f);
                    injectByName(level, pos, "putrescine", 1f);
                }
                case DECAYED_CONTINUOUS -> {
                    // Sustained trickle throughout DECAYED stage (~25% of burst)
                    injectByName(level, pos, "methane", 2f);
                    injectByName(level, pos, "hydrogen_sulfide", 2.5f);
                    injectByName(level, pos, "methanethiol", 1.5f);
                    injectByName(level, pos, "dimethylsulfide", 1f);
                    injectByName(level, pos, "ammonia", 1.25f);
                    injectByName(level, pos, "carbon_dioxide", 2.5f);
                    injectByName(level, pos, "cadaverine", 3f);
                    injectByName(level, pos, "putrescine", 1f);
                }
                case SKELETAL_CONTINUOUS -> {
                    // Sustained trickle throughout SKELETAL stage (~33% of burst)
                    injectByName(level, pos, "phosphine", 1.3f);
                    injectByName(level, pos, "methane", 1f);
                    injectByName(level, pos, "carbon_dioxide", 1.3f);
                    injectByName(level, pos, "hydrogen_sulfide", 0.6f);
                }
            }
        } catch (Exception ignored) {
            // MGE not loaded or gas missing — silent
        }
    }

    private static void injectByName(ServerLevel level, BlockPos pos,
                                     String gasPath, float mbar) {
        exp.CCnewmods.mge.gas.GasRegistry
                .get(new ResourceLocation(MGE_MODID, gasPath))
                .ifPresent(gas ->
                        exp.CCnewmods.mge.event.WorldEventHandler
                                .injectGas(level, pos, gas, mbar));
    }

    // ── Decay profile enum ────────────────────────────────────────────────────

    /**
     * Which decay transition is occurring in the corpse.
     * Used to select the appropriate gas mix to emit.
     */
    public enum DecayProfile {
        /**
         * Entering BLOATED stage — fermentation begins (transition burst).
         */
        BLOATED,
        /** Entering DECAYED stage — full putrefaction (transition burst). */
        DECAYED,
        /**
         * Entering SKELETAL stage — bone decomposition (transition burst).
         */
        SKELETAL,
        /**
         * Sustained background emission throughout BLOATED stage (~25% of burst).
         */
        BLOATED_CONTINUOUS,
        /**
         * Sustained background emission throughout DECAYED stage (~25% of burst).
         */
        DECAYED_CONTINUOUS,
        /**
         * Sustained background emission throughout SKELETAL stage (~33% of burst).
         */
        SKELETAL_CONTINUOUS
    }
}
