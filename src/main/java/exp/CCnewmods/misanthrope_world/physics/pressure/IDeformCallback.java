package exp.CCnewmods.misanthrope_world.physics.pressure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Callback invoked when a boundary block's visual deformation state changes
 * enough to warrant sending a network update to clients.
 *
 * <p>Implementations choose the correct packet channel:
 * <ul>
 *   <li>World-space handler — sends via {@code WorldPressureNetwork}</li>
 *   <li>MVSE hull handler — sends via {@code HullPressureNetwork} with shipId</li>
 * </ul>
 *
 * <p>Change detection (whether the visual state differs enough from the last
 * sent state to actually send) is performed by the caller
 * ({@link WorldSpacePressureHandler} or MVSE's solver) before invoking this
 * callback, so implementations can unconditionally encode and send.
 *
 * <p>Three event types share this interface via separate methods:
 * {@link #onDeformUpdate} (elastic/inflation change), {@link #onStageAdvance}
 * (plastic stage tick-over), and {@link #onTensionPause} (enter inter-stage
 * pause — triggers the low-groan sound on clients).
 */
public interface IDeformCallback {

    /**
     * Visual deformation or inflation fraction has changed.
     *
     * @param level       server level
     * @param pos         block position
     * @param state       current block pressure state (read-only for callback)
     * @param deltaMbar   current signed ΔP
     * @param pd          pressure data
     */
    void onDeformUpdate(ServerLevel level,
                        BlockPos pos,
                        BlockPressureState state,
                        float deltaMbar,
                        BlockPhysicsData.PressureData pd);

    /**
     * Deformation stage has advanced (tension pause just ended).
     *
     * @param level      server level
     * @param pos        block position
     * @param newStage   new deformation stage index
     * @param deltaMbar  signed ΔP
     * @param pd         pressure data (for breach mode, sent to clients so they
     *                   can anticipate what happens at final stage)
     */
    void onStageAdvance(ServerLevel level,
                        BlockPos pos,
                        int newStage,
                        float deltaMbar,
                        BlockPhysicsData.PressureData pd);

    /**
     * Block has entered a tension pause (inter-stage wait).
     * Client plays a low structural groan and begins flicker animation.
     *
     * @param level        server level
     * @param pos          block position
     * @param currentStage current stage (not yet advanced)
     * @param deltaMbar    signed ΔP
     */
    void onTensionPause(ServerLevel level,
                        BlockPos pos,
                        int currentStage,
                        float deltaMbar);
}
