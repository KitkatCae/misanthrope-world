package exp.CCnewmods.misanthrope_world.physics.pressure.hull.client;

import exp.CCnewmods.misanthrope_world.physics.pressure.hull.network.HullPressureNetwork;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side pressure deformation state store and effect dispatcher.
 *
 * Receives pressure packets from server and stores per-block visual state
 * consumed by HullDeformationRenderLayer during RenderLevelStageEvent.
 *
 * Two visual modes:
 *   Elastic/plastic warp: face offset inward/outward by deformAmount blocks
 *   Inflation: faces scaled outward by inflationFraction, glossy tint, thinning alpha
 *
 * Stage sounds map: stage 1 = metallic dent, stage 2 = deep crunch, stage 3 = catastrophic.
 * Tension pause = low groan. Breach = directional particle burst + breach sound.
 */
@OnlyIn(Dist.CLIENT)
public final class HullDeformationRenderer {

    private HullDeformationRenderer() {}

    // ── State ─────────────────────────────────────────────────────────────────

    /** shipId -> shipSpacePos -> visual deform state */
    public static final Map<Long, Map<BlockPos, DeformState>> STATES =
            new ConcurrentHashMap<>();

    public static final class DeformState {
        public float deformAmount;
        public float inflationFraction;
        public int   stage;
        public float deltaMbar;
        public boolean tensionPause;
        public long   lastUpdateTick;
    }

    // ── Packet handlers ───────────────────────────────────────────────────────

    public static void onDeformPacket(HullPressureNetwork.DeformPacket p) {
        var map = STATES.computeIfAbsent(p.shipId(), id -> new ConcurrentHashMap<>());
        var ds  = map.computeIfAbsent(p.shipPos(), pos -> new DeformState());
        ds.deformAmount      = p.deformAmount();
        ds.inflationFraction = p.inflationFraction();
        ds.stage             = p.stage();
        ds.deltaMbar         = p.deltaMbar();
        ds.tensionPause      = false;
        ds.lastUpdateTick    = currentTick();
    }

    public static void onStageAdvance(HullPressureNetwork.StageAdvancePacket p) {
        var map = STATES.computeIfAbsent(p.shipId(), id -> new ConcurrentHashMap<>());
        var ds  = map.computeIfAbsent(p.shipPos(), pos -> new DeformState());
        ds.stage        = p.newStage();
        ds.deltaMbar    = p.deltaMbar();
        ds.tensionPause = false;
        ds.lastUpdateTick = currentTick();
        playStageSoundAtPos(p.shipPos(), p.newStage(), p.deltaMbar());
    }

    public static void onTensionPause(HullPressureNetwork.TensionPausePacket p) {
        var map = STATES.computeIfAbsent(p.shipId(), id -> new ConcurrentHashMap<>());
        var ds  = map.computeIfAbsent(p.shipPos(), pos -> new DeformState());
        ds.stage          = p.currentStage();
        ds.deltaMbar      = p.deltaMbar();
        ds.tensionPause   = true;
        ds.lastUpdateTick = currentTick();
        playTensionGroan(p.shipPos(), p.deltaMbar());
    }

    public static void onBreach(HullPressureNetwork.BreachPacket p) {
        var map = STATES.get(p.shipId());
        if (map != null) map.remove(p.shipPos());
        playBreachEffect(p.shipPos(), p.mode(), p.deltaMbar());
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    public static void clientTick() {
        if (Minecraft.getInstance().level == null) return;
        long tick = currentTick();
        STATES.forEach((id, map) ->
                map.entrySet().removeIf(e -> tick - e.getValue().lastUpdateTick > 40L));
        STATES.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public static void onLevelUnload() { STATES.clear(); }

    // ── Sound helpers ─────────────────────────────────────────────────────────

    private static void playStageSoundAtPos(BlockPos pos, int stage, float delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var sound = switch (stage) {
            case 1  -> SoundEvents.IRON_DOOR_CLOSE;
            case 2  -> SoundEvents.IRON_GOLEM_HURT;
            default -> SoundEvents.ANVIL_LAND;
        };
        float pitch  = 0.6f + 0.2f * stage;
        float volume = Math.min(2.0f, 0.5f + Math.abs(delta) / 1000f);
        mc.level.playLocalSound(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5,
                sound, SoundSource.BLOCKS, volume, pitch, false);
    }

    private static void playTensionGroan(BlockPos pos, float delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float pitch  = Math.min(0.7f, 0.4f + 0.1f * (Math.abs(delta) / 500f));
        float volume = Math.min(1.5f, 0.3f + Math.abs(delta) / 1500f);
        mc.level.playLocalSound(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5,
                SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, volume, pitch, false);
    }

    private static void playBreachEffect(BlockPos pos,
                                          BlockPhysicsData.PressureBreachMode mode,
                                          float delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var sound = switch (mode) {
            case SHATTER -> SoundEvents.GLASS_BREAK;
            case IMPLODE -> SoundEvents.GENERIC_EXPLODE;
            case TEAR    -> SoundEvents.WOOL_BREAK;
            case VENT    -> SoundEvents.GENERIC_EXPLODE;
            default      -> SoundEvents.STONE_BREAK;
        };
        float volume = Math.min(3.0f, 0.8f + Math.abs(delta) / 500f);
        mc.level.playLocalSound(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5,
                sound, SoundSource.BLOCKS, volume, 0.8f, false);

        // Directional particle burst
        boolean inward = delta > 0;
        ClientLevel lvl = mc.level;
        for (int i = 0; i < 16; i++) {
            double vx = (Math.random()-0.5)*0.3 * (inward ? -1 : 1);
            double vy = (Math.random()-0.5)*0.3;
            double vz = (Math.random()-0.5)*0.3 * (inward ? -1 : 1);
            var pt = mode == BlockPhysicsData.PressureBreachMode.SHATTER
                    ? net.minecraft.core.particles.ParticleTypes.CRIT
                    : net.minecraft.core.particles.ParticleTypes.EXPLOSION;
            lvl.addParticle(pt, pos.getX()+.5+vx*2, pos.getY()+.5+vy*2,
                    pos.getZ()+.5+vz*2, vx*3, vy*3, vz*3);
        }
    }

    private static long currentTick() {
        var lvl = Minecraft.getInstance().level;
        return lvl != null ? lvl.getGameTime() : 0L;
    }
}
