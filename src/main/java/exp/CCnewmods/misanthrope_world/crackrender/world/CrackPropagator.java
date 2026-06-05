package exp.CCnewmods.misanthrope_world.crackrender.world;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import exp.CCnewmods.misanthrope_world.crackrender.network.CrackSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Server-side tick driver for the global crack system.
 * <p>
 * ── Tick model ────────────────────────────────────────────────────────────────
 * Runs every PROPAGATION_INTERVAL server ticks (20 ticks = 1 second by default).
 * On each propagation tick:
 * <p>
 * 1. Poll all registered ICrackSourceProviders.
 * 2. Remove expired providers.
 * 3. For each active provider, collect candidate blocks in its zone via a
 * lightweight AABB scan of existing CrackEntries plus adjacent air blocks
 * (new crack origins come from the zone boundary, not existing entries).
 * 4. Use weighted random selection to pick blocks to advance.
 * 5. On first crack advance for a block, call VeinPropagator.generateVein.
 * 6. Tick healing for all entries not driven this cycle.
 * 7. Sync changed entries to clients via CrackSyncPacket.
 * <p>
 * ── Source registration ───────────────────────────────────────────────────────
 * addSource(provider) — thread-safe, can be called from server setup or BE tick.
 * removeSource(sourceId) — removes by stable ID.
 * clearSources() — debug / world unload.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CrackPropagator {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/CrackPropagator");

    public static final int PROPAGATION_INTERVAL = 20; // ticks
    private static final int MAX_ADVANCES_PER_PROVIDER = 5;

    /**
     * Growth multipliers per crack level (same model as original WallCrackSystem).
     */
    private static final float[] GROWTH_FACTORS = {1.0f, 4.0f, 12.0f, 30.0f, 0f};

    /**
     * All registered providers. Mutations synchronized on PROVIDERS.
     */
    private static final List<ICrackSourceProvider> PROVIDERS = new ArrayList<>();

    /**
     * Blocks changed this propagation tick, collected for sync.
     */
    private static final Set<BlockPos> DIRTY_POSITIONS = new LinkedHashSet<>();

    // ── Source registration ───────────────────────────────────────────────────

    public static synchronized void addSource(ICrackSourceProvider provider) {
        // Deduplicate by sourceId if provided
        String id = provider.sourceId();
        if (id != null) {
            PROVIDERS.removeIf(p -> id.equals(p.sourceId()));
        }
        PROVIDERS.add(provider);
    }

    public static synchronized void removeSource(String sourceId) {
        PROVIDERS.removeIf(p -> sourceId.equals(p.sourceId()));
    }

    public static synchronized void clearSources() {
        PROVIDERS.clear();
    }

    /** Returns the number of currently registered crack source providers. */
    public static synchronized int sourceCount() {
        return PROVIDERS.size();
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % PROPAGATION_INTERVAL != 0) return;

        CrackStateMap stateMap = CrackStateMap.get(level);
        DIRTY_POSITIONS.clear();

        List<ICrackSourceProvider> snapshot;
        synchronized (PROVIDERS) {
            snapshot = new ArrayList<>(PROVIDERS);
        }

        List<ICrackSourceProvider> toRemove = new ArrayList<>();

        for (ICrackSourceProvider provider : snapshot) {
            if (provider.isExpired()) {
                toRemove.add(provider);
                continue;
            }

            float pressure = provider.getCrackPressure(level);
            if (pressure <= 0) continue;

            int advances = Math.min(MAX_ADVANCES_PER_PROVIDER, Math.max(1, (int) (pressure / 10)));
            AABB zone = provider.getZone();
            CrackCause cause = provider.getCause();

            // Collect candidate blocks in zone
            List<BlockPos> candidates = collectCandidates(level, stateMap, zone);
            if (candidates.isEmpty()) continue;

            // Weighted random selection
            Random rng = level.getRandom() instanceof Random r ? r : new Random(level.getSeed());
            for (int i = 0; i < advances; i++) {
                BlockPos target = selectTarget(candidates, stateMap, pressure, rng);
                if (target == null) continue;
                advanceCrack(level, stateMap, target, cause, pressure, rng);
            }
        }

        // Heal entries not driven this cycle
        tickHealing(level, stateMap);

        // Remove expired providers
        synchronized (PROVIDERS) {
            PROVIDERS.removeAll(toRemove);
        }

        // Sync dirty positions to clients
        if (!DIRTY_POSITIONS.isEmpty()) {
            CrackSyncPacket.sendChanges(level, stateMap, DIRTY_POSITIONS);
            DIRTY_POSITIONS.clear();
        }
    }

    // ── Candidate collection ──────────────────────────────────────────────────

    /**
     * Collects blocks in the zone that are candidates for cracking:
     * - All existing cracked entries in the zone (they can propagate further)
     * - Solid, non-cracked blocks adjacent to the zone boundary (new origins)
     * <p>
     * Capped at 256 candidates for performance.
     */
    private static List<BlockPos> collectCandidates(ServerLevel level,
                                                    CrackStateMap stateMap,
                                                    AABB zone) {
        List<BlockPos> result = new ArrayList<>();

        // Existing cracked blocks in zone
        for (CrackEntry entry : stateMap.allEntries()) {
            if (entry.isCollapsed()) continue;
            BlockPos p = entry.pos();
            if (zone.contains(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)) {
                result.add(p);
            }
        }

        // New origin candidates: solid blocks on zone boundary not yet cracked
        int minX = (int) Math.floor(zone.minX);
        int minY = (int) Math.floor(zone.minY);
        int minZ = (int) Math.floor(zone.minZ);
        int maxX = (int) Math.ceil(zone.maxX);
        int maxY = (int) Math.ceil(zone.maxY);
        int maxZ = (int) Math.ceil(zone.maxZ);

        // Sample boundary — 1 in 4 to keep cost bounded
        int sampled = 0;
        outer:
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean onBoundary = (x == minX || x == maxX || y == minY
                            || y == maxY || z == minZ || z == maxZ);
                    if (!onBoundary) continue;
                    if ((x ^ y ^ z) % 4 != 0) continue; // 1-in-4 sample

                    BlockPos p = new BlockPos(x, y, z);
                    if (stateMap.hasCracks(p)) continue; // already tracked
                    if (level.getBlockState(p).isSolid()) {
                        result.add(p);
                        sampled++;
                        if (sampled > 32) break outer; // cap new origins per tick
                    }
                }
            }
        }

        if (result.size() > 256) {
            Collections.shuffle(result, new Random(level.getGameTime()));
            return result.subList(0, 256);
        }
        return result;
    }

    // ── Weighted block selection ──────────────────────────────────────────────

    /**
     * Select one block from candidates using weighted random.
     * Higher weight = more likely to be selected.
     * <p>
     * Weight factors:
     * - existingCrackLevel: higher level → much higher weight (same growth factors)
     * - pressure: raw pressure as weak multiplier
     * - structural position: ceiling blocks weighted higher
     */
    private static BlockPos selectTarget(List<BlockPos> candidates,
                                         CrackStateMap stateMap,
                                         float pressure,
                                         Random rng) {
        float[] weights = new float[candidates.size()];
        float total = 0;

        for (int i = 0; i < candidates.size(); i++) {
            BlockPos pos = candidates.get(i);
            CrackEntry entry = stateMap.get(pos);
            int level = entry != null ? entry.level() : 0;
            if (level >= CrackEntry.LEVEL_COLLAPSED) {
                weights[i] = 0;
                continue;
            }

            float growth = GROWTH_FACTORS[Math.min(3, level)];
            float pressureFactor = 1f + pressure * 0.02f;
            weights[i] = growth * pressureFactor;
            total += weights[i];
        }

        if (total <= 0) return null;

        float roll = rng.nextFloat() * total;
        float cumulative = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights[i];
            if (roll <= cumulative) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    // ── Crack advance ─────────────────────────────────────────────────────────

    private static void advanceCrack(ServerLevel level,
                                     CrackStateMap stateMap,
                                     BlockPos pos,
                                     CrackCause cause,
                                     float pressure,
                                     Random rng) {
        CrackEntry entry = stateMap.get(pos);
        boolean isNew = entry == null;

        if (isNew) {
            // Only crack solid blocks
            if (!level.getBlockState(pos).isSolid()) return;
            entry = new CrackEntry(pos, cause, CrackEntry.LEVEL_PRISTINE);
        }

        boolean wasEmpty = !entry.hasCracks();
        int newLevel = entry.advance(level.getGameTime());
        stateMap.put(entry);
        DIRTY_POSITIONS.add(pos);

        // Generate a vein on first crack
        if (wasEmpty) {
            try {
                VeinPropagator.generateVein(stateMap, pos, cause,
                        new Random(rng.nextLong()));
            } catch (Exception e) {
                LOGGER.warn("[CrackPropagator] Vein generation failed at {}: {}", pos, e.getMessage());
            }
        }

        // Handle collapse
        if (newLevel >= CrackEntry.LEVEL_COLLAPSED) {
            handleCollapse(level, stateMap, pos, entry);
        }
    }

    // ── Collapse ──────────────────────────────────────────────────────────────

    private static void handleCollapse(ServerLevel level,
                                       CrackStateMap stateMap,
                                       BlockPos pos,
                                       CrackEntry entry) {
        // Attempt MineCollapse integration
        boolean used = false;
        if (net.minecraftforge.fml.ModList.get().isLoaded("minecollapse")) {
            try {
                used = net.zerodind.minecollapse.recipes.CollapseRecipe
                        .collapseBlock(level, pos, level.getBlockState(pos), false);
            } catch (Exception ignored) {
            }
        }
        if (!used) {
            level.destroyBlock(pos, true);
        }

        // Remove entry after collapse is processed
        stateMap.remove(pos);
        DIRTY_POSITIONS.add(pos);
    }

    // ── Healing ───────────────────────────────────────────────────────────────

    private static void tickHealing(ServerLevel level, CrackStateMap stateMap) {
        long gameTick = level.getGameTime();
        List<BlockPos> toRemove = new ArrayList<>();

        for (CrackEntry entry : stateMap.allEntries()) {
            // Skip if driven this cycle
            if (gameTick - entry.lastDrivenTick() <= PROPAGATION_INTERVAL) continue;

            boolean changed = entry.tickHeal();
            if (changed) {
                DIRTY_POSITIONS.add(entry.pos());
                stateMap.put(entry); // mark dirty
                if (!entry.hasCracks()) {
                    toRemove.add(entry.pos());
                }
            }
        }

        for (BlockPos p : toRemove) {
            stateMap.remove(p);
            DIRTY_POSITIONS.add(p);
        }
    }
}
