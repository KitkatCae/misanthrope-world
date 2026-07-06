package exp.CCnewmods.misanthrope_world.physics.persist;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * WorldSavedData holding per-source-position interior temperature for
 * {@code ThermalField}'s active simulation zones, for a single
 * {@link ServerLevel}.
 *
 * <h3>Why this needs to persist, and why so little of it does</h3>
 * {@code ThermalField.LevelState} keeps a {@code SimZone} per active heat
 * source, but the only piece of state in a {@code SimZone} that isn't cheaply
 * rebuildable from world state is {@code ThermalSimulation#interiorTemp} —
 * the actual accumulated simulation result. Everything else
 * ({@code ThermalStructure}, {@code spatialWeights}) is a geometric scan
 * derived from the blocks physically present, already rebuilt automatically
 * by {@code LevelState.rebuildZone} whenever a zone is missing or invalidated.
 *
 * <p>Before this tracker existed, {@code interiorTemp} lived only in the
 * in-memory {@code LevelState} map and reset to {@code NaN} (re-initialised
 * from ambient) on every server restart — a kiln that had been slowly
 * heating up over hours of real play would instantly cool back to ambient
 * the moment the server restarted, with no in-world cause. This tracker
 * closes that gap: {@code ThermalSimulation} already exposes
 * {@link exp.CCnewmods.misanthrope_world.physics.simulation.ThermalSimulation#setInteriorTemp}
 * specifically for this purpose (see its own doc comment, "e.g. on load from
 * NBT").
 *
 * <h3>Lifecycle</h3>
 * On {@code LevelState.rebuildZone}, after constructing a fresh
 * {@code ThermalSimulation}, the caller should check this tracker for a
 * saved temperature at the source position and call
 * {@code setInteriorTemp} if one exists — but only when there is no
 * already-running zone to inherit from (the existing
 * {@code existing.simulation.getInteriorTemp()} in-memory path still takes
 * priority for a live rescan; this tracker exists specifically to cover the
 * cold-start case after a restart). On every {@code SIM_INTERVAL} tick, the
 * caller writes the zone's current interior temperature back here. When a
 * zone is removed (heat source gone), the entry is removed too — there is no
 * meaningful "saved temperature" for a kiln that no longer exists.
 *
 * <h3>Thread safety</h3>
 * Same contract as {@code CrackStateMap}/{@code CorrosionStateMap} — mutate
 * on the server thread only; {@link #setDirty()} drives Forge's auto-save.
 */
public class ThermalZoneStateMap extends SavedData {

    private static final String DATA_NAME = "misanthrope_thermal_zone_state";

    /**
     * Master map: heat-source BlockPos (as long) → interior temperature °C.
     */
    private final Map<Long, Double> entries = new HashMap<>();

    public ThermalZoneStateMap() {
    }

    // ── Static access ─────────────────────────────────────────────────────────

    public static ThermalZoneStateMap get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                ThermalZoneStateMap::load,
                ThermalZoneStateMap::new,
                DATA_NAME
        );
    }

    // ── Mutation (server thread only) ─────────────────────────────────────────

    /**
     * Records the current interior temperature for a zone's source position.
     * Called every {@code SIM_INTERVAL} ticks from {@code LevelState.tick()}.
     * Marks dirty unless the value is unchanged from what's already stored
     * (avoids a dirty-flag storm from kilns sitting at steady-state).
     */
    public void set(BlockPos sourcePos, double interiorTemp) {
        if (Double.isNaN(interiorTemp)) return;
        Long key = sourcePos.asLong();
        Double existing = entries.get(key);
        if (existing != null && Math.abs(existing - interiorTemp) < 0.01) return;
        entries.put(key, interiorTemp);
        setDirty();
    }

    /**
     * Removes the saved temperature for a zone's source position (zone
     * removed — heat source gone or deactivated). Marks dirty only if
     * something was actually removed.
     */
    public void remove(BlockPos sourcePos) {
        if (entries.remove(sourcePos.asLong()) != null) setDirty();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Returns the saved interior temperature for a source position, or
     * {@code Double.NaN} if none is stored (cold start — caller should fall
     * back to its normal ambient-initialisation behaviour).
     */
    public double get(BlockPos sourcePos) {
        Double v = entries.get(sourcePos.asLong());
        return v != null ? v : Double.NaN;
    }

    public int size() {
        return entries.size();
    }

    // ── NBT persistence ───────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Double> e : entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Pos", e.getKey());
            entryTag.putDouble("Temp", e.getValue());
            list.add(entryTag);
        }
        tag.put("Entries", list);
        return tag;
    }

    public static ThermalZoneStateMap load(CompoundTag tag) {
        ThermalZoneStateMap map = new ThermalZoneStateMap();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            double temp = entryTag.getDouble("Temp");
            if (Double.isNaN(temp)) continue;
            map.entries.put(entryTag.getLong("Pos"), temp);
        }
        return map;
    }
}
