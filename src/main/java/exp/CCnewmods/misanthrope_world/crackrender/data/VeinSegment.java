package exp.CCnewmods.misanthrope_world.crackrender.data;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes how a single world-space crack vein passes through one block.
 * <p>
 * ── Coordinate system ─────────────────────────────────────────────────────────
 * Entry and exit points are expressed in face-local UV space [0,1]² on their
 * respective faces. (0.5, 0.5) is the face centre. The vein travels in a
 * straight line between entry and exit within this block, with slight curvature
 * added at render time from the seed.
 * <p>
 * ── Multi-vein blocks ─────────────────────────────────────────────────────────
 * A block at a crack intersection can have 2-3 VeinSegments passing through it.
 * Each is stored independently. The renderer draws all of them, suppresses all
 * their entry/exit faces, and replaces those faces with trough geometry.
 * <p>
 * ── Persistence ───────────────────────────────────────────────────────────────
 * Stored in the server-side CrackStateMap (WorldSavedData per chunk).
 * Synced to clients as part of CrackSyncPacket on chunk load and state change.
 */
public record VeinSegment(
        int veinId,        // globally unique ID for this vein path
        Direction entryFace,  // face the crack enters through (null = origin block)
        float entryU,        // UV on the entry face [0,1]
        float entryV,
        Direction exitFace,   // face the crack exits through (null = terminus block)
        float exitU,         // UV on the exit face [0,1]
        float exitV,
        long seed           // per-vein seed, stable, used for curvature generation
) {

    /**
     * True if this block is the origin of the vein (no inbound face).
     */
    public boolean isOrigin() {
        return entryFace == null;
    }

    /**
     * True if this block is the end of the vein (no outbound face).
     */
    public boolean isTerminus() {
        return exitFace == null;
    }

    /**
     * Whether this vein segment crosses the given face of the block
     * (either as entry or exit). Used by the face suppression mixin to
     * decide whether to suppress a specific face's BakedQuad output.
     */
    public boolean crossesFace(Direction face) {
        return face.equals(entryFace) || face.equals(exitFace);
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("VeinId", veinId);
        tag.putBoolean("HasEntry", entryFace != null);
        if (entryFace != null) {
            tag.putInt("EntryFace", entryFace.get3DDataValue());
            tag.putFloat("EntryU", entryU);
            tag.putFloat("EntryV", entryV);
        }
        tag.putBoolean("HasExit", exitFace != null);
        if (exitFace != null) {
            tag.putInt("ExitFace", exitFace.get3DDataValue());
            tag.putFloat("ExitU", exitU);
            tag.putFloat("ExitV", exitV);
        }
        tag.putLong("Seed", seed);
        return tag;
    }

    public static VeinSegment load(CompoundTag tag) {
        int veinId = tag.getInt("VeinId");
        Direction entry = tag.getBoolean("HasEntry")
                ? Direction.from3DDataValue(tag.getInt("EntryFace")) : null;
        float entryU = tag.getFloat("EntryU");
        float entryV = tag.getFloat("EntryV");
        Direction exit = tag.getBoolean("HasExit")
                ? Direction.from3DDataValue(tag.getInt("ExitFace")) : null;
        float exitU = tag.getFloat("ExitU");
        float exitV = tag.getFloat("ExitV");
        long seed = tag.getLong("Seed");
        return new VeinSegment(veinId, entry, entryU, entryV, exit, exitU, exitV, seed);
    }

    public static ListTag saveList(List<VeinSegment> segments) {
        ListTag list = new ListTag();
        for (VeinSegment s : segments) list.add(s.save());
        return list;
    }

    public static List<VeinSegment> loadList(ListTag list) {
        List<VeinSegment> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(load(list.getCompound(i)));
        }
        return result;
    }
}
