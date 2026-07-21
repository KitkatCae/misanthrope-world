package exp.CCnewmods.misanthrope_world.physics.structural.grid;

import exp.CCnewmods.misanthrope_world.pos.WorldPos;
import net.minecraft.core.BlockPos;

/**
 * Identifies one 16³ {@link StressSection} by section coordinates.
 * <p>
 * Deliberately {@code long}-based and independent of vanilla's {@code ChunkPos}
 * (int X/Z + separate Y section index tied to a {@code LevelChunk}). See
 * {@code StressGrid_Design_v1.md} — the stress grid's addressing is meant to
 * stay agnostic to how the underlying world storage represents coordinates.
 * <p>
 * Primary path is {@link #fromWorldPos(WorldPos)} — {@link WorldPos} is
 * MWorld's own internal position type (see its doc). {@link #fromBlockPos}
 * exists purely as the vanilla compatibility-boundary convenience, for call
 * sites that haven't been converted to carry {@code WorldPos} yet.
 */
public record SectionKey(long sectionX, long sectionY, long sectionZ) {

    /** Primary path: MWorld's own internal position type, coordinate-system-agnostic. */
    public static SectionKey fromWorldPos(WorldPos pos) {
        return new SectionKey(
                Math.floorDiv(pos.x(), 16),
                Math.floorDiv(pos.y(), 16),
                Math.floorDiv(pos.z(), 16));
    }

    /**
     * Vanilla compatibility-boundary convenience — equivalent to
     * {@code fromWorldPos(WorldPos.fromBlockPos(pos))}, provided directly so
     * call sites that haven't migrated to WorldPos yet don't need a manual
     * conversion step at every use.
     */
    public static SectionKey fromBlockPos(BlockPos pos) {
        return new SectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    /** Local index of a world position within the section this key identifies. */
    public int localIndex(WorldPos pos) {
        int lx = (int) Math.floorMod(pos.x(), 16);
        int ly = (int) Math.floorMod(pos.y(), 16);
        int lz = (int) Math.floorMod(pos.z(), 16);
        return StressSection.index(lx, ly, lz);
    }

    /** Vanilla compatibility-boundary convenience for {@link #localIndex(WorldPos)}. */
    public int localIndex(BlockPos pos) {
        int lx = pos.getX() & 15;
        int ly = pos.getY() & 15;
        int lz = pos.getZ() & 15;
        return StressSection.index(lx, ly, lz);
    }
}
