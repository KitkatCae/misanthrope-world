package exp.CCnewmods.misanthrope_world.physics;

import exp.CCnewmods.misanthrope_world.physics.field.ThermalField;
import exp.CCnewmods.misanthrope_world.physics.offgas.OffGasHandler;
import exp.CCnewmods.misanthrope_world.physics.phase.PhaseTransitionHandler;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Top-level coordinator for all Misanthrope world physics subsystems.
 *
 * <p>Each subsystem ({@link ThermalField}, {@link StructuralStressField},
 * {@link OffGasHandler}) registers its own {@code @SubscribeEvent} tick handlers
 * and runs independently. {@code WorldSimulation} does not replace those — it
 * provides:
 *
 * <ul>
 *   <li><b>Cross-system coupling</b> — notifying structural when thermal weakens
 *       a block, notifying off-gas when thermal raises a block above threshold.</li>
 *   <li><b>Per-level statistics</b> — tick cost tracking per subsystem so config
 *       can throttle expensive systems without breaking others.</li>
 *   <li><b>Server lifecycle</b> — clean shutdown coordination.</li>
 * </ul>
 *
 * <h3>Subsystem tick order within a server tick (END phase)</h3>
 * <ol>
 *   <li>{@link StructuralStressField} — processes dirty queue (reactive, Option A)</li>
 *   <li>{@link StructuralStressField} — background scan (ambient, Option B)</li>
 *   <li>{@link ThermalField} — thermal simulation tick + crack coupling</li>
 *   <li>{@link OffGasHandler} — off-gas emission</li>
 * </ol>
 * <p>
 * Each subsystem runs its own event listener independently. This class handles
 * only the coupling notifications between them.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldSimulation {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/WorldSim");

    /**
     * Running tick cost samples per level, for diagnostics.
     */
    private static final Map<ServerLevel, long[]> TICK_COSTS = new ConcurrentHashMap<>();

    private WorldSimulation() {
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TICK_COSTS.clear();
        LOGGER.info("[WorldSimulation] Shutdown complete.");
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /**
     * Logs aggregate tick cost statistics every 5 minutes (6000 ticks).
     * Only fires if the debug config flag is set.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // Diagnostics hook — expand when config is wired
    }

    /**
     * Called by ThermalField when a wall block's temperature crosses the first
     * strength-retention threshold. Forwards to StructuralStressField so the
     * structural simulation re-evaluates that position's load capacity.
     *
     * <p>This is the primary thermal→structural coupling point.
     */
    public static void onThermalWeakening(ServerLevel level, net.minecraft.core.BlockPos pos) {
        StructuralStressField.notifyThermalWeakening(level, pos);
    }

    /**
     * Called by ThermalField when a wall block's temperature exceeds a block's
     * {@code thermal_offgas.threshold_celsius}. Forwards to OffGasHandler so
     * the off-gas system evaluates that position for emission this tick.
     *
     * <p>This avoids OffGasHandler needing to scan all blocks for temperature
     * changes — ThermalField tells it exactly which blocks crossed the threshold.
     */
    public static void onThermalOffgasThreshold(ServerLevel level, net.minecraft.core.BlockPos pos) {
        OffGasHandler.markDirty(level, pos);
    }

    /**
     * Called by ThermalField when a block's temperature crosses the threshold
     * of any {@code on_melt}, {@code on_fire}, or {@code on_heat_above} entry
     * in its {@code material_properties/} phase transition list.
     *
     * <p>Forwards to {@link PhaseTransitionHandler} which evaluates the block's
     * full transition list and fires the first matching rule.
     */
    public static void onPhaseTransitionThermal(ServerLevel level, net.minecraft.core.BlockPos pos) {
        PhaseTransitionHandler.markDirtyThermal(level, pos);
    }

    /**
     * Called on {@code BlockEvent.NeighborNotifyEvent} when a fluid-bearing
     * block change is detected adjacent to a block with {@code on_water_contact}
     * defined. Triggers an immediate (non-queued) water-contact phase evaluation.
     *
     * <p>Water contact transitions are instantaneous — sodium touches water and
     * reacts immediately, not on the next available budget slot.
     */
    public static void onPhaseTransitionWaterContact(ServerLevel level,
                                                     net.minecraft.core.BlockPos pos) {
        PhaseTransitionHandler.evaluateWaterContact(level, pos);
    }
}
