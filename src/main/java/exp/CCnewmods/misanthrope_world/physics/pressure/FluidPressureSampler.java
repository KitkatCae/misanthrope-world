package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;

/**
 * Hydrostatic fluid pressure sampler for world-space blocks.
 *
 * <p>Replaces {@code WaterPressureProvider}. Handles all fluid types —
 * water, lava, and any modded fluid — by reading the fluid block's
 * {@link BlockPhysicsData#densityKgM3} from {@link BlockPhysicsRegistry}.
 * If a fluid block has no {@code material_properties} entry (or its density
 * is not explicitly set), sensible vanilla fallbacks apply.
 *
 * <h3>Hydrostatic model</h3>
 * Pressure at depth {@code d} below the fluid surface:
 * <pre>
 *   P(d) = ρ × g × d
 * </pre>
 * where {@code ρ} is fluid density in kg/m³, {@code g} = 9.81 m/s² (Minecraft
 * gravity), and {@code d} is depth in blocks (1 block ≈ 1 metre).
 *
 * <p>The column is walked upward from {@code queryPos}, accumulating
 * {@code ρ_i × g × 1} per fluid block, stopping at the first non-fluid block.
 * Mixed-fluid columns (e.g. a layer of lava over water) are handled correctly —
 * each block contributes its own density.
 *
 * <h3>Validity</h3>
 * This model assumes a static, open-topped fluid column. For sealed pressurised
 * tanks where the gas above the fluid is at non-atmospheric pressure, the
 * atmospheric contribution is added automatically by the caller
 * ({@link WorldSpacePressureHandler}) via the gas pressure from MGE's
 * {@code EnvironmentGrid.getComposition().totalPressure()}. The column
 * contribution returned here is purely the hydrostatic component.
 *
 * <h3>Registration</h3>
 * Previously, external mods called {@code WaterPressureProvider.register()}.
 * That hook is now handled through the {@code material_properties} JSON system:
 * a mod that adds a denser or lighter fluid simply ships a JSON for its fluid
 * block with the correct {@code density_kg_m3}. No registration call needed.
 */
public final class FluidPressureSampler {

    private FluidPressureSampler() {}

    // ── Physical constants ────────────────────────────────────────────────────

    /** Gravitational acceleration in m/s² (standard Minecraft). */
    public static final float G_M_S2 = 9.81f;

    /**
     * Conversion from Pa (N/m²) to mbar: 1 Pa = 0.01 mbar, but since we're
     * working in blocks-as-metres and kg/m³, ρgh gives Pa directly, so:
     *   mbar = Pa × 0.01
     * At 1 block depth with water (ρ=1000): 1000 × 9.81 × 1 × 0.01 = 98.1 mbar.
     */
    public static final float PA_TO_MBAR = 0.01f;

    // ── Vanilla density fallbacks (used when no material_properties entry) ────

    /** Water density fallback (kg/m³). Matches real seawater ≈ 1025 kg/m³. */
    public static final float DENSITY_WATER_FALLBACK = 1000f;

    /** Lava density fallback (kg/m³). Basaltic lava ≈ 2800–3100 kg/m³. */
    public static final float DENSITY_LAVA_FALLBACK  = 3100f;

    /** Generic fluid fallback for modded fluids with no JSON entry (kg/m³). */
    public static final float DENSITY_GENERIC_FALLBACK = 1000f;

    // ── Main query ────────────────────────────────────────────────────────────

    /**
     * Returns the total hydrostatic pressure in mbar exerted by the fluid
     * column at {@code queryPos} on the block face below it.
     *
     * <p>Walks upward from {@code queryPos}, summing ρ×g×1 per fluid block,
     * stopping at the first non-fluid cell. The result is the total fluid
     * pressure contribution above this point.
     *
     * @param level      server level
     * @param queryPos   position of the block face being pressurised (the block
     *                   itself, not the fluid cell directly above it)
     * @return fluid hydrostatic pressure in mbar. 0 if the block is not below
     *         any fluid.
     */
    public static float getFluidColumnPressureMbar(ServerLevel level, BlockPos queryPos) {
        float totalPressureMbar = 0f;
        BlockPos.MutableBlockPos cursor = queryPos.mutable().move(0, 1, 0);

        while (cursor.getY() < level.getMaxBuildHeight()) {
            BlockState state = level.getBlockState(cursor);
            FluidState fluid = state.getFluidState();

            if (fluid.isEmpty()) break; // column ended

            float densityKgM3 = getDensityForFluid(level, cursor, state, fluid);
            // ρ × g × 1 block × Pa→mbar
            totalPressureMbar += densityKgM3 * G_M_S2 * PA_TO_MBAR;

            cursor.move(0, 1, 0);
        }

        return totalPressureMbar;
    }

    /**
     * Returns whether the block at {@code pos} is directly below a fluid.
     * Used by {@link WorldSpacePressureHandler} to decide whether to sample
     * fluid pressure on the top face of a boundary block.
     */
    public static boolean isSubmergedOrUnderFluid(ServerLevel level, BlockPos pos) {
        // The block itself is submerged
        if (!level.getFluidState(pos).isEmpty()) return true;
        // Or the block above it is a fluid
        return !level.getFluidState(pos.above()).isEmpty();
    }

    // ── Density resolution ────────────────────────────────────────────────────

    /**
     * Returns the material density for the fluid at {@code pos}.
     *
     * <p>Priority:
     * <ol>
     *   <li>Top-level {@code density_kg_m3} in the block's
     *       {@code material_properties} JSON.</li>
     *   <li>Vanilla fluid type check (water → 1000, lava → 3100).</li>
     *   <li>Generic fallback (1000 kg/m³).</li>
     * </ol>
     */
    public static float getDensityForFluid(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            FluidState fluid) {

        // Check material_properties first
        @Nullable BlockPhysicsData bpd = BlockPhysicsRegistry.get(state);
        if (bpd != null && bpd.densityKgM3 > 1.5) {
            // Any non-air density is authoritative (1.2 = air default,
            // so > 1.5 means it was intentionally set for this fluid block)
            return (float) bpd.densityKgM3;
        }

        // Vanilla fallbacks
        if (fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER) {
            return DENSITY_WATER_FALLBACK;
        }
        if (fluid.getType() == Fluids.LAVA || fluid.getType() == Fluids.FLOWING_LAVA) {
            return DENSITY_LAVA_FALLBACK;
        }

        return DENSITY_GENERIC_FALLBACK;
    }

    /**
     * Convenience overload that looks up the block state from the level.
     */
    public static float getDensityForFluidAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return 0f;
        return getDensityForFluid(level, pos, state, fluid);
    }
}
