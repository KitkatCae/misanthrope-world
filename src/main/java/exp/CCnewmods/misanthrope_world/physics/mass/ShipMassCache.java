package exp.CCnewmods.misanthrope_world.physics.mass;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.admany.quantified.api.parallel.ParallelCompute;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.properties.IShipActiveChunksSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MWorld's own exact (unquantized) running-total mass per ship — independent of, and not fed back
 * into, VS2's own internal per-block mass derivation.
 *
 * <h3>Why this exists, and what it isn't</h3>
 * The original plan was to compute a ship's exact mass once, cache it, maintain it incrementally on
 * block place/break, and push it directly into VS2 to bypass VS2's own per-block accumulation
 * entirely. That push isn't currently possible: VS2's public {@code PhysShip} API
 * ({@code core:api}, confirmed against the real jars) only exposes {@code getMass()} /
 * {@code getCenterOfMass()} / {@code getMomentOfInertia()} as read-only getters. The only place a
 * settable inertia exists ({@code PhysicsBodyReference.setInertiaData} in the low-level
 * {@code physics_api} package) is never exposed through any ship-facing type in {@code core:api},
 * {@code core:util}, or {@code core:internal} — it's VS2's own private bridge to its physics
 * backend, not a mod extension point. So this cache does **not** override what VS2 uses for its own
 * physics (buoyancy, collision, momentum) — that stays whatever VS2 derives from
 * {@code MVSBlockStateInfoProvider}'s (quantized) per-block values.
 *
 * <p>What this cache <em>is</em>: an exact, MWorld-owned number for MWorld's own systems that want a
 * fast, precise ship mass without repeatedly summing block densities themselves (e.g. a future
 * {@code ENTITY_WEIGHT} torque calculation, diagnostics, or anything else that wants the "real" kg
 * figure rather than VS2's quantized-for-performance internal one). If/when a public setter becomes
 * available (either VS2 exposes one, or Caelan gets confirmation from the VS2 team — see
 * {@code MWorld_Mass_Authority_v1.md}), this is also exactly the value that would get pushed through
 * it, with no rework needed on this side.
 *
 * <h3>Wiring status</h3>
 * <ul>
 *   <li><b>Ship formation</b> — wire to {@code ShipLoadEvent} (confirmed real, public event in
 *       {@code core.api.events}, exposes {@code getShip(): LoadedServerShip}). Call
 *       {@link #computeExactMassAsync} then {@link #recordMass}.</li>
 *   <li><b>Block place/break</b> — not yet wired. Needs whichever hook MVSE/MWorld already uses to
 *       intercept block changes in ship-space (block break/place events filtered through
 *       {@code VSGameUtilsKt.getShipManagingPos}) — call {@link #adjustMass} with the
 *       placed/broken block's signed mass delta from there.</li>
 *   <li><b>Ship split/merge</b> — VS2's {@code SplitEvent}/{@code MergeEvent} are lower-level than
 *       expected: they give voxel-region root positions ({@code Vector3ic}), not the resulting
 *       {@code Ship} objects directly. Mapping "this split produced ship X and Y" from those events
 *       needs a bit more work than a straight hookup — flagging rather than guessing at it.</li>
 * </ul>
 */
public final class ShipMassCache {

    private static final Map<Long, Double> MASS_BY_SHIP_ID = new ConcurrentHashMap<>();

    private ShipMassCache() {
    }

    /**
     * The "big calculation" — exact (unquantized) total mass across every non-air block position
     * given, parallelized via Quantified API. Intended for one-off recomputation (ship load, ship
     * split producing a new ship), not per-tick use.
     */
    public static CompletableFuture<Double> computeExactMassAsync(long shipId, List<BlockPos> positions, Level level) {
        // ParallelCompute.<S, R>builder(...) fixes its class-level output type
        // parameter O to List<R> (confirmed via javap on the real jar — the
        // static factory returns Builder<S, R, List<R>> and .reducer() only
        // accepts Function<List<R>, O> where O is that SAME already-bound
        // List<R>, not a fresh type param). It can reshape the list, but it
        // can't collapse it to a scalar — that has to happen after submit(),
        // on the CompletableFuture<List<R>> the builder actually returns.
        return ParallelCompute.<BlockPos, Double>builder("misanthrope_world", "ship_exact_mass", shipId)
                .slices(() -> positions)
                .sliceExecutor(pos -> {
                    var state = level.getBlockState(pos);
                    if (state.isAir()) return 0.0;
                    return BlockPhysicsRegistry.get(state).densityKgM3;
                })
                .maxParallelism(Runtime.getRuntime().availableProcessors())
                .submit()
                .thenApply(masses -> masses.stream().mapToDouble(Double::doubleValue).sum());
    }

    /**
     * Convenience overload — enumerates the ship's own blocks first (see
     * {@link #collectShipBlockPositions}), then delegates to {@link #computeExactMassAsync}.
     */
    public static CompletableFuture<Double> computeExactMassAsync(LoadedServerShip ship, ServerLevel shipLevel) {
        return computeExactMassAsync(ship.getId(), collectShipBlockPositions(ship), shipLevel);
    }

    /**
     * Diagnostic-only: the quantized total, i.e. what VS2 itself would derive by summing
     * {@code MVSBlockStateInfoProvider}'s per-block values across the ship. Useful for comparing
     * against {@code physShip.getMass()} to confirm the provider is behaving as expected, and
     * against {@link #getMass} to see how far quantization diverges from the exact figure. Not
     * used for anything load-bearing — just a way to check our own work.
     */
    public static CompletableFuture<Double> computeQuantizedMassAsync(LoadedServerShip ship, ServerLevel shipLevel) {
        // See computeExactMassAsync's comment — .reducer() can't collapse
        // List<Double> to Double here, so the sum happens via thenApply()
        // on the raw per-block list submit() returns.
        return ParallelCompute.<BlockPos, Double>builder("misanthrope_world", "ship_quantized_mass_diagnostic", ship.getId())
                .slices(() -> collectShipBlockPositions(ship))
                .sliceExecutor(pos -> {
                    var state = shipLevel.getBlockState(pos);
                    if (state.isAir()) return 0.0;
                    return MassQuantizer.quantize(BlockPhysicsRegistry.get(state).densityKgM3);
                })
                .maxParallelism(Runtime.getRuntime().availableProcessors())
                .submit()
                .thenApply(masses -> masses.stream().mapToDouble(Double::doubleValue).sum());
    }

    /**
     * Same block-enumeration approach as {@code ShipInstrumentSampler.iterateShipBlocks} in MVSE —
     * {@link IShipActiveChunksSet} for chunk coverage, ship AABB for the Y range — collected into a
     * flat list up front since {@link ParallelCompute} slices over a {@code List<S>}, not a visitor
     * callback. (Moved here from MVSE's now-retired {@code ShipRemassSweep} — mass computation is
     * MWorld's job, MVSE is the interaction/mechanism layer.)
     */
    private static List<BlockPos> collectShipBlockPositions(LoadedServerShip ship) {
        List<BlockPos> positions = new ArrayList<>();
        IShipActiveChunksSet chunks = ship.getActiveChunksSet();
        var shipAABB = ship.getShipAABB();
        if (shipAABB == null) return positions;

        int minY = shipAABB.minY(), maxY = shipAABB.maxY();
        chunks.forEach((chunkX, chunkZ) -> {
            for (int bx = chunkX * 16; bx < chunkX * 16 + 16; bx++) {
                for (int bz = chunkZ * 16; bz < chunkZ * 16 + 16; bz++) {
                    for (int by = minY; by <= maxY; by++) {
                        positions.add(new BlockPos(bx, by, bz));
                    }
                }
            }
        });
        return positions;
    }

    /** Records/overwrites the cached exact mass for a ship (e.g. after {@link #computeExactMassAsync} completes). */
    public static void recordMass(long shipId, double exactMassKg) {
        MASS_BY_SHIP_ID.put(shipId, exactMassKg);
    }

    /**
     * Adjusts a ship's cached mass by a signed delta (positive for a block placed, negative for a
     * block broken) — the O(1) incremental path, avoiding a full recompute per block change.
     * No-op (rather than throwing) if the ship isn't cached yet, since that just means
     * {@link #computeExactMassAsync} hasn't run for it — the next full compute will be correct
     * regardless.
     */
    public static void adjustMass(long shipId, double deltaKg) {
        MASS_BY_SHIP_ID.merge(shipId, deltaKg, Double::sum);
    }

    /** @return the cached exact mass, or {@code Double.NaN} if this ship hasn't been computed yet. */
    public static double getMass(long shipId) {
        Double mass = MASS_BY_SHIP_ID.get(shipId);
        return mass == null ? Double.NaN : mass;
    }

    /** Call when a ship unloads/is destroyed, so the cache doesn't grow unbounded. */
    public static void removeShip(long shipId) {
        MASS_BY_SHIP_ID.remove(shipId);
    }
}
