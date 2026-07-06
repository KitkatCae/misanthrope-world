package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sustained structural load evaluation for VS2 ships — the ship-side half of
 * the "shared core, world and ship are thin callers" architecture.
 * {@link StructuralStressField} owns the world-terrain half (column load
 * walking a fixed +Y direction); this class owns the ship half, where
 * gravity's "down" isn't fixed at all — it rotates, accelerates, and spins
 * with the hull.
 *
 * <h3>Ship blocks live at real BlockPos coordinates</h3>
 * A VS2 ship's blocks aren't stored in some abstract ship-relative data
 * structure — they occupy real {@link LevelChunk} sections in the same
 * {@link ServerLevel}, at whatever storage-space coordinates VS2 assigned
 * the ship (see {@code StructuralShipDisassembler}'s {@code shipPos}/
 * {@code worldPos} distinction, which relies on exactly this). That means
 * {@code level.getBlockState(pos)} and {@code level.isLoaded(pos)} work
 * identically for ship-local positions as they do for terrain — the only
 * things that differ are *which direction is "down"* at a given position,
 * and *which ship (if any) a position belongs to*, which is why
 * {@link DynamicStressTracker} keys on {@code (shipId, pos)} rather than
 * {@code pos} alone.
 *
 * <h3>Effective local "down"</h3>
 * At a given ship-local position, three things contribute to the load a
 * block feels, all transformed into ship-local space via
 * {@link ShipTransform#transformDirectionNoScalingFromWorldToShip}:
 * <ol>
 *   <li><b>Gravity</b> — always "1 g" by definition; this is what
 *       {@code G_GAME} in {@link StructuralStressField} already calibrates,
 *       so a resting, non-rotating ship reduces to exactly the same load
 *       math as an equivalent stack of terrain blocks.</li>
 *   <li><b>Linear acceleration</b> (d'Alembert's principle) — a ship
 *       speeding up or slowing down feels an inertial pseudo-force opposite
 *       its acceleration, on top of gravity. Tracked per-ship as
 *       {@code (velocity_now − velocity_previous_evaluation) / Δticks}.</li>
 *   <li><b>Centrifugal load</b> — {@code -ω × (ω × r)}, where {@code r} is a
 *       block's offset from the ship's center of mass
 *       ({@code ShipInertiaData.getCenterOfMassInShip()}) and {@code ω} is
 *       angular velocity. This is <em>per-block</em> (depends on {@code r}),
 *       not a single ship-wide vector like the other two — a spinning ring
 *       station feels real outward load at its rim that its hub doesn't.</li>
 * </ol>
 * Acceleration and centrifugal terms are real blocks/tick² values;
 * {@link #REAL_G_BLOCKS_PER_TICK2} converts them into "how many g's" before
 * combining with gravity's fixed 1.0 g, so the final combined magnitude is a
 * dimensionless g-multiple that then scales {@code density × G_GAME} the
 * same way {@link StructuralStressField#computeColumnLoad} does per block.
 * <p>
 * <b>Frame assumption worth flagging:</b> {@code Ship.getAngularVelocity()}
 * carries no unit/frame annotation in the compiled API (nothing survives to
 * bytecode that would confirm this), so this assumes it's expressed in
 * world-frame, matching the convention {@code Ship.getVelocity()} documents
 * elsewhere in this codebase. If spinning ships feel structurally wrong
 * (too weak or too strong at the rim), this is the first assumption to
 * re-examine.
 *
 * <h3>Ray-march, not a straight column</h3>
 * Terrain's column load walks a simple {@code pos.above()} loop because
 * "down" is always +Y. A ship's effective down can point in any direction,
 * so the walk here is a continuous 3D ray-march — the same stepping
 * approach {@code VeinPropagator} already uses for crack veins — rather than
 * an integer-grid walk.
 *
 * <h3>Failure execution needs nothing new</h3>
 * Once a ship-local position is found over threshold,
 * {@link StructuralStressField#connectedFailureBFS(ServerLevel, BlockPos, double, java.util.function.ToDoubleFunction)}
 * (with this class's own ship-local stress function) finds the connected
 * over-stressed group, and {@link FailureDispatcher#dispatchGroup} handles
 * it exactly as it would for terrain — {@code FragmentSplitter} and
 * {@code ShipFragmentLauncher} don't know or care whether a {@code BlockPos}
 * used to belong to a ship; a piece shearing off a stressed hull becomes its
 * own new sub-ship through the identical path a cliff face does.
 *
 * <h3>Scope of this pass</h3>
 * Compressive (column-load-analog) only. Tensile/span bending in ship-local
 * space — generalizing {@link StructuralStressField#computeSpan}'s
 * flood-fill to an arbitrary local "below" direction — is a real extension
 * but adds enough complexity (redefining "span" in a rotated frame) that
 * it's deliberately left for a follow-up pass rather than folded in here.
 * Similarly, terrain's external-support/structural-frame lateral-relief
 * logic isn't reproduced here — "lateral" is direction-dependent once the
 * load axis isn't fixed, and re-deriving that generally is its own piece of
 * work. Structural frame blocks still terminate the ray-march (cheap, kept),
 * they just don't redistribute load to neighbours yet.
 */
public final class ShipStressField {

    private ShipStressField() {
    }

    // ── Tuning ────────────────────────────────────────────────────────────────

    /** Must match {@link StructuralStressField}'s own G_GAME for a resting ship to load-match equivalent terrain. */
    private static final double G_GAME = 9.81e-3;

    /**
     * Real gravitational acceleration in blocks/tick², under the (1 block = 1m,
     * 20 ticks/second) convention this project already uses elsewhere (see
     * {@code ImpactHandler.tickShip}'s {@code vel.length() * 20.0} blocks/tick
     * → m/s conversion). Used only to normalize linear/centrifugal acceleration
     * into "g" multiples before combining with gravity's fixed 1.0 g — not
     * used as a load-scaling constant itself, that's still {@link #G_GAME}.
     */
    private static final double REAL_G_BLOCKS_PER_TICK2 = 9.81 / (20.0 * 20.0);

    private static final double FACE_AREA_M2 = 1.0;
    private static final double STEP_LENGTH = 0.25;
    private static final int MAX_COLUMN_STEPS = 128;
    private static final int OVERLOAD_HOLD_EVALUATIONS = 3;
    private static final int BLOCKS_PER_SHIP_PER_TICK = 8;
    private static final int RESCAN_INTERVAL_TICKS = 200;

    // ── Per-ship state ────────────────────────────────────────────────────────

    private static final Map<Long, Kinematics> KINEMATICS = new ConcurrentHashMap<>();
    private static final Map<Long, ShipScanner> SCANNERS = new ConcurrentHashMap<>();

    private static final class Kinematics {
        final Vector3d prevVelocity = new Vector3d();
        long prevTick = -1;
    }

    /**
     * Round-robin candidate scanner for one ship's occupied blocks. Rebuilds
     * its candidate list every {@link #RESCAN_INTERVAL_TICKS} — approximate
     * and periodic rather than perfectly reactive to every block placed or
     * removed, mirroring {@code StructuralStressField}'s own background
     * scanner being a sampler rather than an exhaustive tracker.
     */
    private static final class ShipScanner {
        private List<BlockPos> candidates = List.of();
        private int cursor = 0;
        private long lastRebuildTick = -1;

        List<BlockPos> next(ServerLevel level, LoadedServerShip ship, int count, long now) {
            if (lastRebuildTick < 0 || now - lastRebuildTick >= RESCAN_INTERVAL_TICKS
                    || cursor >= candidates.size()) {
                rebuild(level, ship);
                lastRebuildTick = now;
                cursor = 0;
            }
            if (candidates.isEmpty()) return List.of();

            List<BlockPos> batch = new ArrayList<>(Math.min(count, candidates.size()));
            for (int i = 0; i < count && cursor < candidates.size(); i++, cursor++) {
                batch.add(candidates.get(cursor));
            }
            return batch;
        }

        private void rebuild(ServerLevel level, LoadedServerShip ship) {
            List<BlockPos> found = new ArrayList<>();
            ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
                if (!level.hasChunk(chunkX, chunkZ)) return;
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) continue;
                    int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                if (section.getBlockState(lx, ly, lz).isAir()) continue;
                                found.add(new BlockPos(
                                        (chunkX << 4) + lx, baseY + ly, (chunkZ << 4) + lz));
                            }
                        }
                    }
                }
            });
            candidates = found;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Drives ship stress evaluation for every loaded ship. Call once per server tick. */
    public static void serverTick(ServerLevel level) {
        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (!(vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld shipWorld)) return;

        long now = level.getGameTime();
        for (var ship : shipWorld.getAllShips()) {
            if (!(ship instanceof LoadedServerShip loaded)) continue;
            tickShip(level, loaded, now);
        }
    }

    /**
     * Clears all state for a removed ship — kinematics, scanner, and any
     * tracked dynamic stress entries. Wire this into whatever central
     * "ship removed" notification this project already uses (see
     * {@code ImpactHandler.onShipRemoved} for the existing hook shape) —
     * not wired in automatically here to avoid guessing at a registration
     * mechanism this class shouldn't need to know about.
     */
    public static void onShipRemoved(ServerLevel level, long shipId) {
        KINEMATICS.remove(shipId);
        SCANNERS.remove(shipId);
        DynamicStressTracker.get(level).removeShip(shipId);
    }

    // ── Per-ship evaluation ───────────────────────────────────────────────────

    private static void tickShip(ServerLevel level, LoadedServerShip ship, long now) {
        long shipId = ship.getId();

        // ── Kinematics: linear acceleration from velocity change ───────────────
        Vector3dc velNow = ship.getVelocity(); // blocks/tick, world space
        Kinematics kin = KINEMATICS.computeIfAbsent(shipId, k -> new Kinematics());
        Vector3d accelWorld = new Vector3d();
        if (kin.prevTick >= 0) {
            long dt = Math.max(1, now - kin.prevTick);
            accelWorld.set(velNow).sub(kin.prevVelocity).div(dt);
        }
        kin.prevVelocity.set(velNow);
        kin.prevTick = now;

        ShipTransform transform = ship.getTransform();
        Vector3dc angularVelWorld = ship.getAngularVelocity(); // assumed world-frame — see class doc
        Vector3dc comShip = ship.getInertiaData().getCenterOfMassInShip();

        // Transform the two ship-wide (position-independent) contributions
        // into ship-local space once — only the centrifugal term needs to be
        // recomputed per block below, since it depends on each block's own
        // offset from the center of mass.
        Vector3d gravityLocal = new Vector3d();
        transform.transformDirectionNoScalingFromWorldToShip(
                new Vector3d(0, -1, 0), gravityLocal); // unit direction; magnitude handled separately

        Vector3d accelLocal = new Vector3d();
        transform.transformDirectionNoScalingFromWorldToShip(accelWorld, accelLocal);
        Vector3d accelInGs = new Vector3d(accelLocal).div(REAL_G_BLOCKS_PER_TICK2).negate(); // d'Alembert: -a

        Vector3d angularVelLocal = new Vector3d();
        transform.transformDirectionNoScalingFromWorldToShip(angularVelWorld, angularVelLocal);

        // ── Evaluate a bounded batch of this ship's blocks ──────────────────────
        ShipScanner scanner = SCANNERS.computeIfAbsent(shipId, k -> new ShipScanner());
        List<BlockPos> batch = scanner.next(level, ship, BLOCKS_PER_SHIP_PER_TICK, now);

        for (BlockPos pos : batch) {
            evaluateShipBlock(level, ship, shipId, pos, gravityLocal, accelInGs, angularVelLocal, comShip);
        }
    }

    private static void evaluateShipBlock(ServerLevel level, LoadedServerShip ship, long shipId,
                                          BlockPos pos, Vector3dc gravityLocalDir, Vector3dc accelInGs,
                                          Vector3dc angularVelLocal, Vector3dc comShipLocal) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        BlockPhysicsData.StructuralData sd = data.structural;
        if (sd == null) return;

        double effectiveGs = effectiveGMagnitude(pos, gravityLocalDir, accelInGs, angularVelLocal, comShipLocal, null);
        Vector3d dir = new Vector3d();
        effectiveGDirection(pos, gravityLocalDir, accelInGs, angularVelLocal, comShipLocal, dir);

        double columnLoad = (dir.lengthSquared() < 1e-12)
                ? 0.0
                : shipColumnLoad(level, pos, dir, effectiveGs);
        double compStress = columnLoad / (sd.compressiveStrengthKpa() * FACE_AREA_M2);

        DynamicStressTracker tracker = DynamicStressTracker.get(level);
        double dynStress = tracker.getSmoothedStress(shipId, pos);

        double rawStress = Math.max(compStress, dynStress);
        // Thermal/corrosion factors deliberately omitted for ship blocks in this
        // pass — MGE's EnvironmentGrid and pH corrosion tracking both operate in
        // world-rendered space and their behavior at ship-local storage
        // coordinates hasn't been verified. Treating both factors as 1.0
        // (no weakening) is the safe default until that's confirmed rather than
        // silently assuming they transfer correctly.
        double effectiveStress = rawStress;

        boolean overloaded = effectiveStress >= sd.failureThresholdFraction();
        int streak = tracker.updateOverloadStreak(shipId, pos, overloaded);
        boolean freshImpact = tracker.consumeImpactFlag(shipId, pos);
        boolean shouldFail = overloaded && (freshImpact || streak >= OVERLOAD_HOLD_EVALUATIONS);

        if (shouldFail) {
            Set<BlockPos> failSet = StructuralStressField.connectedFailureBFS(
                    level, pos, sd.failureThresholdFraction(),
                    candidate -> {
                        BlockPhysicsData.StructuralData csd = BlockPhysicsRegistry.get(level.getBlockState(candidate)).structural;
                        if (csd == null) return 0.0;
                        Vector3d cDir = new Vector3d();
                        effectiveGDirection(candidate, gravityLocalDir, accelInGs, angularVelLocal, comShipLocal, cDir);
                        double cGs = effectiveGMagnitude(candidate, gravityLocalDir, accelInGs, angularVelLocal, comShipLocal, cDir);
                        double cLoad = (cDir.lengthSquared() < 1e-12) ? 0.0 : shipColumnLoad(level, candidate, cDir, cGs);
                        return cLoad / (csd.compressiveStrengthKpa() * FACE_AREA_M2);
                    });

            for (BlockPos failPos : failSet) {
                tracker.remove(shipId, failPos);
            }
            FailureDispatcher.dispatchGroup(level, failSet, state, sd);
        }
    }

    // ── Effective local gravity ───────────────────────────────────────────────

    /**
     * Combined direction of gravity + linear acceleration + centrifugal load
     * at {@code pos}, in ship-local space. Not normalized — callers needing
     * the walk direction should normalize; the pre-normalization length is
     * also usable as a cheap magnitude proxy but {@link #effectiveGMagnitude}
     * is the authoritative one (kept separate so a caller can reuse an
     * already-computed direction vector when it has one, as the BFS callback
     * above does).
     */
    private static void effectiveGDirection(BlockPos pos, Vector3dc gravityLocalDir, Vector3dc accelInGs,
                                            Vector3dc angularVelLocal, Vector3dc comShipLocal, Vector3d dest) {
        dest.set(gravityLocalDir).add(accelInGs);
        if (angularVelLocal.lengthSquared() > 1e-12) {
            Vector3d r = new Vector3d(
                    pos.getX() + 0.5 - comShipLocal.x(),
                    pos.getY() + 0.5 - comShipLocal.y(),
                    pos.getZ() + 0.5 - comShipLocal.z());
            Vector3d omegaCrossR = new Vector3d(angularVelLocal).cross(r);
            Vector3d centrifugal = new Vector3d(angularVelLocal).cross(omegaCrossR).negate();
            dest.add(centrifugal.div(REAL_G_BLOCKS_PER_TICK2));
        }
    }

    /** Magnitude (in g's) of the combined effective gravity at {@code pos}. */
    private static double effectiveGMagnitude(BlockPos pos, Vector3dc gravityLocalDir, Vector3dc accelInGs,
                                              Vector3dc angularVelLocal, Vector3dc comShipLocal,
                                              Vector3d precomputedDir) {
        Vector3d dir = precomputedDir;
        if (dir == null) {
            dir = new Vector3d();
            effectiveGDirection(pos, gravityLocalDir, accelInGs, angularVelLocal, comShipLocal, dir);
        }
        return dir.length();
    }

    // ── Ship-local ray-march column load ──────────────────────────────────────

    /**
     * Continuous 3D ray-march from {@code origin} along {@code direction}
     * (need not be normalized — normalized internally), summing
     * {@code density × G_GAME × effectiveGs} for each solid block crossed,
     * stopping at the first air gap, a structural frame block, the ship's
     * loaded boundary, or {@link #MAX_COLUMN_STEPS}. Mirrors
     * {@code StructuralStressField.computeColumnLoad}'s stopping rules,
     * generalized from a fixed +Y integer walk to an arbitrary-direction
     * continuous walk — see class doc for what's deliberately not
     * reproduced yet (external-support relief, lateral frame redistribution).
     */
    private static double shipColumnLoad(ServerLevel level, BlockPos origin, Vector3dc direction, double effectiveGs) {
        double len = direction.length();
        if (len < 1e-9) return 0.0;
        double dx = direction.x() / len, dy = direction.y() / len, dz = direction.z() / len;

        double wx = origin.getX() + 0.5 + dx * 0.5;
        double wy = origin.getY() + 0.5 + dy * 0.5;
        double wz = origin.getZ() + 0.5 + dz * 0.5;

        double totalKN = 0.0;
        BlockPos lastPos = null;

        for (int i = 0; i < MAX_COLUMN_STEPS; i++) {
            wx += dx * STEP_LENGTH;
            wy += dy * STEP_LENGTH;
            wz += dz * STEP_LENGTH;

            BlockPos cur = new BlockPos((int) Math.floor(wx), (int) Math.floor(wy), (int) Math.floor(wz));
            if (cur.equals(lastPos)) continue;
            lastPos = cur;

            if (!level.isLoaded(cur)) break;
            BlockState state = level.getBlockState(cur);
            if (state.isAir()) break; // open — nothing bearing on origin from this direction

            BlockPhysicsData d = BlockPhysicsRegistry.get(state);
            BlockPhysicsData.StructuralData sd = d.structural;
            if (sd != null) {
                totalKN += sd.densityKgM3() * G_GAME * effectiveGs;
                if (sd.isStructuralFrame()) break; // terminates the walk; lateral redistribution not modeled yet (see class doc)
            } else {
                totalKN += 2400 * G_GAME * effectiveGs;
            }
        }

        return totalKN;
    }
}
