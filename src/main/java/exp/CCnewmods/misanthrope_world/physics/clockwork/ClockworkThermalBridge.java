package exp.CCnewmods.misanthrope_world.physics.clockwork;

import exp.CCnewmods.misanthrope_world.physics.DynamicHeatSourceRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClockworkThermalBridge {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/ClockworkBridge");
    private static final int SCAN_INTERVAL = 40;
    private static final boolean CLOCKWORK_LOADED = ModList.get().isLoaded("vs_clockwork");

    @Nullable
    private static Class<?> kNodeClass;
    @Nullable
    private static Method getKelvin;
    @Nullable
    private static Method getDuctNodePos;
    @Nullable
    private static Method getTemperatureAt;
    private static boolean reflected = false;

    private static final Map<ServerLevel, Map<BlockPos, Double>> KNOWN =
            new ConcurrentHashMap<>();

    private ClockworkThermalBridge() {
    }

    // ── Reflection init ───────────────────────────────────────────────────────

    private static boolean ensureReflected() {
        if (reflected) return kNodeClass != null;
        reflected = true;
        if (!CLOCKWORK_LOADED) return false;
        try {
            kNodeClass = Class.forName("org.valkyrienskies.clockwork.util.kelvin.KNodeBlockEntity");
            Class<?> dNetC = Class.forName("org.valkyrienskies.kelvin.api.DuctNetwork");
            Class<?> dPosC = Class.forName("org.valkyrienskies.kelvin.api.DuctNodePos");
            getKelvin = kNodeClass.getMethod("getKelvin", net.minecraft.world.level.Level.class);
            getDuctNodePos = kNodeClass.getMethod("getDuctNodePosition");
            getTemperatureAt = dNetC.getMethod("getTemperatureAt", dPosC);
            LOGGER.info("[Misanthrope Core] Clockwork Kelvin bridge initialised.");
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Misanthrope Core] Clockwork Kelvin bridge failed: {} — Clockwork heat sources will not affect environment temperature.", e.getMessage());
            kNodeClass = null;
            return false;
        }
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!CLOCKWORK_LOADED) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (!ensureReflected()) return;

        long gameTick = level.getGameTime();
        Map<BlockPos, Double> known = KNOWN.computeIfAbsent(level, k -> new ConcurrentHashMap<>());

        // Refresh all known positions every tick
        Set<BlockPos> toRemove = ConcurrentHashMap.newKeySet();
        for (Map.Entry<BlockPos, Double> entry : known.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos)) {
                toRemove.add(pos);
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !kNodeClass.isInstance(be)) {
                toRemove.add(pos);
                continue;
            }
            double kelvin = readKelvinTemp(be, level);
            if (Double.isNaN(kelvin)) {
                toRemove.add(pos);
                continue;
            }
            entry.setValue(kelvin);
            DynamicHeatSourceRegistry.register(level, pos);
        }
        toRemove.forEach(p -> {
            known.remove(p);
            DynamicHeatSourceRegistry.unregister(level, p);
        });

        // Periodic scan for new Clockwork BEs
        if (gameTick % SCAN_INTERVAL != 0) return;

        try {
            for (net.minecraft.world.level.ChunkPos cp :
                    exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField
                            .getLoadedChunks(level)) {
                net.minecraft.world.level.chunk.LevelChunk chunk =
                        level.getChunkSource().getChunkNow(cp.x, cp.z);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be == null || !kNodeClass.isInstance(be)) continue;
                    BlockPos pos = be.getBlockPos();
                    if (known.containsKey(pos)) continue;
                    if (isClockworkRadiatingTerminal(be, level)) {
                        known.put(pos.immutable(), Double.NaN);
                        LOGGER.debug("[ClockworkBridge] Registered new heat source at {}", pos);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[ClockworkBridge] Scan failed: {}", e.getMessage());
        }
    }

    // ── Temperature query ─────────────────────────────────────────────────────

    public static double getClockworkCelsius(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Double> known = KNOWN.get(level);
        if (known == null) return Double.NaN;
        Double kelvin = known.get(pos);
        if (kelvin == null || Double.isNaN(kelvin)) return Double.NaN;
        return kelvin - 273.15;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double readKelvinTemp(BlockEntity be, ServerLevel level) {
        try {
            Object ductNetwork = getKelvin.invoke(be, level);
            if (ductNetwork == null) return Double.NaN;
            Object nodePos = getDuctNodePos.invoke(be);
            if (nodePos == null) return Double.NaN;
            Object result = getTemperatureAt.invoke(ductNetwork, nodePos);
            if (result instanceof Number n) return n.doubleValue();
            return Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static boolean isClockworkRadiatingTerminal(BlockEntity be, ServerLevel level) {
        var data = exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry
                .get(level.getBlockState(be.getBlockPos()));
        if (data.emission == null) return false;
        return Double.isNaN(data.emission.peakCelsius())
                || data.emission.heatProfileType().equals("dynamic_be");
    }
}