package exp.CCnewmods.misanthrope_world.physics.persist;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * WorldSavedData holding per-block pH corrosion accumulation for a single
 * {@link ServerLevel}.
 *
 * <p>Mirrors {@code CrackStateMap}'s shape exactly — one tracker per
 * dimension, stored under the level's data directory as
 * {@code "misanthrope_corrosion_state.dat"}.
 *
 * <h3>Why this needs to persist</h3>
 * Corrosion accumulation ({@code BlockPhysicsData.PhReactivity}) is a
 * one-way ratchet by design — sustained acid/base exposure should leave a
 * lasting mark on a block's structural integrity, the same way an existing
 * crack only grows or fails the block outright rather than healing on its
 * own. If accumulation lived only in a {@code ConcurrentHashMap} like
 * {@code ThermalField}'s old {@code LevelState}, a server restart would
 * silently erase years of accumulated corrosion — the block would read as
 * pristine even though it had been sitting in a sulfur-dioxide atmosphere
 * for the entire previous session.
 *
 * <h3>Value range</h3>
 * Each stored float is the corrosion accumulation fraction [0, 1] used by
 * {@code BlockPhysicsData.PhReactivity#strengthFractionAt}. 0 = pristine,
 * 1 = fully corroded (strength floored at {@code minStrengthFraction}).
 * Entries at exactly 0 are pruned on save — pristine blocks don't need an
 * entry at all, keeping the saved data small for the overwhelming majority
 * of blocks in a world that never see a reactive atmosphere.
 *
 * <h3>Thread safety</h3>
 * All mutation methods must be called on the server thread, same contract
 * as {@code CrackStateMap}. {@link #setDirty()} is called on every mutation
 * so Forge auto-saves on world save.
 */
public class CorrosionStateMap extends SavedData {

    private static final String DATA_NAME = "misanthrope_corrosion_state";

    /**
     * Master map: BlockPos (as long) → corrosion accumulation fraction [0, 1].
     */
    private final Map<Long, Float> entries = new HashMap<>();

    public CorrosionStateMap() {
    }

    // ── Static access ─────────────────────────────────────────────────────────

    public static CorrosionStateMap get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                CorrosionStateMap::load,
                CorrosionStateMap::new,
                DATA_NAME
        );
    }

    // ── Mutation (server thread only) ─────────────────────────────────────────

    /**
     * Sets the corrosion accumulation fraction for a position, clamped to
     * [0, 1]. Marks dirty. A value of exactly 0 removes the entry entirely
     * (equivalent to {@link #remove}) rather than storing a zero — keeps the
     * map free of no-op entries from blocks that briefly touched a reactive
     * atmosphere and then self-repaired back to pristine.
     */
    public void set(BlockPos pos, float accumulation) {
        float clamped = Math.max(0f, Math.min(1f, accumulation));
        if (clamped <= 0f) {
            remove(pos);
            return;
        }
        entries.put(pos.asLong(), clamped);
        setDirty();
    }

    /**
     * Remove the corrosion entry for a position (block broken, or fully
     * self-repaired back to pristine). Marks dirty only if something was
     * actually removed.
     */
    public void remove(BlockPos pos) {
        if (entries.remove(pos.asLong()) != null) setDirty();
    }

    /**
     * Remove all entries — called on dimension wipe or debug reset.
     */
    public void clear() {
        if (entries.isEmpty()) return;
        entries.clear();
        setDirty();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Returns the corrosion accumulation fraction at a position, or 0.0
     * (pristine) if no entry exists. Never returns a negative value or a
     * value above 1.0.
     */
    public float get(BlockPos pos) {
        Float v = entries.get(pos.asLong());
        return v != null ? v : 0f;
    }

    public boolean isCorroded(BlockPos pos) {
        return entries.containsKey(pos.asLong());
    }

    public int size() {
        return entries.size();
    }

    // ── NBT persistence ───────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Float> e : entries.entrySet()) {
            if (e.getValue() <= 0f) continue; // shouldn't happen given set()'s guard, but be safe
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Pos", e.getKey());
            entryTag.putFloat("Accum", e.getValue());
            list.add(entryTag);
        }
        tag.put("Entries", list);
        return tag;
    }

    public static CorrosionStateMap load(CompoundTag tag) {
        CorrosionStateMap map = new CorrosionStateMap();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            float accum = entryTag.getFloat("Accum");
            if (accum <= 0f) continue;
            map.entries.put(entryTag.getLong("Pos"), accum);
        }
        return map;
    }
}
