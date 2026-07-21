package exp.CCnewmods.misanthrope_world.physics.mass;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3ic;
import org.valkyrienskies.core.api.events.MergeEvent;
import org.valkyrienskies.core.api.events.ShipLoadEvent;
import org.valkyrienskies.core.api.events.SplitEvent;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

/**
 * Wires VS2's real, public ship-lifecycle events (confirmed in {@code core.api.events}, bytecode
 * against the actual {@code core:api} jar) to {@link ShipMassCache}, so a ship's exact mass gets
 * computed at load and recomputed whenever it splits or merges.
 *
 * <p>Registration follows the exact same lazy, guarded pattern as
 * {@code ImpactFractureBridge} elsewhere in this package (register once VS2 is confirmed loaded,
 * on the first server tick) — same reasoning: VS2 stays an optional dependency for MWorld's own
 * event subscriptions, unlike FullStop/Quantified which are hard dependencies.
 *
 * <h3>Split/merge: full recompute, not incremental reconciliation</h3>
 * {@code SplitEvent}/{@code MergeEvent} give voxel-region root positions ({@code Vector3ic}) and a
 * {@code dimensionId}, not the resulting {@code Ship} objects directly — lower-level than
 * {@code ShipLoadEvent}. Rather than trying to work out "old mass minus new mass" from that, each
 * root gets resolved to its ship via {@code getShipManagingPos} (same pattern as
 * {@link #onShipLoad}) and triggers a full {@link ShipMassCache#computeExactMassAsync}. Split/merge
 * are comparatively rare, already-expensive VS2-internal operations — correctness matters more here
 * than shaving the cost of an occasional full recompute (confirmed as the right call rather than
 * assumed).
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShipMassLifecycleBridge {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/ShipMassLifecycleBridge");

    private static volatile boolean registered = false;

    private ShipMassLifecycleBridge() {
    }

    private static void registerListenerIfNeeded() {
        if (registered) return;
        if (!ModList.get().isLoaded("valkyrienskies")) return;
        registered = true;
        try {
            var api = ValkyrienSkiesMod.INSTANCE.getApi();
            api.getShipLoadEvent().on(ShipMassLifecycleBridge::onShipLoad);
            api.getSplitEvent().on(ShipMassLifecycleBridge::onShipSplit);
            api.getMergeEvent().on(ShipMassLifecycleBridge::onShipMerge);
            LOGGER.info("[ShipMassLifecycleBridge] Registered on VS2 ShipLoadEvent/SplitEvent/MergeEvent");
        } catch (Exception e) {
            LOGGER.error("[ShipMassLifecycleBridge] Failed to register on VS2 ship lifecycle events: {}", e.getMessage());
        }
    }

    private static void onShipLoad(ShipLoadEvent event) {
        LoadedServerShip ship = event.getShip();
        try {
            ServerLevel level = resolveLevel(ship.getChunkClaimDimension());
            if (level == null) return;
            recomputeAndRecord(ship, level);
        } catch (Exception e) {
            LOGGER.error("[ShipMassLifecycleBridge] onShipLoad failed for ship {}: {}", ship.getId(), e.getMessage());
        }
    }

    private static void onShipSplit(SplitEvent event) {
        try {
            ServerLevel level = resolveLevel(event.getDimensionId());
            if (level == null) return;
            recomputeAt(level, event.getNewRootA());
            recomputeAt(level, event.getNewRootB());
        } catch (Exception e) {
            LOGGER.error("[ShipMassLifecycleBridge] onShipSplit failed: {}", e.getMessage());
        }
    }

    private static void onShipMerge(MergeEvent event) {
        try {
            ServerLevel level = resolveLevel(event.getDimensionId());
            if (level == null) return;
            recomputeAt(level, event.getNewRoot());
        } catch (Exception e) {
            LOGGER.error("[ShipMassLifecycleBridge] onShipMerge failed: {}", e.getMessage());
        }
    }

    /** Resolves a voxel-region root position to its ship and triggers a full recompute. */
    private static void recomputeAt(ServerLevel level, Vector3ic root) {
        Ship ship = VSGameUtilsKt.getShipManagingPos(level, new BlockPos(root.x(), root.y(), root.z()));
        if (!(ship instanceof LoadedServerShip loadedShip)) return;
        recomputeAndRecord(loadedShip, level);
    }

    private static void recomputeAndRecord(LoadedServerShip ship, ServerLevel level) {
        ShipMassCache.computeExactMassAsync(ship, level)
                .thenAccept(mass -> ShipMassCache.recordMass(ship.getId(), mass))
                .exceptionally(ex -> {
                    LOGGER.error("[ShipMassLifecycleBridge] Exact mass computation failed for ship {}: {}",
                            ship.getId(), ex.getMessage());
                    return null;
                });
    }

    /** Shared {@code dimensionId} -> {@code ServerLevel} resolution, used by all three event handlers. */
    private static ServerLevel resolveLevel(String dimensionId) {
        if (dimensionId == null) return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return VSGameUtilsKt.getLevelFromDimensionId(server, dimensionId);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        registerListenerIfNeeded();
    }
}

