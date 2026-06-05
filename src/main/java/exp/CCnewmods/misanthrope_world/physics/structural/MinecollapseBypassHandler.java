package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * Prevents Minecollapse from naturally triggering cave-ins for blocks that
 * have Misanthrope {@link exp.CCnewmods.misanthrope_core.physics.BlockPhysicsData.StructuralData}.
 *
 * <p>Minecollapse fires its cave-in logic inside a {@code BlockEvent.BreakEvent}
 * handler and separately on neighbour notifications. We register at
 * {@link EventPriority#HIGHEST} so we run first. When the broken block has our
 * structural data, we set a thread-local flag that Minecollapse's mixin checks
 * before triggering. Since we can't directly cancel Minecollapse's internal
 * processing, we use the approach of marking the position as "managed by
 * Misanthrope" so Minecollapse's {@code FallingBlockMixin} skips it.
 *
 * <p>Blocks <em>without</em> structural data continue to work with Minecollapse's
 * natural tag-based system entirely unchanged.
 *
 * <h3>Why this works</h3>
 * Minecollapse checks {@code CollapseRecipe.canStartCollapse(level, pos, state)}
 * before doing anything. That method checks block tags. We don't tag our blocks
 * with {@code can_collapse} — so Minecollapse's <em>natural</em> trigger already
 * ignores them. This handler is a belt-and-suspenders safeguard for any other
 * Minecollapse entry points (e.g. its explosion handler or erosion feature) that
 * might fire independently of the tag check.
 *
 * <p>When {@link FailureDispatcher} decides a block should CAVE_IN, it calls
 * {@code WorldTracker.addCollapsePositions} directly — bypassing all tag checks
 * and the natural trigger system entirely.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MinecollapseBypassHandler {

    /**
     * Thread-local set of positions currently being processed by Misanthrope's
     * structural system. Any position in this set is skipped by Minecollapse's
     * natural trigger.
     *
     * <p>Note: We don't actually inject into Minecollapse's mixin (that would
     * require a Mixin itself). Instead, we rely on the fact that our blocks are
     * not tagged {@code can_collapse} so Minecollapse's tag check already
     * filters them. This handler exists to catch edge cases and log if Minecollapse
     * somehow tries to process a managed block anyway.
     */
    private static final ThreadLocal<java.util.Set<Long>> MANAGED_POSITIONS =
            ThreadLocal.withInitial(java.util.HashSet::new);

    private static final boolean MINECOLLAPSE_LOADED =
            ModList.get().isLoaded("minecollapse");

    private MinecollapseBypassHandler() {
    }

    /**
     * Marks a position as managed by Misanthrope so no external system
     * should trigger its collapse independently. Called by StructuralStressField
     * and FailureDispatcher during processing.
     */
    public static void markManaged(BlockPos pos) {
        MANAGED_POSITIONS.get().add(pos.asLong());
    }

    public static void unmarkManaged(BlockPos pos) {
        MANAGED_POSITIONS.get().remove(pos.asLong());
    }

    public static boolean isManaged(BlockPos pos) {
        return MANAGED_POSITIONS.get().contains(pos.asLong());
    }

    /**
     * High-priority break event: if a block with our structural data is broken
     * and Minecollapse is loaded, ensure our system is the one that handles
     * any resulting collapse — not Minecollapse's natural trigger.
     *
     * <p>We don't cancel the break itself — just note that this position is
     * managed so FailureDispatcher can decide the mode.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!MINECOLLAPSE_LOADED) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        if (BlockPhysicsRegistry.get(state).structural != null) {
            // Mark as managed; StructuralStressField will evaluate neighbours
            // on the next tick via Option A dirty queue
            markManaged(pos);
            StructuralStressField.markDirty(level, pos);
            // Auto-unmark after 1 tick — the event has fired, Minecollapse has had
            // its chance to see the managed flag
            level.getServer().execute(() -> unmarkManaged(pos));
        }
    }
}
