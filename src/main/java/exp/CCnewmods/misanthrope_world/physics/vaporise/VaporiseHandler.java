package exp.CCnewmods.misanthrope_world.physics.vaporise;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.physics.vaporise.network.VaporisePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side vaporisation evaluator.
 *
 * <p>Runs each server tick before MWorld's {@code ImpactHandler} (a separate
 * mod's tick subscriber now, see MVSForgeEvents for the cross-mod ordering note),
 * evaluating every loaded ship for the burnup condition. If a ship exceeds the
 * speed-to-mass threshold, it is immediately deleted and a {@link VaporisePacket}
 * is broadcast to nearby clients.</p>
 *
 * <h3>Speed-to-mass ratio model</h3>
 * Real atmospheric burnup depends on drag force relative to the object's
 * ability to absorb heat. For a simplified game model:
 * <pre>
 *   burnupRatio = speedMs / sqrt(mass)
 * </pre>
 * A small, very fast object (low mass, high speed) vaporises easily — like a
 * meteor or a bullet. A large slow object (high mass, moderate speed) will
 * crater or embed instead.
 *
 * The threshold is configurable via {@link VaporiseConfig}. Default: 18.0,
 * meaning a 1 kg object at 18 m/s, a 100 kg object at 180 m/s, and a
 * 10,000 kg ship at 1800 m/s would all hit the threshold.
 *
 * <h3>Pre-emption of impact system</h3>
 * Returns {@code true} when a ship is vaporised so that
 * {@code ImpactHandler.serverTick} can skip that ship — the ship is already
 * gone before the impact detector runs.
 *
 * <h3>Cooldown</h3>
 * Ships on cooldown from a prior impact event ({@link
 * exp.CCnewmods.misanthrope_world.physics.structural.vs2.ImpactHandler} manages this map)
 * are skipped. Vaporisation always wins over cooldown if the ratio is exceeded.
 */
public final class VaporiseHandler {

    private VaporiseHandler() {}

    private static final String PHOTON_MODID = "photon";

    /** Ships pending vaporisation this tick (to avoid concurrent modification). */
    private static final Map<Long, LoadedServerShip> PENDING_VAPORISE = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Evaluates all loaded ships in the level for vaporisation.
     * Call at START of server tick, before {@code ImpactHandler.serverTick}.
     *
     * @return {@code true} if at least one ship was vaporised this tick
     */
    public static boolean serverTick(ServerLevel level) {
        VaporiseConfig cfg = VaporiseConfig.INSTANCE;
        if (!cfg.enabled.get()) return false;

        double threshold = cfg.burnupRatioThreshold.get();
        boolean photon   = ModList.get().isLoaded(PHOTON_MODID);
        boolean any      = false;

        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (!(vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld shipWorld)) {
            return false;
        }

        for (var ship : shipWorld.getAllShips()) {
            if (!(ship instanceof LoadedServerShip loaded)) continue;

            var vel     = loaded.getVelocity();
            double speedMs = vel.length() * 20.0; // blocks/tick × 20 = m/s
            // Was hardcoded to 1.0 (a fast-exit optimization value, not the
            // real configured minimum) — cfg.minVaporiseSpeedMs was defined
            // but never actually read anywhere. Using it now.
            if (speedMs < cfg.minVaporiseSpeedMs.get()) continue;

            double mass       = loaded.getInertiaData().getShipMass();
            if (mass <= 0) continue;

            // maxVaporiseMassKg was also defined but never read — heavy
            // ships (capital ships, fortresses) should crater/embed instead
            // of vaporising even at high speed; 0 = no cap.
            double maxMass = cfg.maxVaporiseMassKg.get();
            if (maxMass > 0 && mass > maxMass) continue;

            double ratio = speedMs / Math.sqrt(mass);
            if (ratio < threshold) continue;

            // Ship exceeds burnup threshold — vaporise
            PENDING_VAPORISE.put(loaded.getId(), loaded);
        }

        if (PENDING_VAPORISE.isEmpty()) return false;

        for (var entry : PENDING_VAPORISE.entrySet()) {
            LoadedServerShip ship = entry.getValue();
            vaporiseShip(level, ship, photon, cfg);
            any = true;
        }
        PENDING_VAPORISE.clear();

        return any;
    }

    // -------------------------------------------------------------------------
    // Vaporisation
    // -------------------------------------------------------------------------

    private static void vaporiseShip(ServerLevel level, LoadedServerShip ship,
                                      boolean photon, VaporiseConfig cfg) {
        // Capture state before deletion
        var worldPos = ship.getTransform().getPositionInWorld();
        Vec3 origin  = new Vec3(worldPos.x(), worldPos.y(), worldPos.z());
        double mass  = ship.getInertiaData().getShipMass();
        var vel      = ship.getVelocity();
        double speedMs = vel.length() * 20.0;

        // Delete the ship — no block placement, no crater, pure deletion.
        // Uses MWorld's centralized deletion choke point (also notifies
        // ImpactHandler/HullPressureHandler of pending state for this ship,
        // same as fragment-ship and disassembly deletions do) rather than
        // calling ShipAssembler directly, so vaporize doesn't leave any of
        // those systems with stale per-ship state.
        exp.CCnewmods.misanthrope_world.physics.structural.vs2.StructuralShipDisassembler
                .deleteShip(level, ship);

        // Notify clients: flash + billboard + Photon FX
        VaporisePacket.sendToNear(level, origin, mass, speedMs, photon);

        // Optional: spawn a shockwave via MGE if it's loaded — triggerMgeShockwave
        // was defined in config but never actually checked before this fix.
        if (cfg.triggerMgeShockwave.get()) {
            triggerMgeShockwave(level, origin, mass, speedMs);
        }
    }

    /**
     * Triggers an MGE shockwave at the vaporisation point if Misanthrope Gas
     * Engine is loaded. The shockwave strength is proportional to kinetic energy.
     * No-ops cleanly if MGE is absent.
     */
    private static void triggerMgeShockwave(ServerLevel level, Vec3 origin,
                                             double mass, double speedMs) {
        if (!ModList.get().isLoaded("mge")) return;
        try {
            double ke       = 0.5 * mass * speedMs * speedMs;
            // Map KE to shockwave strength: 1 MJ → strength 1, 100 MJ → strength ~4.6
            float strength  = (float) Math.min(50.0, Math.log1p(ke / 1_000_000.0) * 2.0);
            if (strength < 0.5f) return;

            Class<?> swh   = Class.forName("exp.CCnewmods.mge.shockwave.ShockwaveHandler");
            var spawn       = swh.getMethod("spawn", ServerLevel.class,
                    net.minecraft.core.BlockPos.class, float.class);
            spawn.invoke(null, level, net.minecraft.core.BlockPos.containing(
                    origin.x, origin.y, origin.z), strength);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Query: is this ship scheduled for vaporisation this tick?
    // ImpactHandler calls this to skip ships already being vaporised.
    // -------------------------------------------------------------------------

    public static boolean isPendingVaporise(long shipId) {
        return PENDING_VAPORISE.containsKey(shipId);
    }
}
