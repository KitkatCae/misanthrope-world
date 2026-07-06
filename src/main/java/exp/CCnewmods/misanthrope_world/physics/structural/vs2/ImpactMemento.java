package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A snapshot of world blocks disturbed by a single ship impact.
 *
 * <p>Created by {@link ImpactHandler} at the moment of impact. Stores the
 * original {@link BlockState} of every excavated or cleared block so that
 * {@link ImpactHandler} can restore them after the reversion timer expires
 * or the impacting ship is removed.</p>
 *
 * <h3>Thread safety</h3>
 * Mementos are created and consumed exclusively on the <b>game thread</b>
 * (server tick + VS2 ship-removal callback). No synchronisation required.
 *
 * <h3>Impact types</h3>
 * {@link Type#CRATER} — terrain was excavated in a spherical cavity; blocks
 * must be restored on reversion (placed back).<br>
 * {@link Type#EMBED} — blocks were cleared to fit the ship hull; on reversion
 * the ship should be removed first, then blocks restored.
 */
public final class ImpactMemento {

    // -------------------------------------------------------------------------
    // Enum
    // -------------------------------------------------------------------------

    public enum Type { CRATER, EMBED }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Which kind of impact created this memento. */
    public final Type type;

    /** VS2 ship ID that caused this impact. Used for ship-removal reversion. */
    public final long shipId;

    /**
     * Original states of all disturbed blocks, keyed by world-space position.
     * Uses {@link LinkedHashMap} to preserve insertion order, which matches
     * excavation order (surface → depth). Reversion replaces them in reverse
     * order so deeper blocks are placed first, avoiding floating-block issues.
     */
    public final LinkedHashMap<BlockPos, BlockState> originalStates;

    /**
     * World-space centre of the impact (ship nose / collision point).
     * Used for particle effects and range checks.
     */
    public final double cx, cy, cz;

    /**
     * Server tick at which this memento was created. Combined with
     * {@link ImpactConfig#reversionDelayTicks} to determine reversion time.
     */
    public final long createdTick;

    /**
     * True if this memento has already been reverted (either by timer or by
     * ship removal). Once true, the memento should be discarded.
     */
    public volatile boolean reverted = false;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public ImpactMemento(Type type, long shipId,
                         double cx, double cy, double cz,
                         long createdTick) {
        this.type          = type;
        this.shipId        = shipId;
        this.cx            = cx;
        this.cy            = cy;
        this.cz            = cz;
        this.createdTick   = createdTick;
        this.originalStates = new LinkedHashMap<>();
    }

    /**
     * Records the original state of a block before it is disturbed.
     * Call this BEFORE the block is replaced or removed.
     *
     * @param pos   world-space position
     * @param state the block's current (original) state
     */
    public void record(BlockPos pos, BlockState state) {
        // Only record each position once; the first call captures the true original.
        originalStates.putIfAbsent(pos, state);
    }

    /** Returns the number of blocks recorded in this memento. */
    public int size() {
        return originalStates.size();
    }

    /**
     * Provides blocks in reverse-insertion order for reversion
     * (deepest blocks placed first).
     */
    public Iterable<Map.Entry<BlockPos, BlockState>> reversedEntries() {
        // LinkedHashMap has no built-in reverse; collect to a list and iterate backwards
        var entries = new java.util.ArrayList<>(originalStates.entrySet());
        java.util.Collections.reverse(entries);
        return entries;
    }
}
