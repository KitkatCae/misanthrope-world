package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;

/**
 * Connected-component splitter for groups of blocks that have just failed,
 * been excavated, or otherwise been marked as "about to become debris."
 *
 * <h3>Where this comes from</h3>
 * Ported from {@code vox3D-fracturing}'s {@code b3F_components} /
 * {@code b3F_splitPiece} (see {@code vox3D_fracture_handoff.md} §3.3) — a
 * third-party experimental fork of Box2D v3, extended to voxel fracturing.
 * That library is unfinished and not taken as a dependency; only the
 * algorithm shape is reused here, written fresh against this project's own
 * data structures ({@link BlockPhysicsData.StructuralData}, VS2's
 * {@code ShipAssembler}).
 *
 * <h3>The actual gap this fills</h3>
 * Before this class, {@code FailureDispatcher.dispatchGroup} /
 * {@code executeFragment} took whatever {@code Set<BlockPos>} it was handed
 * and assembled it into exactly one VS2 ship, regardless of whether that set
 * was still physically contiguous. A crater carved through the middle of a
 * wall, or a stress-BFS result that happens to bridge two areas only through
 * a since-removed block, could hand over a set that's actually two (or more)
 * disconnected masses. This class splits first; the caller then decides
 * per-component whether it's too small to bother with (crumble) or becomes
 * its own ship.
 *
 * <h3>Connectivity</h3>
 * 26-connectivity (face + edge + corner) — deliberately chosen over vox3D's
 * own 6-connectivity (face-only) so two blocks touching only at an edge or
 * corner still count as attached. Face-only connectivity produced surprising
 * "disconnected" results for diagonal touches that a player would look at
 * and reasonably call one piece.
 *
 * <h3>Velocity inheritance</h3>
 * {@link #inheritedVelocity} mirrors vox3D's rigid-body kinematics
 * ({@code parentVel + parentAngularVel × (childCOM − parentCOM)}), but as of
 * this port there is no caller with an actual "parent ship" to inherit
 * from — {@code FailureDispatcher}'s fragment paths originate from static
 * terrain (no parent body at all), and {@code ImpactFractureBridge} /
 * {@code KineticImpactHandler} currently hand ship-vs-ship impacts off to
 * the crumble+crack-shell pipeline rather than creating fragment ships
 * directly. This method is here, tested and ready, for whenever a "split an
 * already-moving ship's hull" feature exists to call it — it is
 * <em>not</em> wired into anything yet. Do not assume it is in use.
 */
public final class FragmentSplitter {

    private FragmentSplitter() {
    }

    /** All 26 neighbour offsets (face + edge + corner), excluding {0,0,0}. */
    private static final int[][] NEIGHBOR_OFFSETS_26 = build26Offsets();

    private static int[][] build26Offsets() {
        List<int[]> offsets = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    offsets.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offsets.toArray(new int[0][]);
    }

    // -------------------------------------------------------------------------
    // Component splitting
    // -------------------------------------------------------------------------

    /**
     * Splits {@code positions} into its connected components under
     * 26-connectivity. Returns components sorted largest-first — the same
     * convention {@code b3F_splitPiece} uses so a caller wanting to keep the
     * biggest chunk as the "primary" fragment can just take index 0.
     *
     * <p>Pure spatial connectivity — this does not consult structural data,
     * strength, or any cutoff. Size-based culling ({@code fragmentMinSize})
     * is the caller's job, same separation vox3D itself keeps between
     * {@code b3F_components} (connectivity only) and {@code b3F_splitPiece}
     * (connectivity + cutoff + body formation).
     *
     * @param positions the candidate group (failing, excavated, or otherwise
     *                  about to be turned into debris/fragments)
     * @return list of disjoint components, largest-first; empty if
     *         {@code positions} is empty
     */
    public static List<Set<BlockPos>> splitComponents(Set<BlockPos> positions) {
        if (positions.isEmpty()) return List.of();

        Set<BlockPos> remaining = new HashSet<>(positions.size());
        for (BlockPos p : positions) remaining.add(p.immutable());

        List<Set<BlockPos>> components = new ArrayList<>();
        Deque<BlockPos> stack = new ArrayDeque<>();

        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);

            Set<BlockPos> component = new LinkedHashSet<>();
            stack.push(seed);

            while (!stack.isEmpty()) {
                BlockPos cur = stack.pop();
                component.add(cur);

                for (int[] off : NEIGHBOR_OFFSETS_26) {
                    BlockPos neighbor = cur.offset(off[0], off[1], off[2]);
                    if (remaining.remove(neighbor)) {
                        stack.push(neighbor);
                    }
                }
            }

            components.add(component);
        }

        // Early-return case: only one component and it's the whole input —
        // nothing actually split. Still worth returning through the same
        // path rather than special-casing; the caller's cost for a
        // single-element list is negligible.
        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return components;
    }

    // -------------------------------------------------------------------------
    // Center of mass
    // -------------------------------------------------------------------------

    /**
     * Mass-weighted center of mass of {@code group}, in world block-center
     * coordinates (each block's contribution centered at {@code pos + 0.5}
     * on all three axes). Weights by {@code densityKgM3} from
     * {@link BlockPhysicsRegistry} where available, falling back to a
     * generic 2400 kg/m³ (stone/concrete) for blocks with no structural data
     * — same fallback density {@code StructuralStressField.computeColumnLoad}
     * already uses for the same reason.
     *
     * <p>vox3D computes this per-component during {@code b3F_splitPiece};
     * this is that step, generalized to read real per-block density instead
     * of a uniform material density (vox3D's chunk material table is one
     * density per whole body).
     */
    public static Vector3dc centerOfMass(ServerLevel level, Set<BlockPos> group) {
        double sumX = 0, sumY = 0, sumZ = 0, totalMass = 0;

        for (BlockPos pos : group) {
            double density = 2400.0;
            if (level.isLoaded(pos)) {
                BlockPhysicsData data = BlockPhysicsRegistry.get(level.getBlockState(pos));
                if (data.structural != null) {
                    density = data.structural.densityKgM3();
                }
            }
            sumX += (pos.getX() + 0.5) * density;
            sumY += (pos.getY() + 0.5) * density;
            sumZ += (pos.getZ() + 0.5) * density;
            totalMass += density;
        }

        if (totalMass <= 0) totalMass = 1.0; // guard against empty group
        return new Vector3d(sumX / totalMass, sumY / totalMass, sumZ / totalMass);
    }

    // -------------------------------------------------------------------------
    // Velocity inheritance (NOT currently wired to any caller — see class doc)
    // -------------------------------------------------------------------------

    /**
     * Rigid-body-correct velocity a sub-region of a moving/spinning parent
     * body would have had at the moment of separation:
     * {@code parentVel + parentAngularVel × (childCOM − parentCOM)}.
     *
     * <p>Ported from vox3D's {@code b3F_splitPiece} step 6. Only meaningful
     * when {@code group} was physically part of an already-moving VS2 ship
     * before this split — see class doc for why nothing calls this yet.
     *
     * @param parentVel        parent ship's linear velocity (blocks/tick)
     * @param parentAngularVel parent ship's angular velocity (rad/tick, axis-angle vector)
     * @param parentCOM        parent ship's center of mass, world space
     * @param childCOM         this fragment's own center of mass, world space
     *                         (see {@link #centerOfMass})
     * @return inherited linear velocity for the fragment
     */
    public static Vector3dc inheritedVelocity(Vector3dc parentVel, Vector3dc parentAngularVel,
                                               Vector3dc parentCOM, Vector3dc childCOM) {
        Vector3d r = new Vector3d(childCOM).sub(new Vector3d(parentCOM));
        Vector3d omegaCrossR = new Vector3d(parentAngularVel).cross(r);
        return new Vector3d(parentVel).add(omegaCrossR);
    }
}
