package exp.CCnewmods.misanthrope_world.physics.pressure;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Generic volume-level pressure state shared between world-space enclosed
 * regions and VS2 ship hulls.
 *
 * <p>Both {@link WorldSpacePressureHandler} (world-space rooms, vessels) and
 * {@code HullPressureState} in MVSE (ship-keyed) own one of these per tracked
 * volume. It holds:
 * <ul>
 *   <li>Per-block {@link BlockPressureState} entries for every boundary block
 *       currently under stress or recently dirtied.</li>
 *   <li>Volume-wide structural integrity tracking, used to detect catastrophic
 *       failure when many blocks breach in sequence.</li>
 *   <li>Cached internal pressure, refreshed on demand by the owning handler.</li>
 * </ul>
 *
 * <h3>Structural integrity</h3>
 * A scalar [0, 1] reduced by {@link #INTEGRITY_BREACH_LOSS} each time a block
 * breaches. Recovers slowly at {@link #INTEGRITY_RECOVERY_RATE} per tick when
 * ΔP is safe across all tracked blocks. At zero, the owning handler triggers
 * catastrophic failure (ship disassembly for MVSE, room decompression for
 * world-space).
 *
 * <h3>Interior volume</h3>
 * Tracked as an integer block-count estimate. VS2 updates this via
 * {@code ThermalStructure.scan()}. World-space updates it lazily via
 * flood-fill when the baseline changes significantly. Inflatable blocks
 * contribute a fractional bonus via {@link #computeInflatedVolumeBonus()}.
 */
public final class PressureVolumeState {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Structural integrity recovered per tick when ΔP is safe. */
    public static final float INTEGRITY_RECOVERY_RATE = 0.00005f; // ~33 min full recovery

    /** Structural integrity lost per breach event. */
    public static final float INTEGRITY_BREACH_LOSS = 0.08f;

    /** Integrity at which catastrophic failure fires. */
    public static final float CATASTROPHIC_FAILURE_THRESHOLD = 0.0f;

    // ── Volume-wide state ─────────────────────────────────────────────────────

    /** Ship-wide or room-wide structural integrity [0, 1]. */
    public float structuralIntegrity = 1.0f;

    /**
     * Cached internal pressure in mbar. Set by the owning handler after
     * sampling the interior atmosphere (via MGE EnvironmentGrid for world-space,
     * or HullInteriorSampler BFS for ships).
     */
    public float cachedInternalPressureMbar = 1013.25f;

    /** Game tick at which {@link #cachedInternalPressureMbar} was last set. */
    public long internalPressureLastSampledTick = -1L;

    /**
     * Current effective interior volume in blocks (cubic-metre approximation).
     * Grows as inflatable blocks expand outward. Used by callers to correctly
     * scale gas concentration: bigger volume at same moles = lower pressure.
     */
    public int effectiveInteriorVolume = 0;

    /**
     * Baseline interior volume from the last full scan (flood-fill or
     * ThermalStructure). Used to detect changes that warrant a re-scan.
     */
    public int baselineInteriorVolume = 0;

    /** Tick of the last full interior volume scan. */
    public long lastInteriorScanTick = -1L;

    // ── Per-block state ───────────────────────────────────────────────────────

    /**
     * State for each boundary block currently under stress or dirty.
     * Key: immutable BlockPos in the handler's coordinate space
     * (ship-space for MVSE, world-space for {@link WorldSpacePressureHandler}).
     */
    public final Map<BlockPos, BlockPressureState> blockStates = new HashMap<>();

    // ── Construction ──────────────────────────────────────────────────────────

    public PressureVolumeState() {}

    // ── Block state helpers ───────────────────────────────────────────────────

    /**
     * Gets the existing {@link BlockPressureState} for {@code pos}, or creates
     * a fresh one and registers it.
     */
    public BlockPressureState getOrCreate(BlockPos pos) {
        return blockStates.computeIfAbsent(pos.immutable(), p -> new BlockPressureState());
    }

    /**
     * Marks the block state at {@code pos} as dirty if it exists.
     * Called by the owning handler's neighbour-notify listener.
     * If no state exists yet, the handler will create one lazily on next
     * pressure evaluation (driven by pulse or dirty scan).
     */
    public void markDirty(BlockPos pos) {
        BlockPressureState bs = blockStates.get(pos);
        if (bs != null) bs.pressureDirty = true;
    }

    // ── Integrity helpers ─────────────────────────────────────────────────────

    /**
     * Records a breach event: reduces structural integrity.
     *
     * @return {@code true} if integrity has reached
     *         {@link #CATASTROPHIC_FAILURE_THRESHOLD} and catastrophic failure
     *         should be triggered by the owning handler.
     */
    public boolean registerBreach() {
        structuralIntegrity = Math.max(0f, structuralIntegrity - INTEGRITY_BREACH_LOSS);
        return structuralIntegrity <= CATASTROPHIC_FAILURE_THRESHOLD;
    }

    /**
     * Ticks integrity recovery and prunes idle block states.
     * Call once per tick when the volume as a whole is in a safe pressure state.
     */
    public void tickRecovery() {
        if (structuralIntegrity < 1f) {
            structuralIntegrity = Math.min(1f,
                    structuralIntegrity + INTEGRITY_RECOVERY_RATE);
        }
        blockStates.values().removeIf(BlockPressureState::isIdle);
    }

    // ── Volume helpers ────────────────────────────────────────────────────────

    /**
     * Returns the total extra interior volume contributed by currently-inflated
     * blocks (each fully-inflated block ≈ 1 additional block of interior space).
     */
    public int computeInflatedVolumeBonus() {
        int bonus = 0;
        for (BlockPressureState bs : blockStates.values()) {
            bonus += (int) bs.inflationFraction;
        }
        return bonus;
    }

    /**
     * Returns the current effective interior volume including inflation bonus.
     */
    public int totalVolume() {
        return baselineInteriorVolume + computeInflatedVolumeBonus();
    }
}
