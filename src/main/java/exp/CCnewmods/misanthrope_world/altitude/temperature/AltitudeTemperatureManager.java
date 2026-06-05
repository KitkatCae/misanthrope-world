package exp.CCnewmods.misanthrope_world.altitude.temperature;

import exp.CCnewmods.misanthrope_world.Misanthrope_world;
import exp.CCnewmods.misanthrope_world.altitude.compat.ColdSweatAltitudeCompat;
import exp.CCnewmods.misanthrope_world.config.MisWorldConfig;
import exp.CCnewmods.misanthrope_world.altitude.compat.MgeAtmosphereReader;
import exp.CCnewmods.misanthrope_world.config.AltitudeConfig;
import exp.CCnewmods.misanthrope_world.altitude.player.PlayerAltitudeState;
import exp.CCnewmods.misanthrope_world.altitude.protection.AltitudeProtectionManager;
import exp.CCnewmods.misanthrope_world.altitude.shelter.ShelterManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core per-player altitude temperature loop — Forge 1.20.1.
 * <p>
 * Every {@value UPDATE_INTERVAL_TICKS} ticks per online player:
 * <ol>
 *   <li>Find the highest-priority matching band.</li>
 *   <li>Read wind speed from MGE (→ PA) to adjust shelter effectiveness.</li>
 *   <li>Read total gas pressure and O2 from MGE grid to compute atmosphere
 *       thinning factor — amplifies cold at altitude in thin air.</li>
 *   <li>Compute protection and shelter multipliers.</li>
 *   <li>Push a {@code SimpleTempModifier} onto ColdSweat's WORLD trait.</li>
 *   <li>Send on-enter / actionbar messages on band change.</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = Misanthrope_world.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AltitudeTemperatureManager {

    static final int UPDATE_INTERVAL_TICKS = 20;

    private static final AltitudeTemperatureManager INSTANCE = new AltitudeTemperatureManager();
    public static AltitudeTemperatureManager getInstance() { return INSTANCE; }

    private final Map<UUID, PlayerAltitudeState> playerStates = new ConcurrentHashMap<>();

    private AltitudeTemperatureManager() {}

    // ── Forge event handlers ───────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!MisWorldConfig.isAltitudeTemperatureEnabled()) return;
        if (event.player.level().isClientSide()) return;
        if (!(event.player instanceof ServerPlayer sp)) return;
        if (sp.isSpectator() || !sp.isAlive()) return;
        if (sp.tickCount % UPDATE_INTERVAL_TICKS != 0) return;
        INSTANCE.refreshState(sp);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        INSTANCE.playerStates.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer sp)
            ColdSweatAltitudeCompat.INSTANCE.removeAltitudeModifier(sp);
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        INSTANCE.playerStates.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer sp)
            ColdSweatAltitudeCompat.INSTANCE.removeAltitudeModifier(sp);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        INSTANCE.playerStates.remove(sp.getUUID());
        ColdSweatAltitudeCompat.INSTANCE.removeAltitudeModifier(sp);
    }

    // ── Core logic ─────────────────────────────────────────────────────────────

    /**
     * Refreshes altitude state for one player.
     * Also callable on demand from the status command.
     */
    public PlayerAltitudeState refreshState(ServerPlayer player) {
        Optional<AltitudeBand> bandOpt = findMatchingBand(player);
        AltitudeBand band = bandOpt.orElse(null);

        BlockPos pos = player.blockPosition();
        MgeAtmosphereReader mge = MgeAtmosphereReader.getInstance();

        // ── Protection (armor tag) ───────────────────────────────────────────
        double protection = (band != null)
                ? AltitudeProtectionManager.INSTANCE.protectionMultiplier(player, band)
                : 0.0;

        // ── Wind (from MGE/PA) ───────────────────────────────────────────────
        double windMps = ShelterManager.INSTANCE.windSpeedMps(player);

        // ── Shelter (flood-fill, wind-adjusted) ──────────────────────────────
        double shelterEnc = (band != null)
                ? ShelterManager.INSTANCE.shelterEnclosure(player, band)
                : 0.0;
        double shelterMult = (band != null)
                ? ShelterManager.INSTANCE.shelterMultiplier(band, shelterEnc, windMps)
                : 0.0;

        // ── Atmosphere thinning (MGE grid) ────────────────────────────────────
        // Protection gear counteracts the thinning penalty — a player in full high-
        // altitude gear is carrying their own atmosphere effectively.
        float totalMbar    = mge.getTotalPressureMbar(player.level(), pos);
        float baselineMbar = mge.getDimensionBaselinePressureMbar(player.level(), pos);
        float oxygenMbar   = mge.getOxygenMbar(player.level(), pos);

        double atmosphereThinFactor = MgeAtmosphereReader.computeAtmosphereThinFactor(
                totalMbar, baselineMbar, oxygenMbar, protection);

        // ── State update ─────────────────────────────────────────────────────
        PlayerAltitudeState state = playerStates.computeIfAbsent(
                player.getUUID(), id -> new PlayerAltitudeState());

        state.refresh(band, UPDATE_INTERVAL_TICKS,
                protection, shelterEnc, windMps, shelterMult,
                atmosphereThinFactor, oxygenMbar);

        // ── Apply to ColdSweat ───────────────────────────────────────────────
        if (band != null && !player.isCreative()) {
            ColdSweatAltitudeCompat.INSTANCE.applyAltitudeModifier(
                    player, band.id(), state.finalModifier(), band.modifierMode());
        } else {
            ColdSweatAltitudeCompat.INSTANCE.removeAltitudeModifier(player);
        }

        // ── Messages ─────────────────────────────────────────────────────────
        if (band != null) sendWarnings(player, band, state);

        return state;
    }

    /** Finds the first (highest-priority) band matching this player's position. */
    public Optional<AltitudeBand> findMatchingBand(ServerPlayer player) {
        ResourceLocation dimensionId = player.level().dimension().location();
        int y = player.getBlockY();
        return AltitudeConfig.getInstance().getBands().stream()
                .filter(b -> b.matches(dimensionId, y))
                .findFirst();
    }

    // ── Messages ───────────────────────────────────────────────────────────────

    private void sendWarnings(ServerPlayer player, AltitudeBand band, PlayerAltitudeState state) {
        long gameTime = player.level().getGameTime();

        if (state.bandChanged() && !band.onEnterMessage().isBlank()) {
            player.sendSystemMessage(resolveMessage(band.onEnterMessage()));
            state.setLastMessageTick(gameTime);
        }

        if (!band.actionbarMessage().isBlank()) {
            if (gameTime - state.lastMessageTick() >= band.messageCooldownTicks()) {
                player.displayClientMessage(resolveMessage(band.actionbarMessage()), true);
                state.setLastMessageTick(gameTime);
            }
        }
    }

    private static Component resolveMessage(String msg) {
        return (msg.contains(".") && !msg.contains(" "))
                ? Component.translatable(msg)
                : Component.literal(msg);
    }
}
