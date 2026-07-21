package exp.CCnewmods.misanthrope_world.physics.mass;

/**
 * Quantizes a raw {@code density_kg_m3} value from {@code material_properties} down to a small,
 * bounded set of discrete mass tiers before handing it to VS2.
 *
 * <h3>Why this exists</h3>
 * VS2's {@code ShipInertiaDataImpl} stores per-segment mass as a
 * {@code Map<SegmentBlockPos, Double>} — any block whose mass differs from VS2's implicit uniform
 * default needs its own map entry, and that map gets sorted on ship save/network-sync
 * ({@code BodyInertiaConverter}/{@code ShipInertiaConverter}, bytecode-confirmed against
 * valkyrienskies-forge build eeaa2beb). Making {@code material_properties} the mass authority for
 * every block means nearly every block on every ship now needs an entry — quantizing doesn't shrink
 * that map (it's keyed by position, not value), but it keeps the *value space* small and bounded
 * regardless of how many distinct densities exist across the material library, which costs nothing
 * and is the responsible default in case anything downstream ever groups by mass value.
 * See {@code MWorld_Mass_Authority_v1.md} §1/§2.3.
 *
 * <h3>Why log-scale</h3>
 * Density spans roughly 1.2 (air) to 11000+ kg/m³ (lead and denser exotic materials) — a fixed
 * linear step would be far too coarse for light materials or generate hundreds of buckets at the
 * heavy end. Log-scale buckets stay proportionally even across that whole range.
 */
public final class MassQuantizer {

    /**
     * Bucket base. {@code BUCKET_COUNT} steps of this base span roughly the full density range
     * material_properties actually uses (~1 to ~11000 kg/m³). Starting conservative — fewer,
     * coarser buckets — per the open tuning question in the design doc; loosen (raise
     * BUCKET_COUNT / lower the base) if ship mass differences feel too flat in testing.
     */
    private static final double BUCKET_BASE = 1.5;

    private MassQuantizer() {
    }

    /**
     * @param densityKgM3 raw density from {@link exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData#densityKgM3}
     * @return the nearest power of {@link #BUCKET_BASE}, or 0 for non-positive input (air/vacuum)
     */
    public static double quantize(double densityKgM3) {
        if (densityKgM3 <= 0.0 || Double.isNaN(densityKgM3)) return 0.0;
        double logScale = Math.log(densityKgM3) / Math.log(BUCKET_BASE);
        return Math.pow(BUCKET_BASE, Math.round(logScale));
    }
}
