package exp.CCnewmods.misanthrope_world.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages runtime registration of block entities that are active heat sources
 * but are not defined by static {@code material_properties/} data alone.
 *
 * <p>This is the surviving half of the old {@code HeatSourceRegistry}. The
 * static data side (JSON loading, block-ID lookups, {@code HeatSourceData})
 * has been removed — that is now handled by {@link BlockPhysicsRegistry} via
 * {@link BlockPhysicsData.HeatEmission}. What remains here is purely the
 * <em>dynamic</em> registration system: block entities (crucibles, bloomeries,
 * Clockwork heaters, etc.) call {@link #register} each tick to announce
 * themselves as active sources. {@code ThermalField} reads the active set and
 * builds or refreshes a {@code ThermalStructure} around each one.
 *
 * <h3>Expiry</h3>
 * Registrations expire after {@link #EXPIRY_TICKS} ticks without a refresh.
 * BEs that stop ticking (chunk unload, power loss) naturally fall out.
 *
 * <h3>Keepalive</h3>
 * Each registered position holds an MGE {@code SectionLoadManager} keepalive
 * so the gas/thermal grid keeps ticking even when no players are nearby.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DynamicHeatSourceRegistry {

    private static final Logger LOGGER =
            LogManager.getLogger("MisanthropeCore/DynamicHeatSource");

    /**
     * Ticks before a registration expires without refresh.
     */
    public static final int EXPIRY_TICKS = 100;

    // Level → (BlockPos → last-registered gametime)
    private static final Map<Level, Map<BlockPos, Long>> ACTIVE =
            new ConcurrentHashMap<>();

    // Level → Set<BlockPos> that have a keepalive registered
    private static final Map<Level, Set<BlockPos>> KEEPALIVE =
            new ConcurrentHashMap<>();

    private DynamicHeatSourceRegistry() {
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Call from a BE's server tick to register it as an active dynamic heat source.
     * Must be called at least once every {@link #EXPIRY_TICKS} ticks to stay alive.
     *
     * <p>Also ensures the MGE {@code EnvironmentSection} at this position is kept
     * loaded via {@code SectionLoadManager}.
     */
    public static void register(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return;
        long now = level.getGameTime();
        ACTIVE.computeIfAbsent(level, k -> new ConcurrentHashMap<>())
                .put(pos.immutable(), now);

        Set<BlockPos> kSet = KEEPALIVE.computeIfAbsent(level, k -> new HashSet<>());
        if (kSet.add(pos.immutable())) {
            exp.CCnewmods.mge.grid.SectionLoadManager.addKeepalive(sl, pos);
        }
    }

    /**
     * Call when a BE is removed or shuts down to immediately deregister it
     * rather than waiting for expiry.
     */
    public static void unregister(Level level, BlockPos pos) {
        Map<BlockPos, Long> map = ACTIVE.get(level);
        if (map != null) map.remove(pos);

        Set<BlockPos> kSet = KEEPALIVE.get(level);
        if (kSet != null && kSet.remove(pos)) {
            if (level instanceof ServerLevel sl) {
                exp.CCnewmods.mge.grid.SectionLoadManager.removeKeepalive(sl, pos);
            }
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Returns all currently active dynamic source positions for a level,
     * pruning expired registrations in the process.
     */
    public static Set<BlockPos> getActive(Level level) {
        Map<BlockPos, Long> map = ACTIVE.get(level);
        if (map == null) return Collections.emptySet();
        long now = level.getGameTime();
        map.entrySet().removeIf(e -> (now - e.getValue()) > EXPIRY_TICKS);
        return Collections.unmodifiableSet(map.keySet());
    }

    /**
     * Returns {@code true} if {@code pos} is currently registered as an active
     * dynamic source in {@code level}.
     */
    public static boolean isActive(Level level, BlockPos pos) {
        Map<BlockPos, Long> map = ACTIVE.get(level);
        if (map == null) return false;
        Long t = map.get(pos);
        if (t == null) return false;
        return (level.getGameTime() - t) <= EXPIRY_TICKS;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ACTIVE.clear();
        KEEPALIVE.clear();
    }
}
