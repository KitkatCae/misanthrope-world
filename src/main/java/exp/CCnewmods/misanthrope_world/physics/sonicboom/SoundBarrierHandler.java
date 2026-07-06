package exp.CCnewmods.misanthrope_world.physics.sonicboom;

import exp.CCnewmods.misanthrope_world.physics.reentry.AerodynamicsConfig;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.network.SoundBarrierPacket;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.network.SupersonicRumblePacket;
import exp.CCnewmods.misanthrope_world.crackrender.network.CrackNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-side sonic boom crossing detector.
 *
 * <p>Called from {@code exp.CCnewmods.misanthrope_world.physics.sonicboom.SonicBoomSystemSetup}
 * each server tick (END phase).</p>
 *
 * <h3>Logic</h3>
 * <ol>
 *   <li>Iterate all {@link LoadedServerShip}s in the level.</li>
 *   <li>Read velocity magnitude (m/s) from VS2.</li>
 *   <li>If speed crosses Mach 1 upward AND cooldown is zero -> fire boom.</li>
 *   <li>Boom = MGE ShockwaveHandler.spawn() + S2C SoundBarrierPacket.</li>
 *   <li>Atmospheric pressure from MGE gates the boom (no boom in vacuum).</li>
 * </ol>
 *
 * <h3>Vanishing while supersonic</h3>
 * If a ship disappears (destroyed, or otherwise stops appearing in VS2's
 * ship list) while it was supersonic, the sudden vacuum where it was gets an
 * immediate implosion shockwave, and the pressure cone it had been trailing
 * snaps forward as a second, larger, delayed boom — see
 * {@link #handleVanishedWhileSonic} and {@code AerodynamicsConfig}'s
 * {@code vanish*} fields. Note this fires for any case where a ship stops
 * appearing in {@code shipWorld.getAllShips()} while marked supersonic — VS2
 * doesn't give an easy way from here to distinguish "actually destroyed"
 * from "chunk/dimension unloaded while supersonic", so both trigger it.
 * The latter should be rare in practice (ships don't typically stay loaded
 * exactly at the moment chunks unload while also coincidentally supersonic),
 * but flagging it as a known imprecision.
 *
 * <h3>MGE coupling</h3>
 * ShockwaveHandler and GridAtmosphereCompat are accessed via reflection guards
 * identical to the rest of MVS — try/catch, log once, no hard dependency.
 */
public final class SoundBarrierHandler {

    private SoundBarrierHandler() {}

    /** Per-ship state keyed by ship ID. */
    private static final Map<Long, SoundBarrierState> STATES = new ConcurrentHashMap<>();

    /** Delayed "pressure cone snaps forward" booms waiting to fire. */
    private static final ConcurrentLinkedQueue<PendingVanishBoom> PENDING_BOOMS = new ConcurrentLinkedQueue<>();

    private record PendingVanishBoom(String dimensionId, Vec3 pos, float strength, long fireAtGameTime) {
    }

    // ── MGE reflection ────────────────────────────────────────────────────────

    private static volatile boolean mgeResolved = false;
    private static java.lang.reflect.Method mgeSpawn   = null;  // ShockwaveHandler.spawn
    private static java.lang.reflect.Method mgeGetPres = null;  // GridAtmosphereCompat.getTotalPressure

    private static void resolveMge() {
        if (mgeResolved) return;
        mgeResolved = true;
        try {
            Class<?> sh = Class.forName("exp.CCnewmods.mge.shockwave.ShockwaveHandler");
            mgeSpawn = sh.getMethod("spawn", ServerLevel.class, BlockPos.class, float.class);
        } catch (Exception ignored) {}
        try {
            Class<?> gc = Class.forName("exp.CCnewmods.mge.grid.compat.GridAtmosphereCompat");
            mgeGetPres = gc.getMethod("getTotalPressure",
                    net.minecraft.world.level.Level.class, BlockPos.class);
        } catch (Exception ignored) {}
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called once per server tick (END phase) for each loaded level.
     */
    public static void serverTick(ServerLevel level) {
        resolveMge();
        var cfg = AerodynamicsConfig.INSTANCE;
        double machOneMs = cfg.machOneSpeedMs.get();
        String dimensionId = level.dimension().location().toString();

        // Iterate ships via VS2 game-thread API
        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld shipWorld) {
            for (var ship : shipWorld.getAllShips()) {
                if (!(ship instanceof LoadedServerShip loaded)) continue;
                tickShip(level, loaded, machOneMs, cfg);
            }

            // Detect ships that vanished (no longer in getAllShips()) while
            // supersonic, firing the implosion + delayed boom for each before
            // dropping their state entirely.
            var ids = new java.util.HashSet<Long>();
            for (var ship : shipWorld.getAllShips()) ids.add(ship.getId());

            for (var entry : STATES.entrySet()) {
                if (ids.contains(entry.getKey())) continue;
                SoundBarrierState state = entry.getValue();
                if (state.wasSonic && dimensionId.equals(state.lastDimensionId)) {
                    handleVanishedWhileSonic(level, entry.getKey(), state, cfg);
                }
            }
            STATES.keySet().removeIf(id -> !ids.contains(id));
        }

        processPendingBooms(level, dimensionId, cfg);
    }

    private static void processPendingBooms(ServerLevel level, String dimensionId, AerodynamicsConfig cfg) {
        if (PENDING_BOOMS.isEmpty()) return;
        long now = level.getGameTime();

        var it = PENDING_BOOMS.iterator();
        while (it.hasNext()) {
            PendingVanishBoom boom = it.next();
            if (!boom.dimensionId().equals(dimensionId)) continue;
            if (now < boom.fireAtGameTime()) continue;

            it.remove();
            BlockPos bpos = new BlockPos((int) boom.pos().x, (int) boom.pos().y, (int) boom.pos().z);
            spawnShockwave(level, bpos, boom.strength());

            SoundBarrierPacket pkt = new SoundBarrierPacket(boom.pos(), boom.strength(), 1.0f);
            double syncRadius = cfg.clientSyncRadius.get();
            CrackNetwork.CHANNEL.send(
                    PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                            boom.pos().x, boom.pos().y, boom.pos().z, syncRadius * syncRadius,
                            level.dimension())),
                    pkt);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        // State is keyed by ship ID globally; just clear all on unload to be safe.
        STATES.clear();
    }

    // ── Per-ship logic ────────────────────────────────────────────────────────

    private static void tickShip(ServerLevel level, LoadedServerShip ship,
                                  double machOneMs, AerodynamicsConfig cfg) {
        long id = ship.getId();
        SoundBarrierState state = STATES.computeIfAbsent(id, k -> new SoundBarrierState());

        Vector3dc vel = ship.getVelocity();
        // VS2 velocity is in blocks/tick; multiply by 20 to get blocks/s (= m/s).
        double speedMs = vel.length() * 20.0;

        var pos = ship.getTransform().getPositionInWorld();
        state.lastPos = new Vec3(pos.x(), pos.y(), pos.z());
        state.lastDimensionId = level.dimension().location().toString();

        // Tick down cooldown
        if (state.cooldownTicks > 0) {
            state.cooldownTicks--;
            boolean stillSonic = speedMs >= machOneMs;
            // A ship can drop below Mach 1 while still in its post-boom
            // cooldown — this branch returns early, so without this check
            // the rumble-stop packet below would never fire for that case.
            if (!stillSonic && state.wasSonic) {
                sendRumbleTransition(level, ship, id, false, cfg);
            }
            state.lastSpeedMs = speedMs;
            state.wasSonic = stillSonic;
            return;
        }

        boolean isSonic = speedMs >= machOneMs;
        boolean crossing = isSonic && !state.wasSonic; // rising edge
        boolean dropping = !isSonic && state.wasSonic;  // falling edge

        if (crossing) {
            fireBoom(level, ship, speedMs, machOneMs, cfg);
            state.cooldownTicks = cfg.boomCooldownTicks.get();
        }

        if (crossing || dropping) {
            sendRumbleTransition(level, ship, id, isSonic, cfg);
        }

        state.wasSonic    = isSonic;
        state.lastSpeedMs = speedMs;
    }

    private static void sendRumbleTransition(ServerLevel level, LoadedServerShip ship,
                                              long id, boolean active, AerodynamicsConfig cfg) {
        var pos = ship.getTransform().getPositionInWorld();
        Vec3 posVec = new Vec3(pos.x(), pos.y(), pos.z());
        double syncRadius = cfg.clientSyncRadius.get();
        CrackNetwork.CHANNEL.send(
                PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                        pos.x(), pos.y(), pos.z(), syncRadius * syncRadius,
                        level.dimension())),
                new SupersonicRumblePacket(id, active, posVec));
    }

    /**
     * A ship vanished while supersonic. Physically: the air rushes into the
     * sudden vacuum where it was (immediate implosion shockwave), and the
     * pressure cone it had been trailing snaps forward as a second, larger,
     * delayed boom.
     *
     * <p>Uses {@code state.lastPos}/{@code lastDimensionId}/{@code lastSpeedMs}
     * since the ship object itself is already gone by the time this fires —
     * there's nothing left to query position/velocity from directly.</p>
     */
    private static void handleVanishedWhileSonic(ServerLevel level, long shipId,
                                                  SoundBarrierState state,
                                                  AerodynamicsConfig cfg) {
        Vec3 pos = state.lastPos;
        BlockPos bpos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);

        // Stop the rumble at its last known position — no ship left to send
        // a normal falling-edge transition from.
        double syncRadius = cfg.clientSyncRadius.get();
        CrackNetwork.CHANNEL.send(
                PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                        pos.x, pos.y, pos.z, syncRadius * syncRadius, level.dimension())),
                new SupersonicRumblePacket(shipId, false, pos));

        double machOneMs = cfg.machOneSpeedMs.get();
        double speedExcess = Math.max(1.0, state.lastSpeedMs / machOneMs);
        float pressure = getPressure(level, bpos);
        float presScale = Math.min(1f, pressure / 1013.25f);
        if (presScale < 0.01f) return; // vacuum — nothing to rush in, no boom to release

        float base = (float) (cfg.boomBaseStrength.get() * presScale * Math.min(speedExcess, 5.0));

        // Immediate implosion
        float implosionStrength = (float) (base * cfg.vanishImplosionStrength.get());
        if (implosionStrength > 0f) {
            spawnShockwave(level, bpos, implosionStrength);
        }

        // Delayed forward-snapping boom
        float delayedStrength = (float) (base * cfg.vanishDelayedBoomStrength.get());
        int delayTicks = cfg.vanishDelayedBoomTicks.get();
        if (delayedStrength > 0f && delayTicks > 0) {
            PENDING_BOOMS.add(new PendingVanishBoom(
                    state.lastDimensionId, pos, delayedStrength,
                    level.getGameTime() + delayTicks));
        } else if (delayedStrength > 0f) {
            // Zero delay configured — fire immediately alongside the implosion
            // rather than dropping it.
            spawnShockwave(level, bpos, delayedStrength);
        }
    }

    private static void fireBoom(ServerLevel level, LoadedServerShip ship,
                                  double speedMs, double machOneMs,
                                  AerodynamicsConfig cfg) {
        // Ship world-space position (nose approximation = ship transform origin)
        var pos = ship.getTransform().getPositionInWorld();
        BlockPos bpos = new BlockPos((int) pos.x(), (int) pos.y(), (int) pos.z());

        // Atmospheric pressure gate
        float pressure = getPressure(level, bpos);
        float minPressure = cfg.boomMinPressureMbar.get().floatValue();
        // Pressure scale factor: 0 in vacuum → 1 at standard atmosphere
        float presScale = Math.min(1f, pressure / 1013.25f);
        if (presScale < 0.01f) return; // near-vacuum — silent crossing

        // Shockwave strength: base × pressure × speed excess
        double speedExcess = speedMs / machOneMs; // ≥ 1
        float strength = (float) (cfg.boomBaseStrength.get() * presScale * Math.min(speedExcess, 5.0));

        // Fire MGE shockwave
        spawnShockwave(level, bpos, strength);

        // Rattle the ship — MVSE's fireBoom never actually called this (see
        // RattlePhysicsController's class doc for the full story). Passing
        // the same values ShipRattleAttachment.trigger() expected.
        if (cfg.rattleDurationTicks.get() > 0) {
            Vector3dc velDir = new org.joml.Vector3d(ship.getVelocity()).normalize();
            double shipMass = ship.getInertiaData().getShipMass();
            RattlePhysicsController.trigger(
                    ship.getId(), level.dimension().location().toString(),
                    speedMs, machOneMs, velDir, shipMass,
                    cfg.rattleDurationTicks.get());
        }

        // S2C packet: 3 distance-scaled variants (close / medium / far)
        Vec3 originVec = new Vec3(pos.x(), pos.y(), pos.z());
        SoundBarrierPacket pkt = new SoundBarrierPacket(originVec, strength, presScale);
        double syncRadius = cfg.clientSyncRadius.get();
        CrackNetwork.CHANNEL.send(
                PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                        pos.x(), pos.y(), pos.z(), syncRadius * syncRadius,
                        level.dimension())),
                pkt);
    }

    // ── MGE helpers (guarded) ─────────────────────────────────────────────────

    private static float getPressure(ServerLevel level, BlockPos pos) {
        if (mgeGetPres == null) return 1013.25f; // assume standard if MGE absent
        try {
            Object result = mgeGetPres.invoke(null, level, pos);
            return ((Number) result).floatValue();
        } catch (Exception e) {
            return 1013.25f;
        }
    }

    private static void spawnShockwave(ServerLevel level, BlockPos pos, float strength) {
        if (mgeSpawn == null) return;
        try {
            mgeSpawn.invoke(null, level, pos, strength);
        } catch (Exception ignored) {}
    }
}
