package exp.CCnewmods.misanthrope_world.pos;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * MWorld's own internal position type — {@code long} x/y/z, used natively by
 * this mod's own systems. Vanilla's {@code BlockPos} (int-based) is treated
 * purely as an outside-compatibility boundary: something to convert to only
 * at the point of calling a vanilla/Forge API that demands it (e.g.
 * {@code Level.getBlockState}), and to convert from only at the point of
 * receiving one from outside code (a vanilla event, a player interaction).
 * Internal call chains should carry {@code WorldPos}, not {@code BlockPos}.
 * <p>
 * ── Status ────────────────────────────────────────────────────────────────────
 * This type exists as of the stress grid work (see
 * {@code StressGrid_Design_v1.md}) and is used natively there
 * ({@link exp.CCnewmods.misanthrope_world.physics.structural.grid.SectionKey}
 * and friends). It is NOT yet threaded through {@code StructuralStressField}'s
 * existing simulation loop (the dirty queue, {@code computeColumnLoad},
 * {@code connectedFailureBFS}, etc.) — that file is still entirely
 * {@code BlockPos}-based today. Retrofitting it is a real, separate migration
 * (touches the core simulation loop that just came out of two live crashes
 * this session) and deserves its own planning pass rather than being folded
 * in opportunistically. Treat this class as the target shape for that future
 * work, not as something already complete across the mod.
 * <p>
 * ── Range ─────────────────────────────────────────────────────────────────────
 * No range restriction is enforced here — vanilla positions always fit
 * (int widens losslessly to long), and MPM's absolute planetary coordinates
 * are long-native already, so both sides are naturally representable. Only
 * {@link #toBlockPos()} can fail (for a position outside vanilla's own int
 * range) — see its doc.
 */
public record WorldPos(long x, long y, long z) {

    // ── Vanilla compatibility boundary ───────────────────────────────────────

    /** The one place vanilla BlockPos enters this mod's internal representation. */
    public static WorldPos fromBlockPos(BlockPos pos) {
        return new WorldPos(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * The one place this mod's internal representation exits back to vanilla,
     * for calling a vanilla/Forge API that demands a real {@code BlockPos}.
     * <p>
     * Throws if this position is outside vanilla's representable int range —
     * that's a deliberate hard failure rather than silent truncation/wraparound,
     * since silently corrupting a coordinate is much worse than crashing at
     * the exact point where an out-of-range position was wrongly assumed to
     * be vanilla-compatible. Positions originating from MPM's planetary
     * coordinate space are not generally expected to survive this call for
     * planets larger than vanilla's own range — MPM's own relative/absolute
     * translation layer (see {@code PlanetDimensions}) is the correct path
     * for code that needs to interoperate with vanilla-facing chunk data
     * under MPM, not this method.
     */
    public BlockPos toBlockPos() {
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "WorldPos " + this + " is outside vanilla BlockPos's int range — "
                            + "this position did not originate from (or wasn't meant to re-enter) "
                            + "vanilla-chunked space. See toBlockPos() doc.");
        }
        return new BlockPos((int) x, (int) y, (int) z);
    }

    // ── Convenience — mirrors BlockPos's own relative()/above()/below() shape ──

    public WorldPos offset(long dx, long dy, long dz) {
        return new WorldPos(x + dx, y + dy, z + dz);
    }

    public WorldPos relative(Direction dir) {
        return relative(dir, 1);
    }

    public WorldPos relative(Direction dir, long distance) {
        return offset(
                (long) dir.getStepX() * distance,
                (long) dir.getStepY() * distance,
                (long) dir.getStepZ() * distance);
    }

    public WorldPos above() { return offset(0, 1, 0); }
    public WorldPos above(long distance) { return offset(0, distance, 0); }
    public WorldPos below() { return offset(0, -1, 0); }
    public WorldPos below(long distance) { return offset(0, -distance, 0); }

    // ── Distance ──────────────────────────────────────────────────────────────

    public long manhattanDistance(WorldPos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    @Override
    public String toString() {
        return "WorldPos[" + x + ", " + y + ", " + z + "]";
    }
}
