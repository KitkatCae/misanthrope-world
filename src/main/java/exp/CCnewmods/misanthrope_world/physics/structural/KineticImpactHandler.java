package exp.CCnewmods.misanthrope_world.physics.structural;

import exp.CCnewmods.mge.shockwave.ShockwaveHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Routes kinetic impact events into {@link ShockwaveHandler#spawn} so that
 * MGE's shockwave system handles all resulting structural stress.
 *
 * <h3>Sources handled</h3>
 * <ul>
 *   <li><b>Projectile → block ({@code ProjectileImpactEvent})</b> — any non-explosive
 *       projectile. Mass estimated from entity type; overridden for CBC projectiles
 *       via reflected {@code getProjectileMass()} for accuracy.</li>
 *   <li><b>VS2 ship collision</b> — subscribed via
 *       {@code VsCoreApi.getCollisionPersistEvent()} in {@link ModBusEvents}.
 *       Contact velocity + ship mass → shockwave at contact centroid.</li>
 *   <li><b>FullStop collision</b> — detected via {@code LivingHurtEvent} at low
 *       priority. Identified purely via the vanilla DamageSource/DamageType
 *       registry API (checking for the "fullstop" namespace) — no FullStop
 *       class is referenced, so this survives FullStop's internal rewrites.
 *       We read attacker/victim velocity directly off the vanilla entity and
 *       spawn a shockwave at the collision position.</li>
 *   <li><b>Kinetic Minecart</b> — detected via {@code LivingHurtEvent}. Kinetic
 *       Minecart uses {@code DamageSources.generic()} from a minecart entity as
 *       source; we identify it by the source entity type and compute KE from
 *       minecart velocity.</li>
 * </ul>
 *
 * <p>Create Interactive ships use the VS2 collision path. Create trains on VS2
 * ships also use the VS2 collision path.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KineticImpactHandler {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/KineticImpact");

    /**
     * KE (game-scaled J) → shockwave strength. Tuned so iron cannonball ~80 m/s → strength ~1.5.
     * Public: {@link exp.CCnewmods.misanthrope_world.physics.structural.vs2.ImpactHandler}
     * reuses this directly so ship-vs-terrain shockwaves are calibrated identically to
     * every other kinetic-impact source in this file, rather than inventing a second,
     * independently-tuned constant for what is physically the same kind of event.
     */
    public static final float KE_TO_STRENGTH = 0.008f;
    public static final double MIN_KE = 20.0;

    /**
     * Scale applied to VS2 ship-collision KE specifically (both ship-vs-ship,
     * here, and ship-vs-terrain, in ImpactHandler) — collisions between rigid
     * hulls read as harder hits than the same KE delivered by a small dense
     * projectile, so this is lower than the projectile path's implicit 1.0.
     */
    public static final float SHIP_COLLISION_STRENGTH_SCALE = 0.3f;

    /** Below this, a shockwave wouldn't do anything worth the propagation cost. */
    public static final float MIN_SHOCKWAVE_STRENGTH = 0.05f;

    private static final double ARROW_MASS = 0.03;
    private static final double CANNONBALL_MASS = 8.0;
    private static final double DEFAULT_PROJECTILE_MASS = 0.1;

    private static final boolean VS2_LOADED = ModList.get().isLoaded("valkyrienskies");
    private static final boolean FULLSTOP_LOADED = ModList.get().isLoaded("fullstop");
    private static final boolean CBC_LOADED = ModList.get().isLoaded("createbigcannons");
    private static final boolean KMINECART_LOADED = ModList.get().isLoaded("kineticminecart");

    // ── Reflection handles ────────────────────────────────────────────────────

    /**
     * CBC AbstractCannonProjectile.getProjectileMass() → float
     */
    @Nullable
    private static Method cbcGetProjectileMass;
    /**
     * CBC AbstractCannonProjectile class
     */
    @Nullable
    private static Class<?> cbcProjectileClass;

    private static boolean reflectedCbc = false;

    // ── VS2 + MOD bus events ──────────────────────────────────────────────────

    @Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void onLoadComplete(FMLLoadCompleteEvent event) {
            initReflection();
            if (!VS2_LOADED) return;
            try {
                var coreApi = org.valkyrienskies.mod.common.ValkyrienSkiesMod.INSTANCE.getApi();
                coreApi.getCollisionPersistEvent().on(
                        (java.util.function.Consumer<org.valkyrienskies.core.api.events.CollisionEvent>)
                                KineticImpactHandler::handleVS2Collision);
                LOGGER.info("[KineticImpactHandler] VS2 collision listener registered.");
            } catch (Exception e) {
                LOGGER.warn("[KineticImpactHandler] VS2 collision subscription failed: {}", e.getMessage());
            }
        }
    }

    private static void initReflection() {
        // CBC
        if (CBC_LOADED && !reflectedCbc) {
            reflectedCbc = true;
            try {
                cbcProjectileClass = Class.forName(
                        "rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile");
                cbcGetProjectileMass = cbcProjectileClass.getMethod("getProjectileMass");
                LOGGER.info("[KineticImpactHandler] CBC mass reflection initialised.");
            } catch (Exception e) {
                LOGGER.warn("[KineticImpactHandler] CBC reflection failed: {}", e.getMessage());
            }
        }

        // FullStop — no reflection needed. We identify FullStop's kinetic damage
        // purely through the vanilla DamageSource/DamageType API (see
        // isFullStopDamage below), which works unchanged across FullStop's
        // internal rewrites since it never touches a FullStop class directly.
    }

    // ── Projectile → block ────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        HitResult hit = event.getRayTraceResult();
        if (hit.getType() != HitResult.Type.BLOCK) return;

        Projectile projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level)) return;

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos pos = blockHit.getBlockPos();

        double mass = estimateMass(projectile);
        double speedSq = projectile.getDeltaMovement().lengthSqr();
        double ke = 0.5 * mass * speedSq;
        if (ke < MIN_KE) return;

        float strength = (float) (Math.sqrt(ke) * KE_TO_STRENGTH);
        if (strength < 0.05f) return;
        ShockwaveHandler.spawn(level, pos, strength);
    }

    private static double estimateMass(Projectile projectile) {
        // CBC projectiles have precise mass — use it
        if (cbcProjectileClass != null && cbcProjectileClass.isInstance(projectile)) {
            try {
                Object m = cbcGetProjectileMass.invoke(projectile);
                if (m instanceof Number n) return n.doubleValue();
            } catch (Exception ignored) {
            }
        }

        // Fall back to type-name heuristics
        String typeId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getKey(projectile.getType()) != null
                ? net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getKey(projectile.getType()).toString()
                : "";

        if (typeId.contains("arrow") || typeId.contains("bolt")) return ARROW_MASS;
        if (typeId.contains("cannon") || typeId.contains("ball")
                || typeId.contains("shot") || typeId.contains("slug")
                || typeId.contains("shell") || typeId.contains("round")) return CANNONBALL_MASS;
        if (projectile instanceof net.minecraft.world.entity.projectile.AbstractArrow)
            return ARROW_MASS;

        return DEFAULT_PROJECTILE_MASS;
    }

    // ── FullStop + Kinetic Minecart (LivingHurtEvent) ─────────────────────────

    /**
     * LOW priority so we run after vanilla damage resolution but before
     * most other handlers modify the event.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;

        var source = event.getSource();
        Entity attacker = source.getEntity();
        float amount = event.getAmount();

        // ── FullStop collision ────────────────────────────────────────────────
        // FullStop appends "(going X.XX m/s)" to its damage source message id.
        // We identify it by checking the damage type location.
        if (FULLSTOP_LOADED && isFullStopDamage(source)) {
            handleFullStopImpact(level, event, attacker, amount);
            return;
        }

        // ── Kinetic Minecart ──────────────────────────────────────────────────
        // KineticMinecart uses DamageSources.generic() with the minecart as source entity.
        if (KMINECART_LOADED
                && attacker instanceof net.minecraft.world.entity.vehicle.AbstractMinecart cart) {
            handleKineticMinecartImpact(level, cart);
        }
    }

    // NOTE: the previous per-tick "FullStop block collision" scan (onLevelTick)
    // was removed here. It hard-referenced
    // net.camacraft.fullstop.common.capabilities.FullStopCapability by class
    // literal — that package was renamed to .capability (singular) in FullStop's
    // rewrite, so the literal reference threw NoClassDefFoundError (an Error,
    // NOT an Exception — the surrounding `catch (Exception ignored)` never
    // caught it), crashing the server tick loop.
    //
    // It's not being reinstated as-is because it was already non-functional
    // before this update: it looked up "DELTAV_CAP" via
    // FullStopCapability.class.getDeclaredField(...), but DELTAV_CAP has only
    // ever been declared on the nested Provider class in both the old and new
    // FullStop jars, never on FullStopCapability itself — so that reflective
    // lookup always threw NoSuchFieldException and every entity was skipped,
    // every tick, in every version we've shipped against. In other words this
    // code never once produced a shockwave.
    //
    // FullStop's new build also no longer exposes a live per-entity
    // CollisionType at all (no more `impact` field on the capability — block
    // collisions are now resolved and consumed internally in
    // server.physics.interaction.KineticBlockInteractions with no capability
    // exposure), so there's no drop-in replacement using the same approach.
    //
    // If block-only (non-damaging) kinetic impacts should shockwave, the
    // supportable replacement is a vanilla-only heuristic — per moving entity,
    // check entity.horizontalCollision / entity.verticalCollision (stable
    // vanilla Entity fields, no FullStop dependency at all) against the
    // previous tick's speed — rather than reflecting into FullStop internals
    // that may change shape again. Flagging this as a design decision rather
    // than silently reintroducing new behavior.

    /**
     * Identifies FullStop kinetic damage purely via the vanilla DamageSource /
     * DamageType registry API — no FullStop class is referenced, so this keeps
     * working regardless of FullStop's internal package/class churn.
     * <p>
     * In FullStop's current (2026-07 rewrite) build, all of its damage types
     * ({@code fullstop:kinetic}, {@code fullstop:kinetic_fall},
     * {@code fullstop:pressure}, {@code fullstop:atmosphere}) live in the
     * "fullstop" registry namespace, so checking the type holder's resource
     * key namespace is a precise, stable match.
     * <p>
     * Note: FullStop's pre-rewrite build reused vanilla types
     * ({@code minecraft:fall} / {@code minecraft:fly_into_wall}) for its
     * kinetic damage and only customized the death message text, which is not
     * observable from the DamageSource at all — so this same limitation
     * (no reliable detection) existed on the old build too; this is not a
     * regression, it's the first version where detection is actually possible.
     */
    private static boolean isFullStopDamage(net.minecraft.world.damagesource.DamageSource source) {
        try {
            return source.typeHolder().unwrapKey()
                    .map(key -> key.location().getNamespace().equals("fullstop"))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void handleFullStopImpact(ServerLevel level, LivingHurtEvent event,
                                             @Nullable Entity attacker, float damage) {
        Entity victim = event.getEntity();

        // Spawn shockwave at victim position (the collision point)
        BlockPos pos = victim.blockPosition();
        if (!level.isLoaded(pos)) return;

        // Estimate speed from attacker velocity (if entity collision) or victim
        Vec3 vel = attacker != null ? attacker.getDeltaMovement() : victim.getDeltaMovement();
        double speedSq = vel.lengthSqr();

        // Attacker mass: use attacker bounding box volume as proxy
        double mass = attacker != null
                ? estimateEntityMass(attacker)
                : estimateEntityMass(victim);

        double ke = 0.5 * mass * speedSq;
        if (ke < MIN_KE) return;

        float strength = (float) (Math.sqrt(ke) * KE_TO_STRENGTH * 0.5f);
        // 0.5 scale: FullStop entity-entity collisions are softer than projectile impacts
        if (strength < 0.05f) return;
        ShockwaveHandler.spawn(level, pos, strength);
    }

    private static void handleKineticMinecartImpact(ServerLevel level,
                                                    net.minecraft.world.entity.vehicle.AbstractMinecart cart) {
        Vec3 vel = cart.getDeltaMovement();
        double speedSq = vel.lengthSqr();
        if (speedSq < 0.01) return; // barely moving

        // Minecart mass approximation: empty = ~450kg, loaded = ~800kg
        double mass = 450.0;
        double ke = 0.5 * mass * speedSq;
        if (ke < MIN_KE) return;

        float strength = (float) (Math.sqrt(ke) * KE_TO_STRENGTH * 0.4f);
        if (strength < 0.05f) return;
        ShockwaveHandler.spawn(level, cart.blockPosition(), strength);
    }

    /**
     * Rough entity mass from bounding box volume × assumed density.
     */
    private static double estimateEntityMass(Entity entity) {
        var bb = entity.getBoundingBox();
        double volume = (bb.maxX - bb.minX) * (bb.maxY - bb.minY) * (bb.maxZ - bb.minZ);
        // Players/mobs ~70kg/m³ scaled; vehicles/contraptions ~500kg/m³
        boolean isVehicle = entity instanceof net.minecraft.world.entity.vehicle.Boat
                || entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecart;
        return volume * (isVehicle ? 500.0 : 70.0);
    }

    // ── VS2 ship collision ────────────────────────────────────────────────────

    /**
     * Handles VS2 collision events — but only ship-vs-ship. Ship-vs-terrain
     * collisions are {@link exp.CCnewmods.misanthrope_world.physics.structural.vs2.ImpactHandler}'s
     * exclusive domain (it already polls every ship's velocity each tick
     * looking for exactly this), and ImpactHandler now calls
     * {@link ShockwaveHandler#spawn} directly with its own precise
     * speed/mass numbers once it resolves an impact — see its
     * {@code resolveImpact}. Before this split, both systems reacted to the
     * same physical event independently: ImpactHandler carved the crater
     * with exact numbers, this class re-derived a cruder shockwave strength
     * from the raw collision event for the same hit, at a possibly-different
     * point (contact centroid vs. crater center). That's not wrong exactly,
     * but it's two independent, uncoordinated opinions about the same event.
     * <p>
     * There's no reliable published sentinel for "no ship" on
     * {@code CollisionEvent.getShipIdB()} across VS2 versions, so rather than
     * assume one, this checks whether shipIdB actually resolves to a loaded
     * ship — if it doesn't, this is terrain, and ImpactHandler already has it
     * covered.
     */
    private static void handleVS2Collision(
            org.valkyrienskies.core.api.events.CollisionEvent event) {
        var contactPoints = event.getContactPoints();
        if (contactPoints == null || contactPoints.isEmpty()) return;

        String dimId = event.getDimensionId();
        ServerLevel level = findLevel(dimId);
        if (level == null) return;

        if (!isLoadedShip(level, event.getShipIdB())) return; // ship-vs-terrain — ImpactHandler's job now

        double cx = 0, cy = 0, cz = 0;
        double maxVelSq = 0;
        for (var cp : contactPoints) {
            var pos = cp.getPosition();
            cx += pos.x();
            cy += pos.y();
            cz += pos.z();
            var vel = cp.getVelocity();
            double vSq = vel.x() * vel.x() + vel.y() * vel.y() + vel.z() * vel.z();
            maxVelSq = Math.max(maxVelSq, vSq);
        }
        int n = contactPoints.size();
        BlockPos centroid = new BlockPos((int) (cx / n), (int) (cy / n), (int) (cz / n));

        double mass = estimateShipMass(level, event.getShipIdA());
        double ke = 0.5 * mass * maxVelSq;
        if (ke < MIN_KE) return;

        float strength = (float) (Math.sqrt(ke) * KE_TO_STRENGTH * SHIP_COLLISION_STRENGTH_SCALE);
        if (strength < MIN_SHOCKWAVE_STRENGTH) return;
        ShockwaveHandler.spawn(level, centroid, strength);
    }

    /** Whether {@code shipId} currently resolves to a real loaded ship in {@code level}. */
    private static boolean isLoadedShip(ServerLevel level, long shipId) {
        try {
            var shipWorld = org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(level);
            if (shipWorld == null) return false;
            for (var s : shipWorld.getLoadedShips()) {
                if (s.getId() == shipId) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static double estimateShipMass(ServerLevel level, long shipId) {
        try {
            var shipWorld = org.valkyrienskies.mod.common.VSGameUtilsKt
                    .getShipObjectWorld(level);
            if (shipWorld == null) return 1000.0;
            for (var s : shipWorld.getLoadedShips()) {
                if (s.getId() == shipId && s instanceof org.valkyrienskies.core.api.ships.ServerShip serverShip) {
                    // Real ship mass, not a volumetric guess assuming uniform
                    // stone density across every occupied chunk — bytecode-verified
                    // against the actual VS2 api jar (ServerShip.getInertiaData()
                    // .getShipMass():D).
                    return serverShip.getInertiaData().getShipMass();
                }
            }
        } catch (Exception ignored) {
        }
        return 1000.0;
    }

    @Nullable
    private static ServerLevel findLevel(String dimensionId) {
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().location().toString().equals(dimensionId)) return level;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}