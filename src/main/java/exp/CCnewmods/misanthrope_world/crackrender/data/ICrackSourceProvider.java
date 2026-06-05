package exp.CCnewmods.misanthrope_world.crackrender.data;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Implemented by anything that drives crack accumulation on nearby blocks.
 * <p>
 * ── Usage ─────────────────────────────────────────────────────────────────────
 * Register your provider with CrackRegistry.addSource(provider).
 * The CrackPropagator polls all registered providers each PROPAGATION_INTERVAL
 * ticks on the server tick.
 * <p>
 * ── Examples ─────────────────────────────────────────────────────────────────
 * - CrucibleBlockEntity: implements this, returns its shell AABB and a pressure
 * proportional to how far the internal temp exceeds wall crack threshold.
 * - CaveStructuralAnalyzer: a passive provider registered per-chunk, returns
 * the overburden AABB and pressure from load calculation.
 * - ExplosionCrackPulse: a one-shot provider that returns isExpired()=true
 * after one propagation tick; registered on explosion events.
 */
public interface ICrackSourceProvider {

    /**
     * The axis-aligned zone containing all blocks this source might crack.
     * The propagator only evaluates blocks within this zone.
     */
    AABB getZone();

    /**
     * Crack pressure this source exerts this tick.
     * <p>
     * The propagator uses this to weight block selection and determine how many
     * crack advance events to attempt per PROPAGATION_INTERVAL:
     * advances = max(1, (int)(pressure / 10))   (capped at 5)
     *
     * @return pressure ≥ 0. 0 = source inactive (no cracking but also no healing
     * suppression). Typical ranges:
     * 1–5:   low stress, slow progression
     * 10–25: moderate (e.g. bloomery near crack threshold)
     * 50+:   severe (e.g. explosion proximity, extreme overheat)
     */
    float getCrackPressure(ServerLevel level);

    /**
     * The cause type this source applies to blocks in its zone.
     * All blocks cracked by this source get this cause, which controls
     * visual appearance and heal behaviour.
     */
    CrackCause getCause();

    /**
     * Whether this source has expired and should be removed from the registry.
     * One-shot providers (explosion pulses, impact events) return true after
     * their first propagation tick.
     * <p>
     * Persistent providers (block entities, chunk structural analyzers) return false.
     */
    boolean isExpired();

    /**
     * Optional: a stable ID string for deduplication. If two providers with
     * the same sourceId are registered, the newer one replaces the older.
     * Return null to opt out of deduplication (all instances are kept).
     * <p>
     * Suggested format: "modid:type:x,y,z" for block-entity providers.
     */
    default String sourceId() {
        return null;
    }
}
