package exp.CCnewmods.misanthrope_world.crackrender.world;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.data.VeinSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates world-space crack vein paths and assigns VeinSegments to the
 * CrackStateMap entries they pass through.
 * <p>
 * ── Vein path model ───────────────────────────────────────────────────────────
 * A vein is a 3D polyline grown from an origin point in world space. As each
 * segment crosses an integer block boundary, it is clipped to that face and
 * a VeinSegment record is stored in both the exiting and entering block's
 * CrackEntry.
 * <p>
 * The path is seeded and deterministic: given the same veinId and origin, the
 * same path is reproduced. This means clients can re-generate vein geometry
 * from just the VeinSegment records without additional sync data.
 * <p>
 * ── Two entry points, one walk core ────────────────────────────────────────────
 * {@link #generateVein} is the original decorative path: called by
 * {@link CrackPropagator} on its normal 20-tick cadence, one random-direction
 * vein per call, blocks ramp up through the normal CrackEntry.advance() levels
 * over time. Unchanged behaviour from before this refactor.
 * <p>
 * {@link #generateImpactBurst} is new: several veins radiating from a single
 * point, walked synchronously (no waiting on the propagator's tick cadence),
 * with every touched block's level set instantly via
 * {@link CrackEntry#setLevelInstant} rather than the gradual advance() ramp —
 * appropriate for "this just got hit hard," not "this has been slowly
 * stressing for a while." Built for the crack-driven crater boundary (see
 * fracture handoff doc), but it's a general-purpose synchronous burst
 * generator — nothing about it is crater-specific.
 * <p>
 * Both entry points share the same underlying {@link #walkVein} stepping
 * logic (drift, curvature, block-boundary clipping) so their geometry reads
 * as the same kind of crack, just triggered differently.
 * <p>
 * ── Step size ─────────────────────────────────────────────────────────────────
 * Each step advances ~0.12–0.22 block units in a direction that drifts from
 * the previous direction by a small random amount each step. The drift
 * ensures organic-looking curves rather than straight lines. When the
 * direction component on any axis exceeds a block boundary, the path crosses
 * into the next block.
 * <p>
 * ── Cross-block bias ──────────────────────────────────────────────────────────
 * cause.crossBlockBias controls how likely the vein is to keep going after
 * crossing a block boundary. At each boundary crossing the vein has a
 * (1 - crossBlockBias) chance to terminate. This limits vein length naturally.
 */
public class VeinPropagator {

    private static final AtomicInteger NEXT_VEIN_ID = new AtomicInteger(1);

    // Maximum blocks a vein can cross before terminating (decorative default cap
    // per cause — generateImpactBurst takes its own caller-specified cap instead).
    private static final Map<CrackCause, Integer> MAX_LENGTH = Map.of(
            CrackCause.THERMAL, 6,
            CrackCause.STRUCTURAL, 12,
            CrackCause.IMPACT, 3,
            CrackCause.EROSION, 8,
            CrackCause.MAGICAL, 5
    );

    // Steps taken within a single block face before checking boundary
    private static final int STEPS_PER_BLOCK = 8;

    // ── Result type for burst generation ────────────────────────────────────

    /**
     * One generated vein's full geometry, returned immediately (same tick)
     * to the burst caller — no round-trip through {@link CrackStateMap}
     * needed to use it right away, though it's also persisted there via
     * {@link CrackStateMap#registerVeinPath} for later lookup.
     *
     * @param veinId       the assigned vein ID
     * @param blockPath    ordered blocks the vein passed through
     * @param samplePoints fine-grained world-space points along the walked
     *                     curve (finer than block resolution — one per
     *                     stepping iteration, not one per block). This is
     *                     what a boundary/occlusion test should walk against,
     *                     not blockPath, since blockPath loses the actual
     *                     curve shape within a block.
     */
    public record FractureVein(int veinId, List<BlockPos> blockPath, List<Vector3d> samplePoints) {
    }

    /**
     * Internal result of one {@link #walkVein} call, before being wrapped
     * for whichever entry point invoked it.
     */
    private record WalkResult(List<BlockPos> blockPath, List<Vector3d> samplePoints) {
    }

    // ── Decorative entry point (existing behaviour, unchanged) ─────────────────

    /**
     * Generate a new vein from originPos and assign VeinSegments to all
     * CrackEntry objects it passes through (creating entries if absent).
     * <p>
     * Called by CrackPropagator when a block first gets its crack level
     * advanced — the vein is generated at that moment and stored permanently.
     *
     * @param stateMap  the level's crack state, mutated by this call
     * @param originPos block where the vein originates
     * @param cause     crack cause (controls path behaviour and max length)
     * @param random    seeded random — use level.getRandom() on server
     * @return the veinId assigned, for debug/sync purposes
     */
    public static int generateVein(CrackStateMap stateMap,
                                   BlockPos originPos,
                                   CrackCause cause,
                                   Random random) {
        int veinId = NEXT_VEIN_ID.getAndIncrement();
        long veinSeed = random.nextLong();
        Random veinRng = new Random(veinSeed);
        int maxBlocks = MAX_LENGTH.getOrDefault(cause, 6);

        // Initial direction: random unit vector biased toward horizontal
        // (vertical cracks look wrong for most causes)
        float yaw = veinRng.nextFloat() * (float) (Math.PI * 2);
        float pitch = (veinRng.nextFloat() - 0.5f) * (float) (Math.PI * 0.4); // ±36° from horizontal
        float dx = (float) (Math.cos(pitch) * Math.cos(yaw));
        float dy = (float) (Math.sin(pitch));
        float dz = (float) (Math.cos(pitch) * Math.sin(yaw));

        float wx = originPos.getX() + 0.5f + (veinRng.nextFloat() - 0.5f) * 0.3f;
        float wy = originPos.getY() + 0.5f + (veinRng.nextFloat() - 0.5f) * 0.3f;
        float wz = originPos.getZ() + 0.5f + (veinRng.nextFloat() - 0.5f) * 0.3f;

        WalkResult result = walkVein(stateMap, originPos, wx, wy, wz, dx, dy, dz,
                veinId, veinSeed, veinRng, maxBlocks, cause,
                -1 /* no instant level — use normal PRISTINE creation */,
                0L, false /* no sample points needed for decorative use */);

        stateMap.registerVeinPath(veinId, result.blockPath());
        return veinId;
    }

    // ── Synchronous burst entry point (new) ─────────────────────────────────

    /**
     * Generates {@code veinCount} veins radiating outward from {@code center}
     * all at once, synchronously — no waiting on {@link CrackPropagator}'s
     * 20-tick cadence. Every block touched has its CrackEntry set directly to
     * {@code instantLevel} via {@link CrackEntry#setLevelInstant}.
     * <p>
     * Directions are spread evenly over the full sphere via a Fibonacci
     * lattice, then jittered per-vein so the result doesn't look
     * mechanically uniform. If you need a hemispherical spread (e.g. biased
     * away from an impact surface rather than in all directions), filter
     * {@code veinCount} candidate directions before calling, or call this
     * with a smaller count and bias direction generation externally — this
     * method itself stays direction-source-agnostic on purpose so it isn't
     * coupled to any one caller's geometry.
     *
     * @param level        the server level (needed for the RNG seed source)
     * @param stateMap     the level's crack state, mutated by this call
     * @param center       world-space point veins radiate from
     * @param veinCount    how many veins to generate
     * @param maxBlocks    max blocks per vein (caller decides — bigger impacts,
     *                     longer reach)
     * @param cause        crack cause (visual style + crossBlockBias)
     * @param instantLevel crack level every touched block is set to
     *                     (typically {@link CrackEntry#LEVEL_SEVERE})
     * @param gameTick     current server tick, recorded on each touched entry
     * @param random       seeded random — caller controls determinism
     * @return one {@link FractureVein} per generated vein, with both the
     *         block path and fine-grained sample points populated
     */
    public static List<FractureVein> generateImpactBurst(
            net.minecraft.server.level.ServerLevel level,
            CrackStateMap stateMap,
            Vector3d center,
            int veinCount,
            int maxBlocks,
            CrackCause cause,
            int instantLevel,
            long gameTick,
            Random random) {

        List<FractureVein> results = new ArrayList<>(veinCount);
        BlockPos originBlock = new BlockPos(
                (int) Math.floor(center.x), (int) Math.floor(center.y), (int) Math.floor(center.z));

        for (int i = 0; i < veinCount; i++) {
            int veinId = NEXT_VEIN_ID.getAndIncrement();
            long veinSeed = random.nextLong();
            Random veinRng = new Random(veinSeed);

            // Fibonacci lattice direction for even sphere coverage, then jitter.
            float[] dir = fibonacciSphereDirection(i, veinCount, veinRng);

            float wx = (float) center.x + (veinRng.nextFloat() - 0.5f) * 0.2f;
            float wy = (float) center.y + (veinRng.nextFloat() - 0.5f) * 0.2f;
            float wz = (float) center.z + (veinRng.nextFloat() - 0.5f) * 0.2f;

            WalkResult result = walkVein(stateMap, originBlock, wx, wy, wz,
                    dir[0], dir[1], dir[2], veinId, veinSeed, veinRng, maxBlocks, cause,
                    instantLevel, gameTick, true /* collect sample points for boundary use */);

            stateMap.registerVeinPath(veinId, result.blockPath());
            results.add(new FractureVein(veinId, result.blockPath(), result.samplePoints()));
        }

        return results;
    }

    /**
     * Even-ish direction sampling over the unit sphere via a Fibonacci
     * lattice, jittered per-index so a burst of veins doesn't look like a
     * mechanically perfect starburst. Deterministic given the same index/
     * count/rng-state, since {@code veinRng} is the vein's own already-seeded
     * Random.
     */
    private static float[] fibonacciSphereDirection(int index, int total, Random jitterRng) {
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        double y = 1.0 - (index / (double) Math.max(1, total - 1)) * 2.0; // 1 → -1
        double radiusAtY = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double theta = goldenAngle * index;

        double x = Math.cos(theta) * radiusAtY;
        double z = Math.sin(theta) * radiusAtY;

        // Small jitter so veins from repeated impacts at the same count don't
        // all point in bit-identical directions.
        double jitter = 0.12;
        x += (jitterRng.nextDouble() - 0.5) * jitter;
        y += (jitterRng.nextDouble() - 0.5) * jitter;
        z += (jitterRng.nextDouble() - 0.5) * jitter;

        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6) len = 1.0;
        return new float[]{(float) (x / len), (float) (y / len), (float) (z / len)};
    }

    // ── Shared walk core ─────────────────────────────────────────────────────

    /**
     * Walks a single vein polyline from a given start point/direction,
     * stepping with drift, clipping at block boundaries, and emitting
     * VeinSegments into {@code stateMap}. Shared by both entry points — see
     * class doc for what differs between them.
     *
     * @param instantLevel if {@code >= 0}, every touched block's CrackEntry
     *                     is force-set to this level via
     *                     {@link CrackEntry#setLevelInstant}. If negative,
     *                     entries are left at whatever they already were (or
     *                     created fresh at {@link CrackEntry#LEVEL_PRISTINE}
     *                     if absent) — the original decorative behaviour,
     *                     where level is owned by {@code CrackPropagator}'s
     *                     own advance() calls, not by vein generation.
     */
    private static WalkResult walkVein(CrackStateMap stateMap,
                                       BlockPos originPos,
                                       float startWx, float startWy, float startWz,
                                       float initDx, float initDy, float initDz,
                                       int veinId, long veinSeed, Random veinRng,
                                       int maxBlocks, CrackCause cause,
                                       int instantLevel, long gameTick,
                                       boolean collectSamplePoints) {

        float wx = startWx, wy = startWy, wz = startWz;
        float dx = initDx, dy = initDy, dz = initDz;

        BlockPos currentBlock = new BlockPos((int) Math.floor(wx), (int) Math.floor(wy), (int) Math.floor(wz));
        int blocksVisited = 0;

        Direction entryFace = null;
        float entryU = 0.5f, entryV = 0.5f;

        List<BlockPos> blockPath = new ArrayList<>(maxBlocks);
        List<Vector3d> samplePoints = collectSamplePoints ? new ArrayList<>(maxBlocks * STEPS_PER_BLOCK) : List.of();
        if (collectSamplePoints) samplePoints.add(new Vector3d(wx, wy, wz));

        while (blocksVisited < maxBlocks) {
            Direction exitFace = null;
            float exitU = 0.5f, exitV = 0.5f;
            float blockExitX = -1, blockExitY = -1, blockExitZ = -1;

            int bx = currentBlock.getX();
            int by = currentBlock.getY();
            int bz = currentBlock.getZ();

            for (int step = 0; step < STEPS_PER_BLOCK * 2; step++) {
                float driftYaw = (veinRng.nextFloat() - 0.5f) * (float) (Math.PI * 0.4);
                float driftPitch = (veinRng.nextFloat() - 0.5f) * (float) (Math.PI * 0.15);
                float[] rotated = rotate(dx, dy, dz, driftYaw, driftPitch);
                dx = rotated[0];
                dy = rotated[1];
                dz = rotated[2];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0) {
                    dx /= len;
                    dy /= len;
                    dz /= len;
                }

                float stepLen = 0.12f + veinRng.nextFloat() * 0.10f;
                wx += dx * stepLen;
                wy += dy * stepLen;
                wz += dz * stepLen;

                if (collectSamplePoints) samplePoints.add(new Vector3d(wx, wy, wz));

                int nx = (int) Math.floor(wx);
                int ny = (int) Math.floor(wy);
                int nz = (int) Math.floor(wz);

                if (nx != bx || ny != by || nz != bz) {
                    Direction crossed = computeCrossedFace(bx, by, bz, nx, ny, nz);
                    exitFace = crossed;
                    float[] uv = faceUV(crossed, wx - bx, wy - by, wz - bz);
                    exitU = uv[0];
                    exitV = uv[1];
                    blockExitX = wx;
                    blockExitY = wy;
                    blockExitZ = wz;
                    break;
                }
            }

            CrackEntry entry = stateMap.get(currentBlock);
            if (entry == null) {
                entry = new CrackEntry(currentBlock, cause, CrackEntry.LEVEL_PRISTINE);
                stateMap.put(entry);
            }
            if (instantLevel >= 0) {
                entry.setLevelInstant(instantLevel, gameTick);
            }

            VeinSegment segment = new VeinSegment(
                    veinId,
                    entryFace, entryU, entryV,
                    exitFace, exitU, exitV,
                    veinSeed ^ (long) (blocksVisited * 0x9E3779B9)
            );
            entry.addSegment(segment);
            stateMap.put(entry);
            blockPath.add(currentBlock);

            blocksVisited++;

            if (exitFace == null) break; // terminus — no further propagation

            if (veinRng.nextFloat() > cause.crossBlockBias) break;

            currentBlock = currentBlock.relative(exitFace);
            entryFace = exitFace.getOpposite();

            float[] newEntryUV = faceUV(entryFace,
                    blockExitX - currentBlock.getX(),
                    blockExitY - currentBlock.getY(),
                    blockExitZ - currentBlock.getZ());
            entryU = newEntryUV[0];
            entryV = newEntryUV[1];
        }

        return new WalkResult(blockPath, samplePoints);
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    /**
     * Given that we moved from block (bx,by,bz) to (nx,ny,nz), determine
     * which face of (bx,by,bz) was crossed.
     */
    private static Direction computeCrossedFace(int bx, int by, int bz,
                                                int nx, int ny, int nz) {
        if (nx > bx) return Direction.EAST;
        if (nx < bx) return Direction.WEST;
        if (ny > by) return Direction.UP;
        if (ny < by) return Direction.DOWN;
        if (nz > bz) return Direction.SOUTH;
        return Direction.NORTH;
    }

    /**
     * Compute face-local UV [0,1]² for a world point relative to a block
     * corner (lx,ly,lz in [0,1]) on the given face.
     * <p>
     * Face UV convention:
     * U = face-local X (right), V = face-local Y (up)
     * Both clamped to [0.02, 0.98] to avoid boundary artifacts.
     */
    private static float[] faceUV(Direction face, float lx, float ly, float lz) {
        float u, v;
        switch (face) {
            case UP, DOWN -> {
                u = lx;
                v = lz;
            }
            case NORTH, SOUTH -> {
                u = lx;
                v = ly;
            }
            default -> {
                u = lz;
                v = ly;
            } // EAST, WEST
        }
        u = Math.max(0.02f, Math.min(0.98f, u));
        v = Math.max(0.02f, Math.min(0.98f, v));
        return new float[]{u, v};
    }

    /**
     * Rudimentary yaw+pitch rotation of a direction vector.
     * Returns [dx, dy, dz] after applying the rotation.
     */
    private static float[] rotate(float dx, float dy, float dz,
                                  float yaw, float pitch) {
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float nx = dx * cosY - dz * sinY;
        float nz = dx * sinY + dz * cosY;
        dx = nx;
        dz = nz;

        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);
        float ny2 = dy * cosP - dz * sinP;
        float nz2 = dy * sinP + dz * cosP;
        dy = ny2;
        dz = nz2;

        return new float[]{dx, dy, dz};
    }
}
