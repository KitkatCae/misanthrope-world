package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives explosion/fragment-spawned VS2 ships a temporary lifespan: once a
 * tracked ship has been effectively motionless for {@link #STILL_TICKS_THRESHOLD}
 * ticks, it's disassembled back into placed static blocks at its current
 * resting position via {@link StructuralShipDisassembler}.
 *
 * <p>Only ships explicitly registered via {@link #register} are tracked —
 * this is NOT applied to player-built ships. {@link FailureDispatcher} calls
 * {@code register} right after assembling a CAVE_IN/FRAGMENT_VS2 failure
 * group into a ship.
 *
 * <h3>Stillness definition</h3>
 * A ship counts as "still" on a given tick when both its linear speed and
 * angular speed are below their respective thresholds. The still-tick counter
 * resets to zero the instant either exceeds its threshold — e.g. a ship that
 * lands, sits for a while, then gets knocked by a later explosion starts its
 * countdown over. Values are intentionally conservative starting points;
 * tune {@link #LINEAR_STILL_THRESHOLD} / {@link #ANGULAR_STILL_THRESHOLD} /
 * {@link #STILL_TICKS_THRESHOLD} against actual play rather than treating
 * them as final.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TemporaryShipLifecycle {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/TemporaryShipLifecycle");

    /** Blocks/tick below which a ship's linear motion counts as "still". */
    public static final double LINEAR_STILL_THRESHOLD = 0.02;

    /** Radians/tick below which a ship's rotation counts as "still". */
    public static final double ANGULAR_STILL_THRESHOLD = 0.01;

    /** Ticks of continuous stillness required before disassembly (100 = 5s). */
    public static final int STILL_TICKS_THRESHOLD = 100;

    private record Tracked(ServerLevel level, long shipId, int stillTicks) {
        Tracked withStillTicks(int t) {
            return new Tracked(level, shipId, t);
        }
    }

    private static final Map<Long, Tracked> TRACKED = new ConcurrentHashMap<>();

    private TemporaryShipLifecycle() {
    }

    /**
     * Starts tracking a ship for eventual reversion to static blocks.
     * Call this immediately after assembling a temporary fragment/cave-in ship.
     */
    public static void register(ServerLevel level, long shipId) {
        TRACKED.put(shipId, new Tracked(level, shipId, 0));
    }

    /** Stops tracking a ship without disassembling it (e.g. it was destroyed some other way). */
    public static void unregister(long shipId) {
        TRACKED.remove(shipId);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (TRACKED.isEmpty()) return;

        for (Tracked t : TRACKED.values()) {
            var shipWorld = VSGameUtilsKt.getShipWorldNullable(t.level());
            if (!(shipWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld ssw)) continue;

            Ship raw = ssw.getLoadedShips().getById(t.shipId());
            if (!(raw instanceof LoadedServerShip ship)) {
                // Ship unloaded or already gone — stop tracking, nothing to revert.
                TRACKED.remove(t.shipId());
                continue;
            }

            double linSpeed = ship.getVelocity().length();
            double angSpeed = ship.getAngularVelocity().length();

            if (linSpeed < LINEAR_STILL_THRESHOLD && angSpeed < ANGULAR_STILL_THRESHOLD) {
                int nextStill = t.stillTicks() + 1;
                if (nextStill >= STILL_TICKS_THRESHOLD) {
                    TRACKED.remove(t.shipId());
                    try {
                        StructuralShipDisassembler.disassemble(t.level(), ship);
                    } catch (Exception e) {
                        LOGGER.error("[TemporaryShipLifecycle] Failed to disassemble ship {}: {}",
                                t.shipId(), e.getMessage());
                    }
                } else {
                    TRACKED.put(t.shipId(), t.withStillTicks(nextStill));
                }
            } else if (t.stillTicks() != 0) {
                TRACKED.put(t.shipId(), t.withStillTicks(0));
            }
        }
    }
}
