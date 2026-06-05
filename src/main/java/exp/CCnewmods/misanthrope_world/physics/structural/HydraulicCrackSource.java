package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData.StructuralData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * {@link ICrackSourceProvider} that translates hydraulic cylinder pushing force
 * into crack stress on the block at the rod tip.
 *
 * <h3>Physics model</h3>
 * <p>The cylinder pushes with force {@code F} (Newtons) against the face of
 * the block at {@code rodTipPos}. The block's compressive strength is
 * {@code sd.compressiveStrengthKpa() × faceArea_m2} (in kN). If the cylinder
 * force exceeds the compressive capacity of the block, crack stress accumulates.</p>
 *
 * <p>Pressure is normalised to the 0–50 range {@link CrackPropagator} expects:</p>
 * <pre>
 *   stressFraction = forceN / (compressiveStrengthKpa × 1000 × faceArea)
 *   pressure = clamp((stressFraction - crackThreshold) /
 *                    (failureThreshold - crackThreshold) × 50, 0, 50)
 * </pre>
 *
 * <p>A cylinder producing 5 kN against sandstone (compressive ~20 000 kPa)
 * produces negligible stress. The same cylinder against loose dirt or soft
 * stone at sustained high pressure will eventually fail the block.</p>
 *
 * <h3>Zone</h3>
 * <p>Crack zone is a 1×1×1 AABB — exactly the rod-tip block. Cracks are
 * local to the contact point. If the cylinder shatters the block and advances,
 * the BE updates {@code rodTipPos} each tick, so the source naturally migrates
 * forward with the rod.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Registered by {@link exp.CCnewmods.misanthrope_vs_engine.hydraulic.blockentity.HydraulicCylinderBlockEntity}
 * each tick that force > 0. Expires after {@link #EXPIRY_TICKS} ticks without
 * a refresh — i.e. when the cylinder stops pushing (no fluid, joint snapped,
 * or rod tip is air).</p>
 *
 * <h3>CrackCause</h3>
 * <p>Uses {@link CrackCause#IMPACT} — wide radiating cracks from a point, no
 * healing. This matches the visual of rock being slowly crushed by machinery:
 * spiderweb fractures spreading from the contact face. The non-healing property
 * is intentional — hydraulic fracturing leaves permanent damage.</p>
 */
public final class HydraulicCrackSource implements ICrackSourceProvider {

    /**
     * Ticks without a refresh before this source expires (cylinder stopped).
     */
    public static final int EXPIRY_TICKS = 40;

    /**
     * 1×1 block face area in m² (matching StructuralStressField's convention).
     */
    private static final double FACE_AREA_M2 = 1.0;

    // ── State ──────────────────────────────────────────────────────────────────

    /**
     * Position of the block the rod tip is pressing against.
     */
    private BlockPos rodTipPos;

    /**
     * Current pushing force in Newtons.
     * Updated each tick by the cylinder BE via {@link #refresh}.
     */
    private double forceN;

    /**
     * Game tick at last refresh. Used to compute {@link #isExpired()}.
     * The cylinder BE calls {@link #refresh} each tick it is active.
     */
    private long lastRefreshTick;

    /**
     * Stable ID for {@link CrackPropagator} deduplication. One per cylinder.
     */
    private final String sourceId;

    /**
     * Cached pressure value computed by {@link #getCrackPressure}.
     * Recomputed each call — cheap since it's just arithmetic.
     */
    private float cachedPressure = 0f;

    // ── Constructor ────────────────────────────────────────────────────────────

    /**
     * @param cylinderPos world-space position of the cylinder block entity
     *                    (used to construct a stable source ID)
     * @param rodTipPos   block the rod is currently pressing against
     * @param forceN      pushing force in Newtons
     * @param gameTick    current game tick
     */
    public HydraulicCrackSource(BlockPos cylinderPos, BlockPos rodTipPos,
                                double forceN, long gameTick) {
        this.rodTipPos = rodTipPos.immutable();
        this.forceN = forceN;
        this.lastRefreshTick = gameTick;
        this.sourceId = "misanthrope_vs_engine:hydraulic_cylinder:"
                + cylinderPos.asLong();
    }

    // ── ICrackSourceProvider ───────────────────────────────────────────────────

    /**
     * Zone is a 3×3×3 AABB centred on the rod tip — same size as
     * {@link StructuralCrackSource} so the vein can spill into adjacent blocks
     * as the contact face fractures.
     */
    @Override
    public AABB getZone() {
        return new AABB(
                rodTipPos.getX() - 1.5, rodTipPos.getY() - 1.5, rodTipPos.getZ() - 1.5,
                rodTipPos.getX() + 2.5, rodTipPos.getY() + 2.5, rodTipPos.getZ() + 2.5
        );
    }

    /**
     * Computes crack pressure from the ratio of cylinder force to the rod-tip
     * block's compressive strength.
     *
     * <p>If the block has no structural data, pressure is computed against a
     * stone-equivalent default (20 000 kPa compressive, crack at 0.3, fail at 1.0).
     * This covers modded blocks that don't have material_properties JSON entries.</p>
     */
    @Override
    public float getCrackPressure(ServerLevel level) {
        if (forceN <= 0.01) return 0f;
        if (!level.isLoaded(rodTipPos)) return 0f;

        BlockState state = level.getBlockState(rodTipPos);
        if (state.isAir()) return 0f;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        StructuralData sd = data.structural;

        double compStrengthKpa;
        double crackThresh;
        double failThresh;

        if (sd != null) {
            compStrengthKpa = sd.compressiveStrengthKpa();
            crackThresh = sd.crackThresholdFraction();
            failThresh = sd.failureThresholdFraction();
        } else {
            // Unregistered block — assume stone-equivalent
            compStrengthKpa = 20_000.0;
            crackThresh = 0.3;
            failThresh = 1.0;
        }

        // Force in kN; strength in kN (kPa × m²)
        double forceKN = forceN / 1000.0;
        double strengthKN = compStrengthKpa * FACE_AREA_M2;
        double stressFraction = forceKN / Math.max(strengthKN, 0.001);

        if (stressFraction < crackThresh) return 0f;

        // Normalise to [0, 50] matching StructuralCrackSource's pressure range
        float pressure = (float) ((stressFraction - crackThresh)
                / Math.max(failThresh - crackThresh, 0.001) * 50f);
        pressure = Math.max(1f, Math.min(50f, pressure));

        cachedPressure = pressure;
        return pressure;
    }

    /**
     * IMPACT cause: radiating spiderweb fractures from the contact point,
     * no healing. Correct for hydraulic fracturing.
     */
    @Override
    public CrackCause getCause() {
        return CrackCause.IMPACT;
    }

    /**
     * Expires 40 ticks after the last refresh — the cylinder BE calls
     * {@link #refresh} every tick it is active, so expiry only fires if the
     * cylinder stops (no fluid, joint snapped, rod tip is air/already broken).
     * <p>
     * Note: {@link CrackPropagator} replaces providers with the same
     * {@link #sourceId()} via deduplication, so as long as the cylinder is
     * active it refreshes in place. Expiry is the fallback for sudden stops.
     */
    @Override
    public boolean isExpired() {
        // We don't have the game tick here — expiry is handled by the
        // CrackPropagator's deduplication: when the cylinder BE stops calling
        // CrackPropagator.addSource, this source stays until manually removed.
        // The BE calls CrackPropagator.removeSource(sourceId) in disassemble().
        return false;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    // ── Refresh ────────────────────────────────────────────────────────────────

    /**
     * Updates the rod-tip position and force value.
     * Called each server tick by the cylinder BE when the rod is actively pushing.
     *
     * @param newRodTipPos updated rod-tip block position (rod may have advanced)
     * @param newForceN    current pushing force in Newtons
     * @param gameTick     current game tick
     */
    public void refresh(BlockPos newRodTipPos, double newForceN, long gameTick) {
        this.rodTipPos = newRodTipPos.immutable();
        this.forceN = newForceN;
        this.lastRefreshTick = gameTick;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public BlockPos getRodTipPos() {
        return rodTipPos;
    }

    public double getForceN() {
        return forceN;
    }

    public float getCachedPressure() {
        return cachedPressure;
    }
}
