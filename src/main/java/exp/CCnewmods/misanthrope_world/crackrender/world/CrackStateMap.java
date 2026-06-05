package exp.CCnewmods.misanthrope_world.crackrender.world;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
 */
public class CrackStateMap extends SavedData {

    private static final String DATA_NAME = "misanthrope_crack_state";

    /**
     * Master map: BlockPos (as long) → CrackEntry.
     */
    private final Map<Long, CrackEntry> entries = new HashMap<>();

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
     */
    public void remove(BlockPos pos) {
        if (entries.remove(pos.asLong()) != null) setDirty();
    }

    /**
     * Remove all entries — called on dimension wipe or debug reset.
     */
    public void clear() {
        entries.clear();
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

    // ── NBT persistence ───────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (CrackEntry entry : entries.values()) {
            if (entry.hasCracks()) list.add(entry.save());
        }
        tag.put("Entries", list);
        return tag;
    }

    public static CrackStateMap load(CompoundTag tag) {
        CrackStateMap map = new CrackStateMap();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CrackEntry entry = CrackEntry.load(list.getCompound(i));
            map.entries.put(entry.pos().asLong(), entry);
        }
        return map;
    }
}
