package exp.CCnewmods.misanthrope_world.physics.structural.grid;

import exp.CCnewmods.misanthrope_world.pos.WorldPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The persistent structural stress grid. See {@code StressGrid_Design_v1.md}
 * for the full design — this is Stage 1: the data structure and section
 * lifecycle only. Nothing writes simulation results into it yet
 * (Stage 2/3), and nothing reads it for gameplay decisions yet either —
 * {@code StructuralStressField} keeps using its existing tick-scoped memo
 * cache until the compressive channel (Stage 2) actually replaces it.
 * <p>
 * ── Addressing ────────────────────────────────────────────────────────────────
 * Sections are stored in a plain {@code Map<SectionKey, StressSection>} per
 * level, NOT a Forge {@code Capability} on {@code LevelChunk} the way
 * {@code EnvironmentSection} is for MGE's gas grid. Primary accessors take
 * {@link WorldPos} (MWorld's own internal position type); {@code BlockPos}
 * overloads exist purely as the vanilla compatibility boundary. Confirmed
 * against the real Misanthrope Planetary Exploration source:
 * {@code PlanetDimensions.blockToChunk()} does the identical floorDiv-by-16
 * math this grid assumes, and MPM still runs inside a real {@code ServerLevel}
 * (it replaces coordinate math within a dimension, not the dimension object),
 * so level-keying the top-level map stays correct there too.
 * <p>
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 * Two independent eviction paths, deliberately redundant with each other:
 * <p>
 * 1. {@link #onChunkUnload} — vanilla {@code ChunkEvent.Unload}, evicts every
 *    Y section in an unloaded chunk's column immediately.
 * <p>
 * 2. {@link #sweepIdleSections} — a throttled backstop that evicts sections
 *    untouched for {@link #IDLE_EVICTION_TICKS}, independent of any unload
 *    event firing at all. MPM's own chunk load/unload event is planned but
 *    not yet in its source as of this check — this sweep covers the gap
 *    until then, and stays afterward as a general safety net regardless.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StressGrid {

    /** How long (in ticks) a section can go untouched before the idle sweep evicts it. 10 minutes. */
    private static final long IDLE_EVICTION_TICKS = 12_000L;

    /** How often (in ticks) the idle sweep actually runs — it's a backstop, not a hot path. 5 minutes. */
    private static final long SWEEP_INTERVAL_TICKS = 6_000L;

    private static final Map<ServerLevel, Map<SectionKey, StressSection>> SECTIONS =
            new ConcurrentHashMap<>();

    private static final Map<ServerLevel, Long> LAST_SWEPT_GAME_TIME = new ConcurrentHashMap<>();

    private StressGrid() {
    }

    // ── Primary accessors (WorldPos) ─────────────────────────────────────────

    /** Returns the section for this position, or null if it's never been written to. */
    @Nullable
    public static StressSection getSection(ServerLevel level, WorldPos pos) {
        return getSectionByKey(level, SectionKey.fromWorldPos(pos));
    }

    /** Returns the section for this position, allocating an empty one if needed. */
    public static StressSection getOrCreateSection(ServerLevel level, WorldPos pos) {
        return getOrCreateSectionByKey(level, SectionKey.fromWorldPos(pos));
    }

    // ── Vanilla compatibility-boundary overloads ────────────────────────────

    /** Vanilla convenience — equivalent to {@code getSection(level, WorldPos.fromBlockPos(pos))}. */
    @Nullable
    public static StressSection getSection(ServerLevel level, BlockPos pos) {
        return getSectionByKey(level, SectionKey.fromBlockPos(pos));
    }

    /** Vanilla convenience — equivalent to {@code getOrCreateSection(level, WorldPos.fromBlockPos(pos))}. */
    public static StressSection getOrCreateSection(ServerLevel level, BlockPos pos) {
        return getOrCreateSectionByKey(level, SectionKey.fromBlockPos(pos));
    }

    // ── Shared key-based accessors ───────────────────────────────────────────

    @Nullable
    private static StressSection getSectionByKey(ServerLevel level, SectionKey key) {
        Map<SectionKey, StressSection> levelSections = SECTIONS.get(level);
        if (levelSections == null) return null;
        StressSection section = levelSections.get(key);
        if (section != null) section.lastTouchedGameTime = level.getGameTime();
        return section;
    }

    private static StressSection getOrCreateSectionByKey(ServerLevel level, SectionKey key) {
        Map<SectionKey, StressSection> levelSections =
                SECTIONS.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
        StressSection section = levelSections.computeIfAbsent(key, k -> new StressSection());
        section.lastTouchedGameTime = level.getGameTime();
        return section;
    }

    /** Total live sections currently held for a level — for perf logging / debug overlay stats. */
    public static int sectionCount(ServerLevel level) {
        Map<SectionKey, StressSection> levelSections = SECTIONS.get(level);
        return levelSections == null ? 0 : levelSections.size();
    }

    // ── Lifecycle: vanilla unload path ──────────────────────────────────────

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Map<SectionKey, StressSection> levelSections = SECTIONS.get(level);
        if (levelSections == null) return;

        ChunkPos cp = event.getChunk().getPos();
        levelSections.entrySet().removeIf(entry -> {
            SectionKey key = entry.getKey();
            return key.sectionX() == cp.x && key.sectionZ() == cp.z;
        });
    }

    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SECTIONS.remove(level);
            LAST_SWEPT_GAME_TIME.remove(level);
        }
    }

    // ── Lifecycle: idle-sweep backstop ──────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        long lastSwept = LAST_SWEPT_GAME_TIME.getOrDefault(level, 0L);
        if (now - lastSwept < SWEEP_INTERVAL_TICKS) return;
        LAST_SWEPT_GAME_TIME.put(level, now);

        sweepIdleSections(level, now);
    }

    /** Evicts sections untouched for longer than {@link #IDLE_EVICTION_TICKS}. See class doc. */
    private static void sweepIdleSections(ServerLevel level, long now) {
        Map<SectionKey, StressSection> levelSections = SECTIONS.get(level);
        if (levelSections == null || levelSections.isEmpty()) return;

        levelSections.entrySet().removeIf(entry ->
                now - entry.getValue().lastTouchedGameTime > IDLE_EVICTION_TICKS);
    }
}
