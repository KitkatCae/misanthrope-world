package exp.CCnewmods.misanthrope_world.physics.pressure.creature;

import exp.CCnewmods.misanthrope_world.physics.pressure.FluidPressureSampler;
import exp.CCnewmods.misanthrope_world.physics.pressure.PressurePhysicsConfig;
import exp.CCnewmods.misanthrope_world.registry.MisWorldDamageTypes;
import exp.CCnewmods.misanthrope_world.registry.MisWorldSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side handler that applies pressure-based damage to creatures.
 *
 * <h3>Pressure sampling</h3>
 * Total pressure at the entity's feet block is:
 * <pre>
 *   total = gasPressureMbar(MGE) + fluidHydrostaticMbar(MWorld)
 * </pre>
 * Gas pressure comes from {@code GridAtmosphereCompat.getTotalPressure(level, pos)}
 * (falls back to 1013.25 mbar if MGE is absent).  Fluid pressure comes from
 * {@link FluidPressureSampler#getFluidColumnPressureMbar}.
 *
 * <h3>Damage application</h3>
 * Each entity in the world that has a {@link CreaturePressureProfile} is
 * evaluated every {@link LivingEvent.LivingTickEvent}.  Per-entity
 * {@code accumulatorTick} counters live in {@link #ACCUMULATORS}, keyed by
 * entity UUID.  Entries are evicted when the entity leaves the server.
 *
 * <p>Instant-kill thresholds bypass the accumulator and apply damage
 * immediately, once per tick, until the entity moves out of the lethal zone
 * or dies.
 *
 * <h3>Player exemption</h3>
 * By default players are NOT processed here — they are handled separately via
 * ColdSweat's trait system and the altitude compat layer.  Set
 * {@code creaturePressure.damagePlayer = true} in the server config to opt
 * players back in (useful when ColdSweat is absent).
 *
 * <h3>Registration</h3>
 * Registered by {@code Misanthrope_world.commonSetup} on the FORGE event bus.
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CreaturePressureDamageHandler {

    private CreaturePressureDamageHandler() {}

    private static final Logger LOGGER = LogManager.getLogger("MisWorld/CreaturePressure");

    /** Standard sea-level pressure used as the MGE fallback (mbar). */
    private static final float STANDARD_ATMOSPHERE_MBAR = 1013.25f;

    // ── MGE reflection (same pattern as WorldSpacePressureHandler) ────────────

    private static volatile boolean mgeResolved      = false;
    @Nullable private static Method mgeGetTotalPressure = null; // GridAtmosphereCompat.getTotalPressure(Level,BlockPos) → float

    private static void resolveMge() {
        if (mgeResolved) return;
        mgeResolved = true;
        if (!ModList.get().isLoaded("mge")) return;
        try {
            Class<?> compat = Class.forName("exp.CCnewmods.mge.grid.compat.GridAtmosphereCompat");
            mgeGetTotalPressure = compat.getMethod("getTotalPressure",
                    net.minecraft.world.level.Level.class, BlockPos.class);
            LOGGER.info("[MisWorld/CreaturePressure] MGE detected — gas pressure active for creature damage.");
        } catch (Exception e) {
            LOGGER.warn("[MisWorld/CreaturePressure] MGE reflection failed — gas pressure falls back to {} mbar: {}",
                    STANDARD_ATMOSPHERE_MBAR, e.getMessage());
        }
    }

    // ── Per-entity accumulator ────────────────────────────────────────────────

    /**
     * Tracks how many ticks each entity has accumulated inside a damage zone
     * since the last damage application.
     *
     * <p>Format: {@code UUID → accumulatorTicks}
     */
    private static final Map<UUID, Integer> ACCUMULATORS = new ConcurrentHashMap<>();

    /**
     * Tracks the pressure damage type most recently applied to each entity so
     * the correct death sound can be played on {@link LivingDeathEvent}.
     * {@code true} = last damage was crush, {@code false} = vacuum.
     */
    private static final Map<UUID, Boolean> LAST_WAS_CRUSH = new ConcurrentHashMap<>();

    // ── Event handler ─────────────────────────────────────────────────────────

    /**
     * Evaluated once per living entity per server tick.
     * Skips clients, non-server levels, creative/spectator players, entities
     * without a registered profile, and (by default) players.
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();

        // Server-side only
        if (entity.level().isClientSide()) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        // Config guard
        PressurePhysicsConfig cfg = PressurePhysicsConfig.INSTANCE;
        if (!cfg.enabled.get()) return;
        if (!cfg.creaturePressureDamageEnabled.get()) return;

        // Player exemption (ColdSweat handles players via its trait system)
        if (entity instanceof Player player) {
            if (!cfg.creaturePressureDamagePlayer.get()) return;
            if (player.isCreative() || player.isSpectator()) return;
        }

        // Entity must have a registered profile
        String entityKey = getEntityKey(entity);
        if (entityKey == null) return;

        CreaturePressureProfile profile = CreaturePressureLoader.INSTANCE.get(entityKey);
        if (profile == null) return;

        resolveMge();

        // Sample total pressure at the entity's eye/feet position
        BlockPos pos = entity.blockPosition();
        float totalPressure = sampleTotalPressure(level, pos);

        // ── Check crush zone ─────────────────────────────────────────────────
        if (profile.hasCrushZone() && totalPressure > profile.crushThresholdMbar) {

            // Instant-kill overrides interval damage
            if (totalPressure > profile.crushInstantKillThresholdMbar) {
                applyDamage(entity, profile, profile.crushInstantKillDamage, true, true);
                return;
            }

            tickAccumulatorAndMaybeDamage(entity, profile, profile.crushDamagePerInterval, true);
            return;
        }

        // ── Check vacuum zone ─────────────────────────────────────────────────
        if (profile.hasVacuumZone()
                && cfg.simulateExpansion.get()
                && totalPressure < profile.vacuumThresholdMbar) {

            if (profile.vacuumInstantKillThresholdMbar >= 0f
                    && totalPressure < profile.vacuumInstantKillThresholdMbar) {
                applyDamage(entity, profile, profile.vacuumInstantKillDamage, true, false);
                return;
            }

            tickAccumulatorAndMaybeDamage(entity, profile, profile.vacuumDamagePerInterval, false);
            return;
        }

        // Entity is in a safe zone — reset accumulator
        ACCUMULATORS.remove(entity.getUUID());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Increments the entity's accumulator by 1 tick.  When it reaches
     * {@code profile.damageIntervalTicks} the entity takes {@code damage} (after
     * armour reduction) and the accumulator resets.
     */
    private static void tickAccumulatorAndMaybeDamage(
            LivingEntity entity,
            CreaturePressureProfile profile,
            float damage,
            boolean isCrush) {

        int acc = ACCUMULATORS.merge(entity.getUUID(), 1, Integer::sum);
        if (acc >= profile.damageIntervalTicks) {
            ACCUMULATORS.put(entity.getUUID(), 0);
            applyDamage(entity, profile, damage, false, isCrush);
        }
    }

    /**
     * Applies pressure damage to the entity, optionally respecting
     * {@code pressure_sealed} armour reduction, plays the appropriate hurt
     * sound, and records which type was last applied for death sound selection.
     */
    private static void applyDamage(
            LivingEntity entity,
            CreaturePressureProfile profile,
            float rawDamage,
            boolean isInstantKill,
            boolean isCrush) {

        float damage = rawDamage;

        // Armour reduction (not applied for instant-kill thresholds)
        if (profile.respectPressureArmour && !isInstantKill) {
            float reduction = PressureSealedRegistry.INSTANCE.getDamageReduction(entity);
            damage = damage * (1f - reduction);
        }

        if (damage <= 0f) return;

        // Choose damage source
        DamageSource source = isCrush
                ? MisWorldDamageTypes.crush(entity.level())
                : MisWorldDamageTypes.vacuum(entity.level());

        // Record for death sound
        LAST_WAS_CRUSH.put(entity.getUUID(), isCrush);

        // Apply damage — if it would kill the entity, LivingDeathEvent fires
        // before the entity is actually removed, so the death sound handler
        // below will still find the LAST_WAS_CRUSH entry.
        boolean died = !entity.hurt(source, damage);

        // Play hurt sound at the entity's position (server broadcasts to nearby clients)
        if (!died && entity.isAlive()) {
            var sound = isCrush
                    ? MisWorldSounds.HURT_CRUSH.get()
                    : MisWorldSounds.HURT_VACUUM.get();
            entity.level().playSound(
                    null,                          // null = send to all nearby clients
                    entity.getX(), entity.getY(), entity.getZ(),
                    sound,
                    SoundSource.HOSTILE,
                    1.0f,
                    0.9f + entity.level().random.nextFloat() * 0.2f);
        }
    }

    /**
     * Plays the pressure death sound when an entity dies from a pressure
     * damage source.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        String msgId = event.getSource().type().msgId();
        boolean isCrushDeath  = "crush".equals(msgId);
        boolean isVacuumDeath = "vacuum".equals(msgId);

        if (!isCrushDeath && !isVacuumDeath) {
            // Not a pressure death — but check if last pressure damage was tracked
            // (entity may have died to something else while in a pressure zone; skip)
            return;
        }

        var sound = isCrushDeath
                ? MisWorldSounds.DEATH_CRUSH.get()
                : MisWorldSounds.DEATH_VACUUM.get();

        entity.level().playSound(
                null,
                entity.getX(), entity.getY(), entity.getZ(),
                sound,
                SoundSource.HOSTILE,
                1.0f,
                0.85f + entity.level().random.nextFloat() * 0.3f);

        LAST_WAS_CRUSH.remove(entity.getUUID());
    }

    /**
     * Called when an entity is removed from the world (e.g. death, unload).
     * Cleans up the per-entity accumulator entry to prevent memory leaks.
     *
     * <p>Register this on the FORGE event bus alongside the tick handler.
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(
            net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        ACCUMULATORS.remove(event.getEntity().getUUID());
        LAST_WAS_CRUSH.remove(event.getEntity().getUUID());
    }
    private static float sampleTotalPressure(ServerLevel level, BlockPos pos) {
        float gas   = sampleGasPressure(level, pos);
        float fluid = FluidPressureSampler.getFluidColumnPressureMbar(level, pos);
        return gas + fluid;
    }

    /**
     * Reads MGE total gas pressure at {@code pos}, falling back to standard
     * atmosphere when MGE is absent or the chunk is not tracked.
     */
    private static float sampleGasPressure(ServerLevel level, BlockPos pos) {
        if (mgeGetTotalPressure == null) return STANDARD_ATMOSPHERE_MBAR;
        try {
            float p = (float) mgeGetTotalPressure.invoke(null, level, pos);
            return (p > 0f) ? p : STANDARD_ATMOSPHERE_MBAR;
        } catch (Exception e) {
            return STANDARD_ATMOSPHERE_MBAR;
        }
    }

    /**
     * Returns the Forge registry key string for the entity's type, or
     * {@code null} if the entity has no registered type.
     */
    @Nullable
    private static String getEntityKey(Entity entity) {
        EntityType<?> type = entity.getType();
        var key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return (key == null) ? null : key.toString();
    }

}
