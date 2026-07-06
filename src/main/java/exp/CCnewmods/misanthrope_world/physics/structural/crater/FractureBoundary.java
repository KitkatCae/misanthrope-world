package exp.CCnewmods.misanthrope_world.physics.structural.crater;

import exp.CCnewmods.misanthrope_world.crackrender.world.VeinPropagator;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

import java.util.List;

/**
 * Decides whether a candidate block belongs inside a crack-driven crater.
 * <p>
 * ── Hybrid model ──────────────────────────────────────────────────────────────
 * Two independent contributions combine into one effective radius, evaluated
 * per-candidate, per-direction from the impact center:
 * <p>
 * <ol>
 *   <li><b>Rough cut from fracture polylines</b> — for a candidate roughly in
 *       the direction of a generated {@link VeinPropagator.FractureVein}, the
 *       effective radius extends out to (a little past) that vein's farthest
 *       reach. This is what makes the crater actually follow real crack
 *       lines instead of a smooth sphere: it swallows whatever a nearby
 *       crack already reached, rather than stopping short of it or ignoring
 *       it.</li>
 *   <li><b>Value-noise edge jitter</b> — a cheap, dependency-free 3D value
 *       noise sampled at the candidate's world position perturbs the radius
 *       by up to ± {@code edgeJitterBlocks} in every direction, including
 *       ones with no nearby vein. This is what keeps the edge from looking
 *       perfectly smooth in the (common) directions a vein didn't happen to
 *       reach — matching the roughness a real fracture surface has even
 *       where the visible crack lines are sparse.</li>
 * </ol>
 * <p>
 * This class is pure geometry — it doesn't touch the world, doesn't destroy
 * blocks, doesn't know about {@code ImpactHandler}. A caller builds one
 * instance per impact (passing in the {@link VeinPropagator.FractureVein}
 * list from {@link VeinPropagator#generateImpactBurst}) and calls
 * {@link #isInside} once per candidate block in its excavation scan —
 * dropping in as a straight replacement for whatever ellipsoid test the
 * caller used before.
 */
public final class FractureBoundary {

    /**
     * cos(30°) — how tightly a candidate's direction from center must align
     * with a vein's direction to count as "along that crack" for the rough-cut
     * extension. Loose enough that a vein's natural curvature (see
     * {@code VeinPropagator}'s drift-per-step model) doesn't get excluded by
     * an overly strict cone.
     */
    private static final double ALIGNMENT_COS_THRESHOLD = 0.866;

    /**
     * Extra reach past a vein's actual tip, in blocks — so the crater
     * visibly swallows the crack it followed rather than stopping exactly
     * at its last sample point (which would look like the crack was cut
     * off, not the source of the break).
     */
    private static final double VEIN_TIP_PADDING = 0.75;

    private final Vector3d center;
    private final List<VeinPropagator.FractureVein> veins;
    private final double baseRadius;
    private final long noiseSeed;
    private final double edgeJitterBlocks;

    /**
     * @param center           impact center, world space
     * @param veins            fracture veins generated for this impact (see
     *                         {@link VeinPropagator#generateImpactBurst})
     * @param baseRadius       fallback radius in directions with no aligned
     *                         vein — roughly what the old ellipsoid test's
     *                         radius would have been
     * @param noiseSeed        seed for the edge-jitter noise; derive from the
     *                         impact position/tick so repeated impacts at the
     *                         same spot don't jitter identically
     * @param edgeJitterBlocks max noise-driven radius perturbation, in blocks.
     *                         ~0.4–0.8 reads as "rough edge" without looking
     *                         noisy/chaotic; tune per material brittleness if
     *                         desired (not wired to fractureToughness yet —
     *                         left as a caller-supplied constant for now)
     */
    public FractureBoundary(Vector3d center, List<VeinPropagator.FractureVein> veins,
                            double baseRadius, long noiseSeed, double edgeJitterBlocks) {
        this.center = new Vector3d(center);
        this.veins = veins;
        this.baseRadius = baseRadius;
        this.noiseSeed = noiseSeed;
        this.edgeJitterBlocks = edgeJitterBlocks;
    }

    /**
     * Whether {@code candidate} falls inside the crater boundary. Replaces
     * the ellipsoid {@code normPerp + normAxial² > 1.0} test directly — same
     * call shape (one boolean per candidate block), different geometry
     * underneath.
     */
    public boolean isInside(BlockPos candidate) {
        Vector3d p = new Vector3d(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
        double dist = p.distance(center);
        return dist <= effectiveRadius(p, dist);
    }

    /**
     * The effective radius in the direction of {@code candidatePoint},
     * combining the vein rough-cut and noise jitter. Exposed separately from
     * {@link #isInside} in case a caller wants the raw value (e.g. for a
     * debug visualization of the boundary surface).
     */
    public double effectiveRadius(Vector3d candidatePoint, double distFromCenter) {
        double reach = baseRadius;

        if (distFromCenter > 1e-6) {
            Vector3d dirToCandidate = new Vector3d(candidatePoint).sub(center).div(distFromCenter);

            for (VeinPropagator.FractureVein vein : veins) {
                List<Vector3d> pts = vein.samplePoints();
                if (pts.isEmpty()) continue;

                Vector3d tip = pts.get(pts.size() - 1);
                Vector3d veinDir = new Vector3d(tip).sub(center);
                double veinLen = veinDir.length();
                if (veinLen < 1e-6) continue;
                veinDir.div(veinLen);

                double alignment = veinDir.dot(dirToCandidate);
                if (alignment >= ALIGNMENT_COS_THRESHOLD) {
                    reach = Math.max(reach, veinLen + VEIN_TIP_PADDING);
                }
            }
        }

        double noise = valueNoise3D(candidatePoint.x, candidatePoint.y, candidatePoint.z, noiseSeed);
        reach += (noise * 2.0 - 1.0) * edgeJitterBlocks;

        return reach;
    }

    // ── Cheap dependency-free 3D value noise ────────────────────────────────

    /**
     * Trilinearly-interpolated value noise over a unit lattice, hashed
     * corners. No external noise library — this project doesn't currently
     * depend on one, and this is the only place that needs noise so far.
     * Deterministic given the same (x,y,z,seed). Returns [0,1].
     */
    private static double valueNoise3D(double x, double y, double z, long seed) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double yf = y - yi;
        double zf = z - zi;

        double c000 = hash3(xi, yi, zi, seed);
        double c100 = hash3(xi + 1, yi, zi, seed);
        double c010 = hash3(xi, yi + 1, zi, seed);
        double c110 = hash3(xi + 1, yi + 1, zi, seed);
        double c001 = hash3(xi, yi, zi + 1, seed);
        double c101 = hash3(xi + 1, yi, zi + 1, seed);
        double c011 = hash3(xi, yi + 1, zi + 1, seed);
        double c111 = hash3(xi + 1, yi + 1, zi + 1, seed);

        double sx = smooth(xf);
        double sy = smooth(yf);
        double sz = smooth(zf);

        double x00 = lerp(c000, c100, sx);
        double x10 = lerp(c010, c110, sx);
        double x01 = lerp(c001, c101, sx);
        double x11 = lerp(c011, c111, sx);

        double y0 = lerp(x00, x10, sy);
        double y1 = lerp(x01, x11, sy);

        return lerp(y0, y1, sz);
    }

    /** Integer-lattice hash → [0,1], via a standard 64-bit avalanche mix. */
    private static double hash3(int x, int y, int z, long seed) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return (h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
