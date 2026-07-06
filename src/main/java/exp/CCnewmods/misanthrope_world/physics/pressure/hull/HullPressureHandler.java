package exp.CCnewmods.misanthrope_world.physics.pressure.hull;

import exp.CCnewmods.misanthrope_world.physics.pressure.PressurePhysicsConfig;
import net.minecraft.server.level.ServerLevel;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level server-side pressure physics driver.
 *
 * <p>Called from {@code MVSForgeEvents.onServerTick} every tick, after
 * {@code VaporiseHandler} and {@code ImpactHandler}. Manages the lifecycle of
 * {@link HullPressureState} objects and delegates per-ship physics to
 * {@link PressureDifferentialSolver}.</p>
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>A {@link HullPressureState} is created lazily when a ship is first
 *       evaluated (its speed or position means it might have a pressure
 *       differential).</li>
 *   <li>States are removed when the ship is deleted ({@link #onShipRemoved})
 *       or the level unloads ({@link #onLevelUnload}).</li>
 * </ul>
 */
public final class HullPressureHandler {

    private HullPressureHandler() {}

    /** Per-level ship states. Key: dimension location string. */
    private static final Map<String, Map<Long, HullPressureState>> STATES =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Server tick
    // -------------------------------------------------------------------------

    public static void serverTick(ServerLevel level) {
        PressurePhysicsConfig cfg = PressurePhysicsConfig.INSTANCE;
        if (!cfg.enabled.get()) return;

        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (!(vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld shipWorld))
            return;

        long tick    = level.getGameTime();
        String dimKey = level.dimension().location().toString();
        var levelStates = STATES.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());

        for (var ship : shipWorld.getAllShips()) {
            if (!(ship instanceof LoadedServerShip loaded)) continue;
            HullPressureState state = levelStates.computeIfAbsent(
                    loaded.getId(), id -> new HullPressureState(id));
            PressureDifferentialSolver.tick(level, loaded, state, tick, cfg);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle hooks
    // -------------------------------------------------------------------------

    public static void onShipRemoved(long shipId, ServerLevel level) {
        String dimKey = level.dimension().location().toString();
        var levelStates = STATES.get(dimKey);
        if (levelStates != null) levelStates.remove(shipId);
        PressureDifferentialSolver.onShipRemoved(shipId);
    }

    public static void onLevelUnload(ServerLevel level) {
        STATES.remove(level.dimension().location().toString());
    }
}
