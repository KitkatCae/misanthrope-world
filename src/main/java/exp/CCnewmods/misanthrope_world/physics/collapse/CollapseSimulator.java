package exp.CCnewmods.misanthrope_world.physics.collapse;

import net.minecraft.core.Direction;

import java.util.Random;

/**
 * Pure server-side density field simulator for lattice collapse.
 *
 * <p>The density field is a 5×5×5 float array where 1.0 = full material,
 * 0.0 = void. Index layout: {@code field[x][y][z]}, x/y/z in [0,4].
 *
 * <p>Each call to {@link #step} advances the simulation by one tick and
 * returns a new field array. The method is stateless — same inputs always
 * produce the same output — making it deterministic and easy to test.
 *
 * <h3>Physics model (per tick)</h3>
 * <ol>
 *   <li><b>Fracture planes</b> — 1–3 planar cuts oriented toward the failure
 *       direction, each reducing density along the plane by a per-tick amount.
 *       The planes drift slightly each tick (controlled by {@code seed}) so
 *       cracks don't look mechanical.</li>
 *   <li><b>Gravitational compaction</b> — material flows downward: each tick,
 *       a fraction of density from the upper half transfers to the lower half,
 *       weighted by the failure direction so ceiling collapses fall down and
 *       wall breaches lean sideways.</li>
 *   <li><b>Pressure wave</b> — a radial density pulse from the field center
 *       bounces off the 5-cell walls. Each bounce inverts phase, creating
 *       unpredictable local voids and clumps. Amplitude decays each tick.</li>
 *   <li><b>Global drain</b> — the entire field is multiplied by
 *       {@code (1 - 1/implodeDurationTicks)} each tick so the block reaches
 *       zero density exactly at the end of the animation.</li>
 * </ol>
 */
public final class CollapseSimulator {

    public static final int GRID = 5;

    private CollapseSimulator() {
    }

    /**
     * Returns the initial density field for a new collapse — a full unit cube.
     */
    public static float[][][] initialField() {
        float[][][] f = new float[GRID][GRID][GRID];
        for (int x = 0; x < GRID; x++)
            for (int y = 0; y < GRID; y++)
                for (int z = 0; z < GRID; z++)
                    f[x][y][z] = 1.0f;
        return f;
    }

    /**
     * Advances the density field by one tick.
     *
     * @param current           current density field (not mutated)
     * @param tick              current tick within the collapse (0 = first tick)
     * @param implodeTicks      total duration of the collapse in ticks
     * @param seed              stable random seed (from BlockPos.asLong())
     * @param failureDir        direction the structural load came from
     * @param fractureToughness material fracture toughness [0,1]; lower = more planes, sharper cuts
     * @return new density field after this tick's simulation step
     */
    public static float[][][] step(float[][][] current, int tick, int implodeTicks,
                                   long seed, Direction failureDir, double fractureToughness) {
        float[][][] next = copy(current);
        Random rng = new Random(seed ^ ((long) tick * 0x9E3779B97F4A7C15L));

        // ── 1. Fracture planes ──────────────────────────────────────────────
        int numPlanes = fractureToughness < 0.3 ? 3 : fractureToughness < 0.6 ? 2 : 1;
        float planeStrength = 0.08f + (1f - (float) fractureToughness) * 0.12f;

        // Primary plane orientation driven by failure direction
        int[] failAxis = dirToAxis(failureDir);     // {dx, dy, dz}
        int[] perpAxis1 = perpendicular(failAxis, 0);
        int[] perpAxis2 = perpendicular(failAxis, 1);

        for (int p = 0; p < numPlanes; p++) {
            // Plane offset drifts with tick + seed for unpredictability
            float planeFrac = 0.5f + (float) (rng.nextGaussian() * 0.15);
            planeFrac = Math.max(0.1f, Math.min(0.9f, planeFrac));
            float planePos = planeFrac * (GRID - 1); // position along failure axis

            // Small random tilt per plane
            float tiltA = (float) (rng.nextGaussian() * 0.3);
            float tiltB = (float) (rng.nextGaussian() * 0.3);

            for (int x = 0; x < GRID; x++) {
                for (int y = 0; y < GRID; y++) {
                    for (int z = 0; z < GRID; z++) {
                        float ax = failAxis[0] * x + failAxis[1] * y + failAxis[2] * z;
                        float tilt = tiltA * (perpAxis1[0] * x + perpAxis1[1] * y + perpAxis1[2] * z)
                                + tiltB * (perpAxis2[0] * x + perpAxis2[1] * y + perpAxis2[2] * z);
                        float dist = Math.abs(ax + tilt - planePos);
                        if (dist < 1.2f) {
                            float cut = planeStrength * (1f - dist / 1.2f);
                            next[x][y][z] = Math.max(0f, next[x][y][z] - cut);
                        }
                    }
                }
            }
        }

        // ── 2. Gravitational compaction ─────────────────────────────────────
        // Failure direction determines which way material "falls"
        // The axis component of the failure direction is the gravity axis
        float gravStrength = 0.03f * (float) tick / implodeTicks; // increases over time
        int[] gravAxis = dirToAxis(failureDir); // material falls toward failure dir

        for (int x = 0; x < GRID; x++) {
            for (int y = 0; y < GRID; y++) {
                for (int z = 0; z < GRID; z++) {
                    int nx = x + gravAxis[0];
                    int ny = y + gravAxis[1];
                    int nz = z + gravAxis[2];
                    if (nx < 0 || nx >= GRID || ny < 0 || ny >= GRID || nz < 0 || nz >= GRID)
                        continue;
                    float flow = next[x][y][z] * gravStrength;
                    next[x][y][z] -= flow;
                    next[nx][ny][nz] = Math.min(1f, next[nx][ny][nz] + flow * 0.7f); // 30% lost
                }
            }
        }

        // ── 3. Pressure wave ─────────────────────────────────────────────────
        // A radial wave from the center, phase-inverted each tick
        float wavePhase = (tick % 2 == 0) ? 1f : -1f;
        float waveDecay = Math.max(0f, 1f - (float) tick / (implodeTicks * 0.7f));
        float waveAmp = 0.04f * waveDecay * wavePhase;
        float cx = 2f, cy = 2f, cz = 2f; // center of 5×5×5

        for (int x = 0; x < GRID; x++) {
            for (int y = 0; y < GRID; y++) {
                for (int z = 0; z < GRID; z++) {
                    float dx = x - cx, dy = y - cy, dz = z - cz;
                    float r = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    // Gaussian pulse centred at r ≈ 1.5 (inner shell), moving outward
                    float waveCentre = 1.5f + tick * 0.15f;
                    float pulse = (float) Math.exp(-Math.pow(r - waveCentre, 2) / 0.8f);
                    next[x][y][z] = Math.max(0f, Math.min(1f,
                            next[x][y][z] + waveAmp * pulse));
                }
            }
        }

        // ── 4. Global drain ──────────────────────────────────────────────────
        float drainFactor = 1f - 1f / Math.max(1, implodeTicks);
        // Accelerate drain in the final quarter
        if (tick > implodeTicks * 0.75f) {
            drainFactor *= 0.85f;
        }
        for (int x = 0; x < GRID; x++)
            for (int y = 0; y < GRID; y++)
                for (int z = 0; z < GRID; z++)
                    next[x][y][z] = Math.max(0f, next[x][y][z] * drainFactor);

        return next;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float[][][] copy(float[][][] src) {
        float[][][] dst = new float[GRID][GRID][GRID];
        for (int x = 0; x < GRID; x++)
            for (int y = 0; y < GRID; y++)
                System.arraycopy(src[x][y], 0, dst[x][y], 0, GRID);
        return dst;
    }

    /**
     * Convert a Direction to a unit axis vector {dx,dy,dz}.
     */
    static int[] dirToAxis(Direction dir) {
        return new int[]{dir.getStepX(), dir.getStepY(), dir.getStepZ()};
    }

    /**
     * Returns a vector perpendicular to {@code axis}.
     * {@code which} selects between the two perpendicular choices.
     */
    static int[] perpendicular(int[] axis, int which) {
        // Find first non-zero component and rotate
        if (axis[0] != 0) return which == 0 ? new int[]{0, 1, 0} : new int[]{0, 0, 1};
        if (axis[1] != 0) return which == 0 ? new int[]{1, 0, 0} : new int[]{0, 0, 1};
        return which == 0 ? new int[]{1, 0, 0} : new int[]{0, 1, 0};
    }
}
