package exp.CCnewmods.misanthrope_world.temperature.storage;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Defines the thermal storage behaviour of a single block type.
 *
 * <h3>Insulation model</h3>
 * The old record carried explicit {@code insulationThickness} and
 * {@code insulationMaterialResistance} fields that duplicated data already in
 * {@link BlockPhysicsRegistry}. The new design reads both from there:
 * <ul>
 *   <li>{@link BlockPhysicsData#insulationR} — R-value per full-block thickness</li>
 *   <li>{@link BlockPhysicsData#thicknessFraction} — 1.0 for full block, 0.5 for
 *       slab, 0.08 for pane</li>
 * </ul>
 * Effective wall R = {@code insulationR × thicknessFraction}. This is equivalent
 * to the old {@code insulationThickness × insulationMaterialResistance} product.
 * The physics registry encodes the same two concepts under consistent names.
 * <p>
 * An optional {@code insulation_r_override} lets you set a custom effective R
 * for containers whose walls differ from a full block of their material (e.g.
 * thin-walled wooden chests), without touching the block's own material entry.
 * {@code insulation_multiplier} scales the resolved R for double-wall construction.
 *
 * <h3>JSON format ({@code data/misanthrope_core/thermal_storage/<name>.json})</h3>
 * <pre>
 * {
 *   "block": "minecraft:chest",
 *
 *   // Optional: fixed internal temperature. If absent, internal = ambient.
 *   "internal_temperature": 2.0,
 *
 *   // Optional: registered IDynamicTemperatureProvider id.
 *   // Overrides internal_temperature when the BE is live.
 *   "dynamic_temperature_provider": "misanthrope_core:crucible",
 *
 *   // Optional: override effective wall R entirely (skips BlockPhysicsRegistry).
 *   // Use for thin-walled containers that differ from a solid block of their material.
 *   "insulation_r_override": 0.3,
 *
 *   // Optional: multiply the resolved R. Default 1.0.
 *   // Use for double-wall construction, insulated lids, etc.
 *   "insulation_multiplier": 1.0,
 *
 *   // Optional: whether this block actively drives items to its internal temp.
 *   // true  = items equalize at full thermal-mass rate toward internalTemp
 *   // false = items equalize at leak-attenuated rate (passive insulation only)
 *   "active": false,
 *
 *   // Optional: whether internal temp bleeds into the world (kilns, crucibles).
 *   "affects_nearby_blocks": false
 * }
 * </pre>
 *
 * <h3>Leak factor</h3>
 * <pre>
 *   effectiveR = insulation_r_override                              (if set)
 *             OR (BlockPhysicsData.insulationR
 *                 × BlockPhysicsData.thicknessFraction
 *                 × insulation_multiplier)
 *             OR GENERIC_SOLID fallback
 *
 *   leakFactor = clamp(1.0 / effectiveR, 0.0, 1.0)
 * </pre>
 * Examples:
 * <ul>
 *   <li>Wooden chest (insulationR≈0.3, thin walls → override≈0.15): leak≈0.67
 *       — fairly leaky, items approach ambient quickly.</li>
 *   <li>Stone vault (insulationR≈0.45, thickness=1.0): leak≈0.44
 *       — meaningfully slower equalization.</li>
 *   <li>Insulated icebox (insulation_r_override=4.0): leak≈0.25
 *       — items stay cold a long time without an active source.</li>
 * </ul>
 */
public record ThermalStorageData(
        ResourceLocation blockId,
        @Nullable Double staticInternalCelsius,
        @Nullable ResourceLocation dynamicProviderId,
        /** Explicit effective-R override. {@code Double.NaN} = derive from BlockPhysicsRegistry. */
        double insulationROverride,
        /** Multiplier on the resolved R. 1.0 = no change. */
        double insulationMultiplier,
        boolean active,
        boolean affectsNearbyBlocks
) {

    // ── Insulation resolution ─────────────────────────────────────────────────

    /**
     * Resolve the effective wall R-value for this block.
     * <p>
     * Uses {@code insulationROverride} when explicitly set (not NaN); otherwise
     * reads {@link BlockPhysicsData#insulationR} × {@link BlockPhysicsData#thicknessFraction}
     * from {@link BlockPhysicsRegistry}, then applies {@link #insulationMultiplier}.
     *
     * @param state the current block state, used for BlockPhysicsRegistry lookup.
     *              Pass {@code null} to use the GENERIC_SOLID fallback.
     */
    public double resolveEffectiveR(@Nullable BlockState state) {
        double r;
        if (!Double.isNaN(insulationROverride)) {
            r = insulationROverride;
        } else if (state != null) {
            BlockPhysicsData physics = BlockPhysicsRegistry.INSTANCE.get(state);
            // insulationR is the per-full-block R-value; thicknessFraction scales it
            // for slabs/panes/etc. — exactly what the old thickness × resistance did.
            r = physics.insulationR * physics.thicknessFraction;
        } else {
            r = BlockPhysicsData.GENERIC_SOLID.insulationR
                    * BlockPhysicsData.GENERIC_SOLID.thicknessFraction;
        }
        return Math.max(0.001, r * insulationMultiplier); // guard div-by-zero
    }

    /**
     * Fraction of ambient temperature that bleeds through the wall per tick [0, 1].
     * Lower means better insulated.
     */
    public double leakFactor(@Nullable BlockState state) {
        return Math.max(0.0, Math.min(1.0, 1.0 / resolveEffectiveR(state)));
    }

    // ── Temperature computation ───────────────────────────────────────────────

    /**
     * Effective temperature that items inside this block experience.
     *
     * @param ambientCelsius ambient world temperature at this block's position
     * @param be             the live block entity (for dynamic provider reads)
     * @param state          the current block state (for insulation R lookup)
     */
    public double effectiveTemperature(double ambientCelsius,
                                       @Nullable BlockEntity be,
                                       @Nullable BlockState state) {
        double internalTemp = resolveInternalTemperature(ambientCelsius, be);
        double leak = leakFactor(state);
        return internalTemp + (ambientCelsius - internalTemp) * leak;
    }

    /**
     * Legacy overload without BlockState — falls back to GENERIC_SOLID R.
     * Prefer the three-argument form for accuracy.
     */
    public double effectiveTemperature(double ambientCelsius, @Nullable BlockEntity be) {
        return effectiveTemperature(ambientCelsius, be, null);
    }

    /**
     * Rate multiplier for item temperature equalization inside this block.
     * Active blocks drive items at full rate; passive blocks at leak rate.
     */
    public double effectiveTickRateMultiplier(@Nullable BlockState state) {
        return active ? 1.0 : leakFactor(state);
    }

    /**
     * Legacy overload. Prefer the BlockState form.
     */
    public double effectiveTickRateMultiplier() {
        return effectiveTickRateMultiplier(null);
    }

    // ── Internal temperature resolution ──────────────────────────────────────

    private double resolveInternalTemperature(double ambientCelsius,
                                              @Nullable BlockEntity be) {
        if (dynamicProviderId != null && be != null) {
            IDynamicTemperatureProvider provider =
                    ThermalStorageRegistry.getDynamicProvider(dynamicProviderId);
            if (provider != null) {
                double dyn = provider.getInternalCelsius(be);
                if (!Double.isNaN(dyn)) return dyn;
            }
        }
        if (staticInternalCelsius != null) return staticInternalCelsius;
        return ambientCelsius; // pure insulator — no active source
    }
}
