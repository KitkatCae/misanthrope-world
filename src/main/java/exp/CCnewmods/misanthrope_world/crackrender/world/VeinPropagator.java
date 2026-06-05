package exp.CCnewmods.misanthrope_world.crackrender.world;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.data.VeinSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates world-space crack vein paths and assigns VeinSegments to the
 * CrackStateMap entries they pass through.
 * <p>
 * ── Vein path model ───────────────────────────────────────────────────────────
 * A vein is a 3D polyline grown from an origin block in world space. As each
 * segment crosses an integer block boundary, it is clipped to that face and
 * a VeinSegment record is stored in both the exiting and entering block's
 * CrackEntry.
 * <p>
 * The path is seeded and deterministic: given the same veinId and origin, the
 * same path is reproduced. This means clients can re-generate vein geometry
 * from just the VeinSegment records without additional sync data.
 * <p>
 * ── Step size ─────────────────────────────────────────────────────────────────
 * Each step advances ~0.15–0.30 block units in a direction that drifts from
 * the previous direction by ±40°. The drift ensures organic-looking curves
 * rather than straight lines. When the direction component on any axis exceeds
 * a block boundary, the path crosses into the next block.
 * <p>
 * ── Cross-block bias ──────────────────────────────────────────────────────────
 * cause.crossBlockBias controls how likely the vein is to keep going after
 * crossing a block boundary. At each boundary crossing the vein has a
 * (1 - crossBlockBias) chance to terminate. This limits vein length naturally.
 * <p>
 * ── Max vein length ───────────────────────────────────────────────────────────
 * Hard cap at MAX_BLOCKS_PER_VEIN to prevent runaway propagation.
 * STRUCTURAL veins cap at 12, THERMAL at 6, IMPACT at 3, EROSION at 8.
 */
public class VeinPropagator {

    private static final AtomicInteger NEXT_VEIN_ID = new AtomicInteger(1);

    // Maximum blocks a vein can cross before terminating
    private static final Map<CrackCause, Integer> MAX_LENGTH = Map.of(
            CrackCause.THERMAL, 6,
            CrackCause.STRUCTURAL, 12,
            CrackCause.IMPACT, 3,
            CrackCause.EROSION, 8,
            CrackCause.MAGICAL, 5
    );

    // Steps taken within a single block face before checking boundary
    private static final int STEPS_PER_BLOCK = 8;

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

        // Current world-space position (float precision, relative to origin block corner)
        float wx = originPos.getX() + 0.5f + (veinRng.nextFloat() - 0.5f) * 0.3f;
        float wy = originPos.getY() + 0.5f + (veinRng.nextFloat() - 0.5f) * 0.3f;
        float wz = originPos.getZ() + 0.5f + (veinRng.nextFloat() - 0.5f) * 0.3f;

        BlockPos currentBlock = new BlockPos((int) Math.floor(wx), (int) Math.floor(wy), (int) Math.floor(wz));
        int blocksVisited = 0;

        // Entry state for the origin block
        Direction entryFace = null;
        float entryU = 0.5f, entryV = 0.5f;

        while (blocksVisited < maxBlocks) {
            // Step within current block until we exit or exceed steps
            float blockExitX = -1, blockExitY = -1, blockExitZ = -1;
            Direction exitFace = null;
            float exitU = 0.5f, exitV = 0.5f;

            // Block integer bounds
            int bx = currentBlock.getX();
            int by = currentBlock.getY();
            int bz = currentBlock.getZ();

            for (int step = 0; step < STEPS_PER_BLOCK * 2; step++) {
                // Drift direction
                float driftYaw = (veinRng.nextFloat() - 0.5f) * (float) (Math.PI * 0.4);
                float driftPitch = (veinRng.nextFloat() - 0.5f) * (float) (Math.PI * 0.15);
                dx = rotate(dx, dy, dz, driftYaw, driftPitch)[0];
                dy = rotate(dx, dy, dz, driftYaw, driftPitch)[1];
                dz = rotate(dx, dy, dz, driftYaw, driftPitch)[2];
                // Renormalize
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

                // Check if we've left the current block
                int nx = (int) Math.floor(wx);
                int ny = (int) Math.floor(wy);
                int nz = (int) Math.floor(wz);

                if (nx != bx || ny != by || nz != bz) {
                    // Crossed a boundary — compute which face and UV
                    Direction crossed = computeCrossedFace(bx, by, bz, nx, ny, nz);
                    exitFace = crossed;
                    // UV on the exit face in [0,1]
                    float[] uv = faceUV(crossed, wx - bx, wy - by, wz - bz);
                    exitU = uv[0];
                    exitV = uv[1];
                    blockExitX = wx;
                    blockExitY = wy;
                    blockExitZ = wz;
                    break;
                }
            }

            // Ensure the entry exists in the state map
            CrackEntry entry = stateMap.get(currentBlock);
            if (entry == null) {
                // Don't create entries here — VeinPropagator only annotates
                // existing entries. The propagator creates entries first,
                // then calls generateVein. For blocks mid-path that don't
                // have entries yet, we still add the segment so the geometry
                // is correct even before cracking advance reaches them.
                entry = new CrackEntry(currentBlock, cause, CrackEntry.LEVEL_PRISTINE);
                stateMap.put(entry);
            }

            // Create the segment for this block
            VeinSegment segment = new VeinSegment(
                    veinId,
                    entryFace, entryU, entryV,
                    exitFace, exitU, exitV,
                    veinSeed ^ (long) (blocksVisited * 0x9E3779B9)
            );
            entry.addSegment(segment);
            stateMap.put(entry); // mark dirty

            blocksVisited++;

            if (exitFace == null) break; // terminus — no further propagation

            // Cross-block bias check
            if (veinRng.nextFloat() > cause.crossBlockBias) break;

            // Move to next block
            currentBlock = currentBlock.relative(exitFace);
            entryFace = exitFace.getOpposite();

            // Entry UV on the new block = same world position, other face
            float[] newEntryUV = faceUV(entryFace,
                    blockExitX - currentBlock.getX(),
                    blockExitY - currentBlock.getY(),
                    blockExitZ - currentBlock.getZ());
            entryU = newEntryUV[0];
            entryV = newEntryUV[1];
        }

        return veinId;
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
        // Yaw (rotate around Y)
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float nx = dx * cosY - dz * sinY;
        float nz = dx * sinY + dz * cosY;
        dx = nx;
        dz = nz;

        // Pitch (rotate around local right = Z cross up)
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);
        float ny2 = dy * cosP - dz * sinP;
        float nz2 = dy * sinP + dz * cosP;
        dy = ny2;
        dz = nz2;

        return new float[]{dx, dy, dz};
    }
}
