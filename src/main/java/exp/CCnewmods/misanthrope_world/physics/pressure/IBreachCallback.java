package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Callback invoked by {@link BlockPressureEvaluator} when a boundary block's
 * pressure differential exceeds its ultimate strength.
 *
 * <p>Implementations handle the context-specific breach consequences:
 * <ul>
 *   <li>{@code WorldBreachCallback} — destroys the block, drops items,
 *       vents gas to MGE, injects shockwave stress into neighbours.</li>
 *   <li>{@code HullBreachCallback} (MVSE) — removes the block from the VS2
 *       ship, transforms position to world-space for MGE vent, checks for
 *       catastrophic hull failure and triggers
 *       {@code ShipDisassembler.disassemble}.</li>
 * </ul>
 *
 * <p>The callback is responsible for sending the breach network packet to
 * clients — it has the context to choose the right packet channel
 * (world-space vs ship-space).
 */
@FunctionalInterface
public interface IBreachCallback {

    /**
     * Called when {@code pos} has exceeded its pressure ultimate strength.
     *
     * @param level      server level
     * @param pos        position of the breaching block (world-space for
     *                   world-space handler; ship-space for MVSE)
     * @param pd         pressure data of the breached block (for breach mode)
     * @param volumeState the volume state owning this block (for integrity tracking)
     * @param deltaMbar  signed ΔP at time of breach (external − internal)
     */
    void onBreach(ServerLevel level,
                  BlockPos pos,
                  BlockPhysicsData.PressureData pd,
                  PressureVolumeState volumeState,
                  float deltaMbar);
}
