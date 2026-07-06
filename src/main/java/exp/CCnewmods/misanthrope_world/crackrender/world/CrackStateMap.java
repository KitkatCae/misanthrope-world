package exp.CCnewmods.misanthrope_world.crackrender.world;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WorldSavedData holding all CrackEntries for a single ServerLevel.
 * <p>
 * ── Scope ─────────────────────────────────────────────────────────────────────
 * One CrackStateMap per dimension (ServerLevel), stored under the level's
 * data directory as "misanthrope_crack_state.dat". Loaded lazily on first
 * crack query or registration event.
 * <p>
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * All mutation methods must be called on the server thread.
 * The snapshot() method returns an immutable copy used by the client sync
 * packet builder — safe to call from any thread after snapshot.
 * <p>
 * ── Dirty tracking ────────────────────────────────────────────────────────────
 * setDirty() is called on every mutation so Forge auto-saves on world save.
 * <p>
 * ── Vein path registry ────────────────────────────────────────────────────────
 * {@code veinPaths} indexes veinId → the ordered list of BlockPos that vein's
 * generator walked through, at generation time. Before this, a vein's full
 * path was only discoverable by scanning every CrackEntry in the level for a
 * matching {@code VeinSegment.veinId} — workable for the propagator's own
 * decorative-growth bookkeeping, useless for anything that needs to ask
 * "what does vein N actually look like" (fracture-boundary queries, debug
 * tools, future gameplay reading crack geometry). Populated by
 * {@link VeinPropagator#generateVein} and
 * {@link VeinPropagator#generateImpactBurst} — both call
 * {@link #registerVeinPath} once they've finished walking a vein.
 */
public class CrackStateMap extends SavedData {

    private static final String DATA_NAME = "misanthrope_crack_state";

    /**
     * Master map: BlockPos (as long) → CrackEntry.
     */
    private final Map<Long, CrackEntry> entries = new HashMap<>();

    /**
     * veinId → ordered block path. See class doc.
     */
    private final Map<Integer, List<BlockPos>> veinPaths = new HashMap<>();

    public CrackStateMap() {
    }

    // ── Static access ─────────────────────────────────────────────────────────

    public static CrackStateMap get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                CrackStateMap::load,
                CrackStateMap::new,
                DATA_NAME
        );
    }

    // ── Mutation (server thread only) ─────────────────────────────────────────

    /**
     * Store or update a crack entry. Creates a new entry if none exists.
     * Marks dirty.
     */
    public void put(CrackEntry entry) {
        entries.put(entry.pos().asLong(), entry);
        setDirty();
    }

    /**
     * Remove the crack entry for a position (called on block removal or
     * full heal to level 0). Marks dirty.
     * <p>
     * Note: this deliberately does NOT prune {@code veinPaths} entries that
     * reference the removed position. A vein's recorded path is a historical
     * record of geometry that was generated — a healed-away block doesn't
     * un-happen the vein that once passed through it. Callers doing
     * boundary/geometry queries against an old veinId should already be
     * tolerant of paths containing positions that no longer have live
     * CrackEntries (see {@link exp.CCnewmods.misanthrope_world.physics.structural.crater.FractureBoundary}).
     */
    public void remove(BlockPos pos) {
        if (entries.remove(pos.asLong()) != null) setDirty();
    }

    /**
     * Remove all entries and vein paths — called on dimension wipe or debug reset.
     */
    public void clear() {
        entries.clear();
        veinPaths.clear();
        setDirty();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Nullable
    public CrackEntry get(BlockPos pos) {
        return entries.get(pos.asLong());
    }

    public boolean hasCracks(BlockPos pos) {
        CrackEntry e = entries.get(pos.asLong());
        return e != null && e.hasCracks();
    }

    public Collection<CrackEntry> allEntries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public int size() {
        return entries.size();
    }

    /**
     * Returns an immutable snapshot map (Long pos → CrackEntry) suitable
     * for passing to the network packet builder on any thread.
     * <p>
     * This is a shallow copy — CrackEntry fields are read-only after
     * construction so sharing the references is safe.
     */
    public Map<Long, CrackEntry> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(entries));
    }

    /**
     * All CrackEntries whose block-center point falls within {@code box}.
     * <p>
     * This is the same containment test {@code CrackPropagator.collectCandidates}
     * already did privately for its own tick-driven candidate scan — pulled
     * out here as a public, reusable spatial query so other callers (the
     * fracture-boundary generator, debug commands, whatever comes next)
     * don't need to duplicate it or reach into the propagator's internals.
     * {@code CrackPropagator} itself is unchanged and keeps its own inline
     * scan for now; nothing about this extraction alters its behaviour.
     * <p>
     * O(n) over all entries in the level — fine at current crack-entry
     * counts (hundreds to low thousands), same cost class the propagator
     * already pays every 20 ticks. Revisit with a spatial index only if
     * profiling actually shows this hot.
     */
    public List<CrackEntry> entriesInBox(AABB box) {
        List<CrackEntry> result = new ArrayList<>();
        for (CrackEntry entry : entries.values()) {
            BlockPos p = entry.pos();
            if (box.contains(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)) {
                result.add(entry);
            }
        }
        return result;
    }

    // ── Vein path registry ───────────────────────────────────────────────────

    /**
     * Records the full ordered block path a newly-generated vein walked
     * through. Called once per vein by {@link VeinPropagator} at the end of
     * generation — the path itself doesn't change after that (veins are
     * generated once and never re-walked), so this is a single put, not an
     * incremental accumulation.
     */
    public void registerVeinPath(int veinId, List<BlockPos> path) {
        veinPaths.put(veinId, List.copyOf(path));
        setDirty();
    }

    /**
     * The ordered block path for a given vein, or an empty list if unknown
     * (e.g. veinId from before this registry existed, on a world saved by
     * an older build — segments still work fine via the old per-block scan,
     * this registry just won't have backfilled history for them).
     */
    public List<BlockPos> getVeinPath(int veinId) {
        return veinPaths.getOrDefault(veinId, List.of());
    }

    // ── NBT persistence ───────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (CrackEntry entry : entries.values()) {
            if (entry.hasCracks()) list.add(entry.save());
        }
        tag.put("Entries", list);

        ListTag veinList = new ListTag();
        for (Map.Entry<Integer, List<BlockPos>> e : veinPaths.entrySet()) {
            CompoundTag veinTag = new CompoundTag();
            veinTag.putInt("VeinId", e.getKey());
            long[] path = new long[e.getValue().size()];
            int idx = 0;
            for (BlockPos p : e.getValue()) path[idx++] = p.asLong();
            veinTag.putLongArray("Path", path);
            veinList.add(veinTag);
        }
        tag.put("VeinPaths", veinList);

        return tag;
    }

    public static CrackStateMap load(CompoundTag tag) {
        CrackStateMap map = new CrackStateMap();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CrackEntry entry = CrackEntry.load(list.getCompound(i));
            map.entries.put(entry.pos().asLong(), entry);
        }

        if (tag.contains("VeinPaths", Tag.TAG_LIST)) {
            ListTag veinList = tag.getList("VeinPaths", Tag.TAG_COMPOUND);
            for (int i = 0; i < veinList.size(); i++) {
                CompoundTag veinTag = veinList.getCompound(i);
                int veinId = veinTag.getInt("VeinId");
                long[] rawPath = veinTag.getLongArray("Path");
                List<BlockPos> path = new ArrayList<>(rawPath.length);
                for (long posLong : rawPath) {
                    path.add(BlockPos.of(posLong));
                }
                map.veinPaths.put(veinId, path);
            }
        }

        return map;
    }
}