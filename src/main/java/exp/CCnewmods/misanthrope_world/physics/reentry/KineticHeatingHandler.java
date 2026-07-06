package exp.CCnewmods.misanthrope_world.physics.reentry;

import exp.CCnewmods.misanthrope_world.physics.reentry.AerodynamicsConfig;
import exp.CCnewmods.misanthrope_world.physics.reentry.network.ReentryStatePacket;
import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.util.AerodynamicUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.structural.ShockwaveStressAdapter;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side kinetic (aerodynamic) heating system.
 *
 * <h3>Atmospheric scaling</h3>
 * Uses VS2's {@link AerodynamicUtils#getAirDensityForY(double, String)} to scale
 * heating with actual atmospheric density rather than a hardcoded Y threshold.
 * This integrates naturally with MGE's pressure profile: thin atmosphere =
 * less aerodynamic heating even at high speed (fewer molecules to compress).
 *
 * <h3>Material-aware destruction</h3>
 * Reads {@link BlockPhysicsRegistry} directly now that this class lives in
 * MWorld (ported from MVSE, where it reflected into Misanthrope Core's
 * now-removed BlockPhysicsRegistry) for:
 * <ul>
 *   <li>{@code thermalCrackThreshold} — blocks with low thresholds fail fast</li>
 *   <li>{@code thermalCrackRate}      — rate multiplier on crack accumulation</li>
 *   <li>{@code structural.failureMode} — determines if fragments go VS2 physics</li>
 *   <li>{@code lavaImmune}            — used as proxy for high-heat resistance</li>
 * </ul>
 *
 * <h3>Fragmentation</h3>
 * At extreme intensity, sufficiently-large contiguous groups of destroyed blocks
 * are assembled into new VS2 ships via {@code ShipAssembler.assembleToShip()},
 * creating real physics bodies that fly off the ship.
 */
public final class KineticHeatingHandler {

    private KineticHeatingHandler() {}

    private static final Map<Long, KineticHeatingState> STATES = new ConcurrentHashMap<>();
    private static int globalTick = 0;

    // ── Reflection handles (MGE only — genuinely optional/external) ────────────

    private static volatile boolean resolved = false;
    private static Method mgeSetTemp        = null;
    private static Method mgeAddGas         = null;
    private static Object gasIonisedAir     = null;

    private static void resolveApis() {
        if (resolved) return;
        resolved = true;

        // MGE EnvironmentGrid
        try {
            Class<?> eg = Class.forName("exp.CCnewmods.mge.grid.EnvironmentGrid");
            mgeSetTemp = eg.getMethod("setTemperature",
                    net.minecraft.world.level.Level.class, BlockPos.class, float.class);
            mgeAddGas  = eg.getMethod("addGas",
                    net.minecraft.world.level.Level.class, BlockPos.class,
                    Class.forName("exp.CCnewmods.mge.gas.Gas"), float.class);
        } catch (Exception ignored) {}

        // MGE gas registry - IONISED_AIR
        try {
            gasIonisedAir = Class.forName("exp.CCnewmods.mge.gas.GasRegistry")
                    .getField("IONISED_AIR").get(null);
        } catch (Exception ignored) {}

        // NOTE: BlockPhysicsRegistry/BlockPhysicsData and the shockwave-stress
        // bridge (formerly reflected into MGE's exp.CCnewmods.mge.compat.MisCoreBridge
        // — a stale name; MGE's own bridge class is now MisWorldBridge) and
        // ShipAssembler are called directly below, not via reflection, now that
        // this class lives in MWorld itself and can see them at compile time.
    }

    // ── Ship fragmentation via ShipAssembler ────────────────────────────────────
    // Direct call, replacing MVSE's reflection into a nonexistent
    // ShipAssembler$Companion class — verified against this project's actual
    // VS2 jar that ShipAssembler is a Kotlin object (singleton INSTANCE field),
    // not a companion, and assembleToShip(ServerLevel, Set, double) is a plain
    // public instance method on it. Same pattern FailureDispatcher already uses
    // for assembleToShipFull.
    private static org.valkyrienskies.core.api.ships.ServerShip assembleToShip(
            ServerLevel level, Set<BlockPos> blocks) {
        return org.valkyrienskies.mod.common.assembly.ShipAssembler.INSTANCE
                .assembleToShip(level, blocks, 1.0);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void serverTick(ServerLevel level) {
        resolveApis();
        globalTick++;

        AerodynamicsConfig cfg = AerodynamicsConfig.INSTANCE;
        double machOneMs    = cfg.machOneSpeedMs.get();
        double onsetMach    = cfg.heatingOnsetMach.get();
        double maxMach      = cfg.heatingMaxMach.get();
        int    heatInterval = cfg.heatingTickInterval.get();
        int    syncInterval = cfg.clientSyncTickInterval.get();

        String dimId = VSGameUtilsKt.getDimensionId(level);
        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (!(vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld shipWorld)) return;

        Set<Long> activeIds = new HashSet<>();
        for (var ship : shipWorld.getAllShips()) {
            if (!(ship instanceof LoadedServerShip loaded)) continue;
            activeIds.add(loaded.getId());
            tickShip(level, loaded, machOneMs, onsetMach, maxMach,
                     heatInterval, syncInterval, cfg, dimId);
        }
        STATES.keySet().removeIf(id -> !activeIds.contains(id));
    }

    public static void onLevelUnload(ServerLevel level) { STATES.clear(); }

    public static float getIntensity(long shipId) {
        KineticHeatingState s = STATES.get(shipId);
        return s != null ? s.intensity : 0f;
    }

    // ── Per-ship tick ─────────────────────────────────────────────────────────

    private static void tickShip(ServerLevel level, LoadedServerShip ship,
                                  double machOneMs, double onsetMach, double maxMach,
                                  int heatInterval, int syncInterval,
                                  AerodynamicsConfig cfg, String dimId) {
        long id = ship.getId();
        KineticHeatingState state = STATES.computeIfAbsent(id, k -> new KineticHeatingState());

        Vector3dc vel    = ship.getVelocity();
        double speedMs   = vel.length() * 20.0;
        double mach      = speedMs / machOneMs;
        double shipY     = ship.getTransform().getPositionInWorld().y();

        // VS2 atmospheric density at ship altitude — scales heating
        double airDensity;
        try {
            try {
                // AerodynamicUtils is a Kotlin interface; getAirDensityForY is an
                // interface method, not visible on the Companion class from Java.
                // Call via reflection on the interface directly.
                java.lang.reflect.Method m = org.valkyrienskies.core.api.util.AerodynamicUtils.class
                    .getMethod("getAirDensityForY", double.class, String.class);
                airDensity = (double) m.invoke(AerodynamicUtils.Companion, shipY, dimId);
            } catch (Exception _aeroEx) {
                airDensity = 1.225; // ISA sea-level fallback
            }
        } catch (Exception e) {
            airDensity = 1.225; // sea-level fallback
        }
        // Normalise: sea-level density ~1.225 kg/m³ → ratio [0,1]
        double densityRatio = Math.min(1.0, airDensity / 1.225);

        state.speedMs = speedMs;
        state.mach    = mach;
        if (vel.lengthSquared() > 1e-6) state.velDir.set(vel).normalize();

        // Target intensity: mach curve × atmospheric density scaling
        float target = (float) (computeIntensity(mach, onsetMach, maxMach) * densityRatio);
        state.targetIntensity = target;

        // Smooth lerp
        if (target > state.intensity) {
            state.intensity = Math.min(target, state.intensity + KineticHeatingState.LERP_RATE);
        } else {
            state.intensity = Math.max(target, state.intensity - KineticHeatingState.DECAY_RATE);
        }

        int maxDepth = cfg.heatingPenetrationDepth.get();
        state.currentDepth = Math.max(1, (int)(state.intensity * maxDepth + 0.5f));

        if (state.intensity > 0.001f && globalTick % heatInterval == 0) {
            applyHeating(level, ship, state, cfg);
        }

        state.ticksSinceSync++;
        if (state.ticksSinceSync >= syncInterval) {
            sendSyncPacket(level, ship, state, cfg);
            state.ticksSinceSync = 0;
        }
    }

    // ── Intensity curve ───────────────────────────────────────────────────────

    private static float computeIntensity(double mach, double onsetMach, double maxMach) {
        if (mach < 1.0) return 0f;
        if (mach < onsetMach) {
            return (float)((mach - 1.0) / (onsetMach - 1.0)) * 0.05f;
        }
        double logRange   = Math.log(maxMach / onsetMach);
        double logCurrent = Math.log(mach / onsetMach);
        double frac       = Math.min(1.0, logCurrent / logRange);
        return 0.05f + (float)(frac * 0.95);
    }

    // ── Heating application ───────────────────────────────────────────────────

    private static void applyHeating(ServerLevel level, LoadedServerShip ship,
                                      KineticHeatingState state, AerodynamicsConfig cfg) {
        float intensity  = state.intensity;
        double peakC     = cfg.heatingPeakCelsius.get();
        float  gasRate   = cfg.plasmaGasRateMbar.get().floatValue();

        // Quadratic temperature ramp: mild at low mach, extreme at peak
        float tempC = (float)(400.0 + (peakC - 400.0) * intensity * intensity);

        List<BlockPos> surface = getLeadingEdgeSurface(ship, state.velDir, state.currentDepth);
        List<BlockPos> toFragment = new ArrayList<>();

        for (BlockPos pos : surface) {
            if (!level.isLoaded(pos)) continue;
            BlockState blockState = level.getBlockState(pos);
            if (blockState.isAir()) continue;

            setTemperature(level, pos, tempC);
            addIonisedAir(level, pos, gasRate * intensity);

            if (intensity > 0.3f) {
                injectStress(level, pos, intensity * 2.0f);
            }

            if (intensity > 0.5f) {
                if (shouldDestroy(blockState, level, pos, intensity, tempC)) {
                    toFragment.add(pos);
                }
            }
        }

        // Entity damage
        if (intensity > 0.1f) damageEntitiesInFront(level, ship, state.velDir, intensity);

        // Destroy and fragment blocks
        if (!toFragment.isEmpty()) {
            processDestructions(level, ship, toFragment, intensity);
        }
    }

    // ── Material-aware destruction ────────────────────────────────────────────

    /**
     * Determines whether a block should be destroyed this tick based on
     * material properties from BlockPhysicsRegistry.
     */
    private static boolean shouldDestroy(BlockState state, ServerLevel level,
                                          BlockPos pos, float intensity, float tempC) {
        // Unbreakable by game rules
        if (state.getDestroySpeed(level, pos) < 0) return false;

        double crackThreshold = 500.0; // default: glass-like
        double crackRate      = 0.003;
        boolean lavaImmune    = false;

        BlockPhysicsData data = BlockPhysicsRegistry.get(state);
        if (data != null) {
            crackThreshold = data.thermalCrackThreshold;
            crackRate = data.thermalCrackRate;
            lavaImmune = data.lavaImmune;
        }

        // lava_immune blocks resist up to ~4000°C (Mach ~80 equivalent)
        if (lavaImmune && tempC < 4000f) return false;

        // Probability = crackRate × intensity × (tempC / crackThreshold)
        // High crack rate + high temp relative to threshold = fast failure
        double tempFactor = Math.max(0, tempC / crackThreshold - 1.0); // 0 when below threshold
        double chance     = crackRate * intensity * Math.min(10.0, tempFactor) * 0.02;
        return level.getRandom().nextDouble() < chance;
    }

    /**
     * Processes a list of blocks to destroy. For groups large enough, attempts
     * VS2 fragmentation via ShipAssembler. Others crumble in place.
     */
    private static void processDestructions(ServerLevel level, LoadedServerShip ship,
                                             List<BlockPos> toDestroy, float intensity) {
        // Try to identify connected groups for fragmentation
        if (intensity > 0.75f && toDestroy.size() >= 4) {
            tryFragment(level, ship, new HashSet<>(toDestroy), intensity);
        } else {
            // Just crumble — no drops, ablated away
            for (BlockPos pos : toDestroy) {
                level.destroyBlock(pos, false);
            }
        }
    }

    /**
     * Attempts to assemble destroyed blocks into a new VS2 physics ship
     * (fragment) using ShipAssembler.
     */
    private static void tryFragment(ServerLevel level, LoadedServerShip ship,
                                     Set<BlockPos> blocks, float intensity) {
        int minSize = 4;
        BlockPos first = blocks.iterator().next();
        BlockState firstState = level.getBlockState(first);
        BlockPhysicsData.StructuralData sd = BlockPhysicsRegistry.get(firstState).structural;
        if (sd != null) {
            minSize = sd.fragmentMinSize();
        }

        if (blocks.size() < minSize) {
            // Too small — just crumble
            for (BlockPos pos : blocks) level.destroyBlock(pos, false);
            return;
        }

        try {
            var newShip = assembleToShip(level, blocks);
            if (newShip != null) {
                // Give the fragment an initial velocity matching parent + outward impulse
                // (VS2 ShipAssembler copies velocity from the parent ship naturally,
                // but we add a small perpendicular kick for visual separation)
                // This is handled by VS2 internally via the kinematics copy in assembleToShipFull
            }
        } catch (Exception e) {
            // Fallback: crumble
            for (BlockPos pos : blocks) level.destroyBlock(pos, false);
        }
    }

    // ── Leading edge ─────────────────────────────────────────────────────────

    private static List<BlockPos> getLeadingEdgeSurface(LoadedServerShip ship,
                                                          Vector3d velDir, int depth) {
        var worldAabb = ship.getWorldAABB();
        if (worldAabb == null) return Collections.emptyList();

        double ax = Math.abs(velDir.x);
        double ay = Math.abs(velDir.y);
        double az = Math.abs(velDir.z);

        List<BlockPos> positions = new ArrayList<>();
        final int MAX = 512;

        if (ax >= ay && ax >= az) {
            int faceX = velDir.x > 0 ? (int)Math.floor(worldAabb.maxX()) - depth
                                      : (int)Math.ceil(worldAabb.minX());
            int signX = velDir.x > 0 ? 1 : -1;
            int minY = (int)Math.floor(worldAabb.minY()), maxY = (int)Math.ceil(worldAabb.maxY());
            int minZ = (int)Math.floor(worldAabb.minZ()), maxZ = (int)Math.ceil(worldAabb.maxZ());
            outer: for (int d = 0; d < depth; d++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++) {
                        positions.add(new BlockPos(faceX + signX * d, y, z));
                        if (positions.size() >= MAX) break outer;
                    }
        } else if (ay >= ax && ay >= az) {
            int faceY = velDir.y > 0 ? (int)Math.floor(worldAabb.maxY()) - depth
                                      : (int)Math.ceil(worldAabb.minY());
            int signY = velDir.y > 0 ? 1 : -1;
            int minX = (int)Math.floor(worldAabb.minX()), maxX = (int)Math.ceil(worldAabb.maxX());
            int minZ = (int)Math.floor(worldAabb.minZ()), maxZ = (int)Math.ceil(worldAabb.maxZ());
            outer: for (int d = 0; d < depth; d++)
                for (int x = minX; x <= maxX; x++)
                    for (int z = minZ; z <= maxZ; z++) {
                        positions.add(new BlockPos(x, faceY + signY * d, z));
                        if (positions.size() >= MAX) break outer;
                    }
        } else {
            int faceZ = velDir.z > 0 ? (int)Math.floor(worldAabb.maxZ()) - depth
                                      : (int)Math.ceil(worldAabb.minZ());
            int signZ = velDir.z > 0 ? 1 : -1;
            int minX = (int)Math.floor(worldAabb.minX()), maxX = (int)Math.ceil(worldAabb.maxX());
            int minY = (int)Math.floor(worldAabb.minY()), maxY = (int)Math.ceil(worldAabb.maxY());
            outer: for (int d = 0; d < depth; d++)
                for (int x = minX; x <= maxX; x++)
                    for (int y = minY; y <= maxY; y++) {
                        positions.add(new BlockPos(x, y, faceZ + signZ * d));
                        if (positions.size() >= MAX) break outer;
                    }
        }
        return positions;
    }

    // ── Entity damage ─────────────────────────────────────────────────────────

    private static void damageEntitiesInFront(ServerLevel level, LoadedServerShip ship,
                                               Vector3d velDir, float intensity) {
        var aabb = ship.getWorldAABB();
        if (aabb == null) return;
        double cx = (aabb.minX() + aabb.maxX()) / 2.0;
        double cy = (aabb.minY() + aabb.maxY()) / 2.0;
        double cz = (aabb.minZ() + aabb.maxZ()) / 2.0;
        double ex = (aabb.maxX() - aabb.minX()) / 4.0;
        double ey = (aabb.maxY() - aabb.minY()) / 4.0;
        double ez = (aabb.maxZ() - aabb.minZ()) / 4.0;
        double nx = cx + velDir.x * ex, ny = cy + velDir.y * ey, nz = cz + velDir.z * ez;
        var damageBox = new net.minecraft.world.phys.AABB(
                nx - ex, ny - ey, nz - ez, nx + ex, ny + ey, nz + ez);
        float dmg = intensity * 4f;
        level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                damageBox, e -> !e.fireImmune()).forEach(e -> {
            e.setRemainingFireTicks(60);
            e.hurt(level.damageSources().inFire(), dmg);
        });
    }

    // ── Client sync ───────────────────────────────────────────────────────────

    private static void sendSyncPacket(ServerLevel level, LoadedServerShip ship,
                                        KineticHeatingState state, AerodynamicsConfig cfg) {
        var pos = ship.getTransform().getPositionInWorld();
        ReentryStatePacket pkt = new ReentryStatePacket(
                ship.getId(), state.intensity,
                new Vec3(pos.x(), pos.y(), pos.z()),
                new Vec3(state.velDir.x, state.velDir.y, state.velDir.z),
                (float) state.mach);
        double radius = cfg.clientSyncRadius.get();
        CrackNetwork.CHANNEL.send(
                PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                        pos.x(), pos.y(), pos.z(), radius * radius, level.dimension())),
                pkt);
    }

    // ── MGE helpers ───────────────────────────────────────────────────────────

    private static void setTemperature(ServerLevel level, BlockPos pos, float celsius) {
        if (mgeSetTemp == null) return;
        try { mgeSetTemp.invoke(null, level, pos, celsius); } catch (Exception ignored) {}
    }
    private static void addIonisedAir(ServerLevel level, BlockPos pos, float mbar) {
        if (mgeAddGas == null || gasIonisedAir == null) return;
        try { mgeAddGas.invoke(null, level, pos, gasIonisedAir, mbar); } catch (Exception ignored) {}
    }
    private static void injectStress(ServerLevel level, BlockPos pos, float strength) {
        // Direct call — this used to reflect into MGE's compat bridge
        // (exp.CCnewmods.mge.compat.MisCoreBridge.injectShockwaveStress, itself
        // a stale name for what's now MisWorldBridge) purely to reach Core's
        // structural stress system. Now that this class IS in MWorld, alongside
        // ShockwaveStressAdapter, there's no reason to route through MGE at all
        // for this — ShockwaveStressAdapter has no state, safe to instantiate
        // ad hoc.
        new ShockwaveStressAdapter().injectShockwaveStress(level, pos, strength);
    }
}
