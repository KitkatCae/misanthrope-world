package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.AxisAngle4d;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a VS2 {@link ServerShip} back into real world blocks at its current
 * world-space position, then deletes the ship.
 *
 * <p>This is MWorld's own copy of the same technique used by MVSE's
 * {@code ShipDisassembler} (impact/meteor disassembly) — deliberately
 * re-implemented here rather than depended on, so MWorld does not require
 * MVSE to be present. If you're maintaining both, keep the reflection surface
 * and rotation-snapping logic in sync; they're intentionally identical.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Snap the ship's current {@code shipToWorldRotation} to the nearest 90°
 *       (our explosion/fragment ships never had torque applied — see
 *       {@code FailureDispatcher.applyFragmentImpulse}, linear impulse only —
 *       so in practice this is almost always already axis-aligned, but we
 *       snap defensively in case drift or a future torque source changes that).</li>
 *   <li>Build a {@link Matrix4d} mapping ship-space block positions to
 *       world-space positions.</li>
 *   <li>Iterate every chunk section in the ship's {@code activeChunksSet}.</li>
 *   <li>For each non-air block, compute its world-space {@link BlockPos},
 *       then relocate the block (state + block entity NBT) from the ship
 *       chunk to the world chunk via VS2's {@code RelocationUtilKt} (reflection,
 *       since it isn't part of the stable public API).</li>
 *   <li>Fire neighbour/lighting updates for every moved block.</li>
 *   <li>Delete the now-empty ship.</li>
 * </ol>
 *
 * <h3>Reflection surface</h3>
 * Same three calls as MVSE's version:
 * <pre>
 *   RelocationUtilKt.relocateBlock(LevelChunk, BlockPos, LevelChunk, BlockPos,
 *                                  boolean, ServerShip, Rotation)
 *   RelocationUtilKt.updateBlock(Level, BlockPos, BlockPos, BlockState)
 *   ShipAssembler.deleteShips(ServerShipWorld, List)  — or, if unavailable,
 *   ShipAssembler.INSTANCE.deleteShip(level, ship, false, false)
 * </pre>
 * None of these are confirmed against this project's actual VS2 jar via
 * classdump/javap in this session — they're carried over unchanged from
 * MVSE's already-working implementation, which is the strongest evidence
 * available short of a decompile. Re-verify if VS2 version changes break this.
 *
 * <h3>Thread safety</h3>
 * Must be called on the game thread (server tick), same as MVSE's version.
 */
public final class StructuralShipDisassembler {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/StructuralShipDisassembler");

    private StructuralShipDisassembler() {
    }

    // ── Reflection cache ─────────────────────────────────────────────────────

    private static volatile boolean resolved = false;
    private static Method methodRelocateBlock = null; // RelocationUtilKt.relocateBlock
    private static Method methodUpdateBlock = null;   // RelocationUtilKt.updateBlock
    private static Method methodDeleteShips = null;   // ShipAssembler.deleteShips (static ext)

    private static void resolveReflection() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> reloc = Class.forName("org.valkyrienskies.mod.util.RelocationUtilKt");
            methodRelocateBlock = reloc.getMethod("relocateBlock",
                    LevelChunk.class, BlockPos.class,
                    LevelChunk.class, BlockPos.class,
                    boolean.class,
                    ServerShip.class,
                    Rotation.class);
            methodUpdateBlock = reloc.getMethod("updateBlock",
                    net.minecraft.world.level.Level.class,
                    BlockPos.class, BlockPos.class, BlockState.class);
        } catch (Exception e) {
            LOGGER.warn("[StructuralShipDisassembler] Could not resolve RelocationUtilKt — using fallback: {}", e.getMessage());
        }
        try {
            Class<?> shipWorldExt = Class.forName("org.valkyrienskies.mod.common.assembly.ShipAssembler");
            methodDeleteShips = shipWorldExt.getMethod("deleteShips",
                    org.valkyrienskies.core.api.world.ServerShipWorld.class, List.class);
        } catch (Exception e) {
            try {
                Class<?> ext = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
                methodDeleteShips = ext.getMethod("deleteShips",
                        org.valkyrienskies.core.api.world.ServerShipWorld.class, List.class);
            } catch (Exception e2) {
                LOGGER.warn("[StructuralShipDisassembler] Could not resolve deleteShips — will use ShipAssembler.deleteShip fallback: {}", e2.getMessage());
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Clean disassembly at the ship's exact current transform — no scatter.
     * This is the call the "settled explosion debris" reversion feature uses:
     * once a fragment ship has been still long enough, it converts back into
     * placed static blocks right where it came to rest.
     */
    public static void disassemble(ServerLevel level, ServerShip ship) {
        disassemble(level, ship, 0.0, null, false);
    }

    /**
     * Disassembly with optional scatter, for impact-style fragmentation.
     *
     * @param scatterRadius   if > 0, blocks are randomly displaced by up to this
     *                        many blocks, biased outward from impactOrigin.
     * @param impactOrigin    world-space origin used to bias scatter direction;
     *                        required if scatterRadius > 0.
     * @param dropOutOfBounds if true, blocks scattered outside build height are
     *                        dropped as item entities instead of placed.
     */
    public static void disassemble(ServerLevel level, ServerShip ship,
                                    double scatterRadius, Vector3d impactOrigin,
                                    boolean dropOutOfBounds) {
        resolveReflection();

        var transform = ship.getTransform();
        AxisAngle4d rawRot = new AxisAngle4d(transform.getShipToWorldRotation());
        AxisAngle4d snapped = snapRotation(rawRot);
        Rotation mcRot = axisAngleToMcRotation(snapped);

        var worldPos = transform.getPositionInWorld();
        var shipPos = transform.getPositionInShip();
        double scale = transform.getShipToWorldScaling().x();

        Matrix4d shipToWorld = new Matrix4d()
                .translate(worldPos.x(), worldPos.y(), worldPos.z())
                .rotate(snapped)
                .scale(scale)
                .translate(-shipPos.x(), -shipPos.y(), -shipPos.z());

        Vector3d alloc = new Vector3d();
        RandomSource rng = level.getRandom();

        record BlockMove(BlockPos shipPos, BlockPos worldPos, BlockState state) {
        }
        List<BlockMove> moves = new ArrayList<>();

        ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
            LevelChunk shipChunk = level.getChunk(chunkX, chunkZ);
            var sections = shipChunk.getSections();

            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                var section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) continue;

                int sectionBottomY = shipChunk.getSectionYFromSectionIndex(sectionIndex) << 4;

                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            BlockState state = section.getBlockState(lx, ly, lz);
                            if (state.isAir()) continue;

                            int shipBlockX = (chunkX << 4) + lx;
                            int shipBlockY = sectionBottomY + ly + level.getMinBuildHeight();
                            int shipBlockZ = (chunkZ << 4) + lz;

                            shipToWorld.transformPosition(
                                    alloc.set(shipBlockX + 0.5, shipBlockY + 0.5, shipBlockZ + 0.5));
                            alloc.floor();

                            int wx = (int) alloc.x;
                            int wy = (int) alloc.y;
                            int wz = (int) alloc.z;

                            if (scatterRadius > 0.0 && impactOrigin != null) {
                                double dx = wx - impactOrigin.x;
                                double dy = wy - impactOrigin.y;
                                double dz = wz - impactOrigin.z;
                                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                                if (len > 0.01) {
                                    dx /= len;
                                    dy /= len;
                                    dz /= len;
                                } else {
                                    dx = rng.nextGaussian();
                                    dy = rng.nextGaussian();
                                    dz = rng.nextGaussian();
                                    double l2 = Math.sqrt(dx * dx + dy * dy + dz * dz);
                                    dx /= l2;
                                    dy /= l2;
                                    dz /= l2;
                                }
                                double mag = rng.nextDouble() * scatterRadius;
                                wx += (int) Math.round(dx * mag);
                                wy += (int) Math.round(dy * mag);
                                wz += (int) Math.round(dz * mag);
                            }

                            moves.add(new BlockMove(
                                    new BlockPos(shipBlockX, shipBlockY, shipBlockZ),
                                    new BlockPos(wx, wy, wz),
                                    state));
                        }
                    }
                }
            }
        });

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (BlockMove move : moves) {
            int wy = move.worldPos().getY();
            boolean inBounds = wy >= minY && wy < maxY;

            if (!inBounds) {
                if (dropOutOfBounds) dropBlock(level, move.state(), move.worldPos());
                continue;
            }

            BlockPos shipBp = move.shipPos();
            BlockPos worldBp = move.worldPos();

            if (methodRelocateBlock != null) {
                try {
                    LevelChunk shipChunk = level.getChunk(new ChunkPos(shipBp).x, new ChunkPos(shipBp).z);
                    LevelChunk worldChunk = level.getChunk(new ChunkPos(worldBp).x, new ChunkPos(worldBp).z);
                    methodRelocateBlock.invoke(null,
                            shipChunk, shipBp,
                            worldChunk, worldBp,
                            false, // doUpdate: batched separately below
                            null,  // toShip: null = world
                            mcRot);
                } catch (Exception e) {
                    level.setBlock(worldBp, move.state().rotate(mcRot), 3);
                }
            } else {
                level.setBlock(worldBp, move.state().rotate(mcRot), 3);
            }
        }

        if (methodUpdateBlock != null) {
            for (BlockMove move : moves) {
                int wy = move.worldPos().getY();
                if (wy < minY || wy >= maxY) continue;
                try {
                    methodUpdateBlock.invoke(null,
                            level, move.shipPos(), move.worldPos(),
                            move.state().rotate(mcRot));
                } catch (Exception ignored) {
                }
            }
        }

        deleteShip(level, ship);
    }

    // ── Ship deletion ────────────────────────────────────────────────────────

    /**
     * Deletes a ship with no block relocation — for cases like vaporization
     * where the ship should simply cease to exist, not leave debris. This is
     * also called internally by {@link #disassemble} after it finishes
     * relocating blocks, so this stays the one place in MWorld that actually
     * deletes a VS2 ship, and the one place that notifies
     * ImpactHandler/HullPressureHandler regardless of which caller triggered
     * the deletion.
     */
    public static void deleteShip(ServerLevel level, ServerShip ship) {
        // Let ImpactHandler revert any pending crater/embed mementos for this
        // ship immediately (if ImpactConfig.revertOnShipRemoval is set) before
        // the ship itself is actually gone. This is the one place in MWorld
        // that deletes VS2 ships, so it's the natural choke point for this
        // notification rather than hunting for a VS2 ship-removal event.
        try {
            ImpactHandler.onShipRemoved(ship.getId(), level);
        } catch (Exception ignored) {
        }
        try {
            exp.CCnewmods.misanthrope_world.physics.pressure.hull.HullPressureHandler
                    .onShipRemoved(ship.getId(), level);
        } catch (Exception ignored) {
        }

        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (!(vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld shipWorld)) return;

        if (methodDeleteShips != null) {
            try {
                methodDeleteShips.invoke(null, shipWorld, List.of(ship));
                return;
            } catch (Exception ignored) {
            }
        }

        // Blocks are already relocated above, so both flags here mean
        // "don't drop anything, don't do it again" — matches MVSE's
        // already-working call to this exact overload.
        try {
            ShipAssembler.INSTANCE.deleteShip(level, ship, false, false);
        } catch (Exception e) {
            LOGGER.warn("[StructuralShipDisassembler] Failed to delete ship {}: {}", ship.getId(), e.getMessage());
        }
    }

    // ── Block dropping (out-of-bounds scatter) ──────────────────────────────

    private static void dropBlock(ServerLevel level, BlockState state, BlockPos pos) {
        var drops = net.minecraft.world.level.block.Block.getDrops(state, level, pos, null);
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        for (var stack : drops) {
            var entity = new net.minecraft.world.entity.item.ItemEntity(level, x, y, z, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
    }

    // ── Rotation helpers (identical to MVSE's) ──────────────────────────────

    static AxisAngle4d snapRotation(AxisAngle4d in) {
        double ax = Math.abs(in.x), ay = Math.abs(in.y), az = Math.abs(in.z);
        double snappedAngle = Math.round(in.angle / (Math.PI / 2)) * (Math.PI / 2);

        if (ax >= ay && ax >= az) return new AxisAngle4d(snappedAngle, Math.signum(in.x), 0, 0);
        else if (ay >= ax && ay >= az) return new AxisAngle4d(snappedAngle, 0, Math.signum(in.y), 0);
        else return new AxisAngle4d(snappedAngle, 0, 0, Math.signum(in.z));
    }

    static Rotation axisAngleToMcRotation(AxisAngle4d axis) {
        if (Math.abs(axis.y) < 0.1) return Rotation.NONE;

        double angle = axis.angle;
        if (axis.y < 0) {
            angle = 2 * Math.PI - angle;
            angle %= (2 * Math.PI);
        }

        double eps = 0.001;
        if (angle < eps) return Rotation.NONE;
        if (Math.abs(angle - Math.PI / 2) < eps) return Rotation.COUNTERCLOCKWISE_90;
        if (Math.abs(angle - Math.PI) < eps) return Rotation.CLOCKWISE_180;
        if (Math.abs(angle - 3 * Math.PI / 2) < eps) return Rotation.CLOCKWISE_90;

        return Rotation.NONE;
    }
}
