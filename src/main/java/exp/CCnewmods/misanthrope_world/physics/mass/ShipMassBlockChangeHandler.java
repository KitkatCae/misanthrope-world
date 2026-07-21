package exp.CCnewmods.misanthrope_world.physics.mass;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Keeps {@link ShipMassCache} incrementally correct as blocks are placed/broken on a ship — the O(1)
 * "small addition/subtraction" half of the design (the other half, the "big calculation," is
 * {@link ShipMassLifecycleBridge} on ship load, and full recomputes on split/merge).
 *
 * <p>Same event pattern as {@code StructuralWorldEventHandler} elsewhere in MWorld, but that handler
 * only seeds the structural-stress dirty queue and doesn't filter by ship-space at all — this is a
 * separate, purpose-built hook for ship mass specifically.
 *
 * <h3>Why break needs the state read at event time</h3>
 * Same reasoning as {@code StructuralWorldEventHandler.onBlockBreak}: {@code BlockEvent.BreakEvent}
 * fires before the block is actually removed, so {@code event.getState()} still reports the real
 * (about-to-be-removed) block — that's the mass we need to subtract.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShipMassBlockChangeHandler {

    private ShipMassBlockChangeHandler() {
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) return;

        BlockState state = event.getState();
        if (state.isAir()) return;

        double density = BlockPhysicsRegistry.get(state).densityKgM3;
        ShipMassCache.adjustMass(ship.getId(), -density);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) return;

        BlockState placed = event.getPlacedBlock();
        if (placed.isAir()) return;

        double density = BlockPhysicsRegistry.get(placed).densityKgM3;
        ShipMassCache.adjustMass(ship.getId(), density);
    }
}
