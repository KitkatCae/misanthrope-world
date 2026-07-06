package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Seeds {@link StructuralStressField}'s reactive dirty queue (Option A) for
 * the two world-mutation paths that need it and don't already trigger it on
 * their own: player/tool block breaks, and explosions.
 *
 * <p>This replaces {@code MinecollapseBypassHandler}. Minecollapse has been
 * removed from the project entirely — {@link FailureDispatcher}'s CAVE_IN
 * failure mode now routes through the same VS2 fragmentation path as
 * FRAGMENT_VS2 (see FailureDispatcher), so there is no external mod left to
 * coordinate with here. This class exists purely to make sure the right
 * positions get re-evaluated by the existing structural stress simulation.
 *
 * <h3>Why block breaks need an explicit hook</h3>
 * {@code StructuralStressField.onNeighborNotify} already reacts to
 * {@code BlockEvent.NeighborNotifyEvent}, but it reads the CURRENT state at
 * the notifying position to decide whether to enqueue it — and for a block
 * that was just removed, that position is now air, which never has
 * structural data. So neighbour-notify alone does not re-evaluate the blocks
 * around a break; we mark {@code pos} dirty explicitly, at
 * {@code BlockEvent.BreakEvent} time (before removal, while the position
 * still reports its real block state) so {@code markDirty}'s own 26-neighbour
 * spread covers everything that might have lost support.
 *
 * <h3>Why explosions need a separate hook</h3>
 * Explosions remove many blocks at once outside of individual
 * {@code BlockEvent.BreakEvent} firings, so the break-event hook above never
 * sees them. Previously nothing seeded the dirty queue for the blocks left
 * behind after an explosion, so debris that should have been caught by the
 * EXISTING compressive-column / tensile-span checks in
 * {@link StructuralStressField} just sat there indefinitely — floating,
 * disconnected, or in an overloaded pillar, with nothing ever re-evaluating
 * it. We don't reimplement that check here; we only make sure it actually
 * runs, by marking every position the explosion affected (plus their
 * neighbours, via {@code markDirty}) dirty once the blocks have actually been
 * removed from the world.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StructuralWorldEventHandler {

    private StructuralWorldEventHandler() {
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        if (BlockPhysicsRegistry.get(state).structural != null) {
            StructuralStressField.markDirty(level, pos);
        }
    }

    /**
     * Fires before the explosion actually removes any blocks. We capture the
     * affected position list here (it isn't available afterward) and defer
     * the dirty-marking to the next server tick, by which point
     * {@code Explosion.finalizeExplosion} has already cleared the blocks —
     * we want to evaluate what's left standing around the crater, not the
     * blocks that are about to vanish.
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        List<BlockPos> affected = List.copyOf(event.getAffectedBlocks());
        if (affected.isEmpty()) return;

        level.getServer().execute(() -> {
            if (level.isClientSide()) return;
            for (BlockPos pos : affected) {
                // markDirty enqueues pos plus its full 26-neighbour shell —
                // exactly the blocks that could now be floating, disconnected,
                // or carrying more column load than before. We don't gate this
                // on the (now air) block having structural data, unlike the
                // break-event hook above, since the interesting targets here
                // are what's left AROUND each removed position, not the
                // removed position itself.
                StructuralStressField.markDirty(level, pos);
            }
        });
    }
}
