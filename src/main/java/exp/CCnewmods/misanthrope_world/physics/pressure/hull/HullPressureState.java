package exp.CCnewmods.misanthrope_world.physics.pressure.hull;

import exp.CCnewmods.misanthrope_world.physics.pressure.BlockPressureState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Server-side per-ship pressure physics state.
 *
 * <p>One instance lives in {@link HullPressureHandler}'s ship-keyed map for
 * every ship that has been evaluated at least once. Stores everything the
 * pressure solver needs between ticks without re-scanning from scratch.</p>
 *
 * <h3>Per-block state</h3>
 * Every hull face block that has crossed elastic yield gets a
 * {@link BlockPressureState} entry. Blocks that have never been stressed are
 * absent from the maps (saves memory on large ships).
 *
 * <h3>Interior volume tracking</h3>
 * The server tracks the ship's current effective interior volume in blocks.
 * This starts as the flood-fill result from {@code ThermalStructure.scan()}
 * and grows as inflatable blocks expand outward. The volume is used by MGE's
 * EnvironmentGrid to correctly scale gas concentration: a bigger volume at the
 * same moles of gas = lower pressure.
 *
 * <h3>Structural integrity</h3>
 * A ship-wide scalar [0, 1]. Reduced by each breach event (block failure) and
 * by sustained creep stress. Recovered at {@link #INTEGRITY_RECOVERY_RATE}
 * per tick when ΔP is in the safe region. At zero, the entire hull fails
 * catastrophically — triggers {@code ShipDisassembler}.
 *
 * <h3>Cache invalidation</h3>
 * Interior pressure is expensive to sample (BFS through ship chunks). It is
 * cached for {@link #INTERIOR_CACHE_TICKS} ticks. The cache is dirtied when:
 * a block is removed from the ship, a block is added to the ship, or the
 * MGE EnvironmentGrid reports a significant gas change at any interior position.
 */
public final class HullPressureState {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Ticks between full interior pressure re-samples. */
    public static final int INTERIOR_CACHE_TICKS = 5;
    /** Ticks between full hull-face external pressure re-scans. */
    public static final int EXTERIOR_CACHE_TICKS = 10;
    /** Structural integrity recovered per tick when ΔP is safe. */
    public static final float INTEGRITY_RECOVERY_RATE = 0.00005f; // ~33 min for full recovery
    /** Structural integrity lost per breach event. */
    public static final float INTEGRITY_BREACH_LOSS = 0.08f;
    /** Structural integrity at which catastrophic hull failure triggers. */
    public static final float CATASTROPHIC_FAILURE_THRESHOLD = 0.0f;

    // -------------------------------------------------------------------------
    // Ship-wide state
    // -------------------------------------------------------------------------

    public final long shipId;

    /** Cached interior pressure in mbar. Refreshed every INTERIOR_CACHE_TICKS. */
    public float cachedInternalPressureMbar = 1013.25f; // standard atmosphere default

    /** Tick at which internal pressure was last sampled. */
    public long internalPressureLastSampledTick = -1;

    /**
     * Current effective interior volume in blocks (cubic metres approximation).
     * Starts from ThermalStructure flood-fill; grows as inflatable blocks expand.
     */
    public int effectiveInteriorVolume = 0;

    /**
     * Baseline interior volume from last full ThermalStructure scan.
     * Used to detect significant changes that warrant a re-scan.
     */
    public int baselineInteriorVolume = 0;

    /** Tick of last full ThermalStructure interior scan. */
    public long lastInteriorScanTick = -1;

    /** Ship-wide structural integrity [0, 1]. */
    public float structuralIntegrity = 1.0f;

    /** Whether the ship is currently submerged (drives water pressure sampling). */
    public boolean isSubmerged = false;

    /** Approximate water depth at ship centroid (blocks below sea level). */
    public double waterDepthBlocks = 0.0;

    // -------------------------------------------------------------------------
    // Per-block state
    // -------------------------------------------------------------------------

    /**
     * State for each hull block currently under stress.
     * Key: immutable BlockPos in ship-space.
     */
    public final Map<BlockPos, BlockPressureState> blockStates = new HashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public HullPressureState(long shipId) {
        this.shipId = shipId;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Gets or creates a {@link BlockPressureState} for the given position.
     */
    public BlockPressureState getOrCreate(BlockPos pos) {
        return blockStates.computeIfAbsent(pos.immutable(), p -> new BlockPressureState());
    }

    /**
     * Called when a breach occurs — reduces structural integrity and returns
     * whether catastrophic failure should be triggered.
     */
    public boolean registerBreach() {
        structuralIntegrity = Math.max(0f, structuralIntegrity - INTEGRITY_BREACH_LOSS);
        return structuralIntegrity <= CATASTROPHIC_FAILURE_THRESHOLD;
    }

    /**
     * Ticks recovery when the hull is in a safe pressure state.
     */
    public void tickRecovery() {
        if (structuralIntegrity < 1f) {
            structuralIntegrity = Math.min(1f,
                    structuralIntegrity + INTEGRITY_RECOVERY_RATE);
        }
        // Remove idle block states to free memory
        blockStates.values().removeIf(BlockPressureState::isIdle);
    }

    /**
     * Returns the total expanded volume contributed by all currently inflated
     * blocks, in blocks (cubic metres approximation).
     * Inflated blocks expand into adjacent cells, increasing interior volume.
     */
    public int computeInflatedVolumeBonus() {
        int bonus = 0;
        for (BlockPressureState bs : blockStates.values()) {
            // Each fully-inflated block adds ~1 block to the volume
            bonus += (int)(bs.inflationFraction);
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
