package exp.CCnewmods.misanthrope_world.physics.structural.vs2;

import exp.CCnewmods.misanthrope_world.physics.structural.ImpactCrackSource;
import exp.CCnewmods.misanthrope_world.physics.structural.StructuralStressField;
import exp.CCnewmods.misanthrope_world.crackrender.world.CrackPropagator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.events.CollisionEvent;
import org.valkyrienskies.core.api.physics.ContactPoint;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MWorld's own terrain-fracture-on-impact system, replacing reliance on VS2's
 * generic {@code ImpactFractureHandler} (2.4.13-only, and even where present,
 * a fixed-radius block-delete with no knowledge of this project's crack or
 * structural-failure systems).
 *
 * <h3>Trigger — VS2's public event API, no reflection</h3>
 * {@code ValkyrienSkiesMod.INSTANCE.getApi()} exposes
 * {@code VsCoreApi.getCollisionStartEvent()} (a {@code ListenableEvent}) which
 * takes a plain {@code Consumer<CollisionEvent>} via {@code .on(...)}. Each
 * {@link CollisionEvent} carries {@code shipIdA}/{@code shipIdB} and a
 * collection of {@link ContactPoint}s (position, normal, velocity, separation).
 * This is the same event {@code ImpactFractureHandler} itself listens to in
 * the 2.4.13 build — confirmed by decompiling {@code ValkyrienSkiesMod}'s own
 * registration lambdas — so it's present in both VS2 versions even though the
 * 2.5.0 build we're on doesn't ship the handler that used to consume it.
 *
 * <p>Physics events fire off the game thread, so we only ever queue work here
 * and drain it on the next server tick, same pattern as
 * {@code ImpactFractureHandler}'s own deferred {@code PendingImpact} + tick().
 *
 * <h3>Scope: ship-vs-ship only</h3>
 * {@link ImpactHandler} (ported from MVSE) is now the sole handler for
 * ship-vs-terrain impacts — it does its own forward-raycast detection, crater/
 * embed/hypersonic-disassemble resolution, temporary-crater reversion, and
 * (via {@code registerCrackShell}) the same crack-system integration this
 * class was originally built to add. Leaving this class also reacting to
 * ship-vs-terrain contact points via {@code CollisionEvent} would double-process
 * the same impact through two independent systems. So this class now only
 * fires when BOTH sides of the collision resolve to actual loaded ships —
 * ship-vs-ship collisions, which {@link ImpactHandler} doesn't cover at all.
 *
 * <h3>What happens on impact (ship-vs-ship only)</h3>
 * <ol>
 *   <li>For each contact point, compute closing speed = the component of
 *       {@code ContactPoint.getVelocity()} along {@code getNormal()}.</li>
 *   <li>Look up the impacted block's structural data via {@code MaterialProfile}.
 *       If a block has none authored, the effect is skipped entirely for that
 *       impact (logged once per block type) rather than fabricated from a
 *       default — see {@code MaterialProfile}'s class doc.</li>
 *   <li>Compute an impact-energy proxy and derive:
 *       <ul>
 *         <li>a small instant-CRUMBLE core radius (true crater) scaled by
 *             energy and inversely by {@code compressiveStrengthKpa}</li>
 *         <li>a crack pressure for the surrounding shell scaled by energy and
 *             inversely by {@code fractureToughness} — brittle material
 *             (low toughness) cracks harder for the same hit</li>
 *       </ul>
 *   </li>
 *   <li>Register an {@link ImpactCrackSource} for the shell (feeds
 *       {@link CrackPropagator}) and {@link StructuralStressField#markDirty}
 *       around the impact so anything left over-stressed by the crater
 *       cascades through the existing failure pipeline on its own.</li>
 * </ol>
 *
 * <h3>Tuning</h3>
 * The constants below are starting points, not final balance — tune against
 * actual play. {@code ENERGY_SCALE}, {@code CORE_RADIUS_SCALE}, and
 * {@code CRACK_PRESSURE_SCALE} are the three knobs most worth adjusting first.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ImpactFractureBridge {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/ImpactFractureBridge");

    /** Below this closing speed (blocks/tick), an impact is ignored entirely. */
    public static final double MIN_IMPACT_SPEED = 0.5;

    /** Energy proxy = 0.5 * speed^2 (no mass term — VS2 mass isn't reliably
     *  available from a bare CollisionEvent without extra ship lookups, and
     *  speed alone is a reasonable first-pass proxy). Scales the whole system. */
    public static final double ENERGY_SCALE = 1.0;

    /** Crumble-core radius in blocks per unit of (energy / compressiveStrengthKpa). */
    public static final double CORE_RADIUS_SCALE = 40.0;
    public static final int MAX_CORE_RADIUS = 6;

    /** Crack pressure per unit of (energy / fractureToughness). */
    public static final double CRACK_PRESSURE_SCALE = 15.0;
    public static final double MAX_CRACK_PRESSURE = 80.0;

    /** Crack zone radius = core radius + this many blocks of shell. */
    public static final double CRACK_SHELL_MARGIN = 4.0;

    private record PendingImpact(String dimensionId, BlockPos pos, double speed,
                                 long shipIdA, long shipIdB) {
    }

    private static final ConcurrentLinkedQueue<PendingImpact> PENDING = new ConcurrentLinkedQueue<>();

    private static volatile boolean registered = false;

    private ImpactFractureBridge() {
    }

    // ── Registration (lazy, on first tick once VS2 is confirmed loaded) ────────

    private static void registerListenerIfNeeded() {
        if (registered) return;
        if (!ModList.get().isLoaded("valkyrienskies")) return;
        registered = true;
        try {
            ValkyrienSkiesMod.INSTANCE.getApi().getCollisionStartEvent()
                    .on(ImpactFractureBridge::onCollisionStart);
            LOGGER.info("[ImpactFractureBridge] Registered on VS2 CollisionStartEvent");
        } catch (Exception e) {
            LOGGER.error("[ImpactFractureBridge] Failed to register on VS2 collision event: {}", e.getMessage());
        }
    }

    // ── Collision callback (physics thread — queue only, don't touch the world) ─

    private static void onCollisionStart(CollisionEvent event) {
        try {
            List<ContactPoint> points = List.copyOf(event.getContactPoints());
            if (points.isEmpty()) return;

            String dimensionId = event.getDimensionId();
            long shipIdA = event.getShipIdA();
            long shipIdB = event.getShipIdB();

            for (ContactPoint cp : points) {
                Vector3dc normal = cp.getNormal();
                Vector3dc vel = cp.getVelocity();
                double closingSpeed = Math.abs(
                        vel.x() * normal.x() + vel.y() * normal.y() + vel.z() * normal.z());
                if (closingSpeed < MIN_IMPACT_SPEED) continue;

                Vector3dc pos = cp.getPosition();
                BlockPos blockPos = BlockPos.containing(pos.x(), pos.y(), pos.z());
                PENDING.add(new PendingImpact(dimensionId, blockPos, closingSpeed, shipIdA, shipIdB));
            }
        } catch (Exception e) {
            LOGGER.warn("[ImpactFractureBridge] Error queuing collision: {}", e.getMessage());
        }
    }

    // ── Server tick: drain queue on the game thread ─────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        registerListenerIfNeeded();
        if (PENDING.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            PENDING.clear();
            return;
        }

        PendingImpact impact;
        while ((impact = PENDING.poll()) != null) {
            ServerLevel level = resolveLevel(server, impact.dimensionId());
            if (level == null) continue;
            if (!isShipVsShip(level, impact)) continue; // terrain side handled by ImpactHandler
            processImpact(level, impact);
        }
    }

    /**
     * Returns true only if BOTH sides of the collision resolve to actual
     * loaded ships. One side not resolving means it's the world/terrain,
     * which {@link ImpactHandler} already owns — skip to avoid double
     * processing the same impact through two systems.
     */
    private static boolean isShipVsShip(ServerLevel level, PendingImpact impact) {
        var vsWorld = VSGameUtilsKt.getShipWorldNullable(level);
        if (!(vsWorld instanceof org.valkyrienskies.core.api.world.ServerShipWorld ssw)) return false;
        var loaded = ssw.getLoadedShips();
        return loaded.getById(impact.shipIdA()) != null && loaded.getById(impact.shipIdB()) != null;
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimensionId)) return level;
        }
        return null;
    }

    // ── Core processing ─────────────────────────────────────────────────────────

    private static void processImpact(ServerLevel level, PendingImpact impact) {
        BlockPos pos = impact.pos();
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        // Every material_properties file is expected to carry structural data.
        // If this block doesn't have any, MaterialProfile has already logged
        // it (once per block type) — we don't fabricate a plausible-sounding
        // number for data that doesn't exist, we just skip the effect here so
        // it's obvious in testing that this block needs authoring.
        var compressiveOpt = MaterialProfile.compressiveStrengthKpa(state);
        var toughnessOpt = MaterialProfile.fractureToughness(state);
        if (compressiveOpt.isEmpty() || toughnessOpt.isEmpty()) return;

        double compressiveStrengthKpa = compressiveOpt.getAsDouble();
        double fractureToughness = toughnessOpt.getAsDouble();

        double energy = ENERGY_SCALE * 0.5 * impact.speed() * impact.speed();

        int coreRadius = (int) Math.min(MAX_CORE_RADIUS,
                Math.round(CORE_RADIUS_SCALE * energy / compressiveStrengthKpa));

        float crackPressure = (float) Math.min(MAX_CRACK_PRESSURE,
                CRACK_PRESSURE_SCALE * energy / fractureToughness);

        if (coreRadius > 0) {
            crumbleCore(level, pos, coreRadius);
        }

        if (crackPressure > 0) {
            double shellRadius = coreRadius + CRACK_SHELL_MARGIN;
            CrackPropagator.addSource(new ImpactCrackSource(pos, crackPressure, shellRadius));

            // Let the existing structural pipeline re-evaluate everything the
            // crater left over-stressed or floating — same call the explosion
            // handler uses, so heavy impacts naturally cascade into further
            // CRUMBLE/CAVE_IN/FRAGMENT_VS2 dispatch on their own.
            // markDirty spreads 1 block in each direction per call, so seeds
            // need to be spaced ~2 blocks apart (with overlap) to actually
            // cover the shell rather than leaving gaps between sparse points.
            int markRadius = (int) Math.ceil(shellRadius);
            for (int dx = -markRadius; dx <= markRadius; dx += 2) {
                for (int dy = -markRadius; dy <= markRadius; dy += 2) {
                    for (int dz = -markRadius; dz <= markRadius; dz += 2) {
                        StructuralStressField.markDirty(level, pos.offset(dx, dy, dz));
                    }
                }
            }
        }
    }

    private static void crumbleCore(ServerLevel level, BlockPos center, int radius) {
        double radiusSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) continue;
                    if (level.getBlockState(p).isAir()) continue;
                    level.destroyBlock(p, true);
                }
            }
        }
    }
}