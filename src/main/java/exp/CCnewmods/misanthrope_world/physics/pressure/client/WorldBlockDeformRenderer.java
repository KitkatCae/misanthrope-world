package exp.CCnewmods.misanthrope_world.physics.pressure.client;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.pressure.network.WorldPressureNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side world-space pressure deformation state store and effect dispatcher.
 *
 * <p>Receives packets from {@link WorldPressureNetwork} and stores per-block
 * visual state consumed by {@code WorldBlockDeformRenderLayer} during
 * {@code RenderLevelStageEvent}.
 *
 * <p>This is the world-space analog of MVSE's {@code HullDeformationRenderer}.
 * Key difference: state is keyed by world-space {@link BlockPos} only (no
 * shipId), and rendering uses fixed world-space coordinates instead of the VS2
 * ship transform. The {@code HullDeformationRenderer} in MVSE is unchanged and
 * continues to handle ship hull deformation independently.
 */
@OnlyIn(Dist.CLIENT)
public final class WorldBlockDeformRenderer {

    private WorldBlockDeformRenderer() {}

    // ── Client-side state ─────────────────────────────────────────────────────

    /**
     * World-space position → current visual deform state.
     * Written by packet handlers on the main thread; read by render layer
     * during {@code RenderLevelStageEvent}.
     */
    public static final Map<BlockPos, DeformState> STATES = new ConcurrentHashMap<>();

    public static final class DeformState {
        /** Total visual deformation in blocks (positive = outward, negative = inward). */
        public float deformAmount;
        /** Current inflation fraction [0, 1]. */
        public float inflationFraction;
        /** Current plastic deformation stage. */
        public int   stage;
        /** Last received ΔP (signed). */
        public float deltaMbar;
        /** Whether we are currently in a tension pause (triggers flicker + groan). */
        public boolean tensionPause;
        /** Client tick at which this state was last updated. */
        public long   lastUpdateTick;
        /** Breach mode for anticipatory colour cue at high stages. */
        public BlockPhysicsData.PressureBreachMode breachMode;
    }

    // ── Packet handlers ───────────────────────────────────────────────────────

    public static void onDeformPacket(WorldPressureNetwork.DeformPacket p) {
        var ds = STATES.computeIfAbsent(p.pos().immutable(), k -> new DeformState());
        ds.deformAmount      = p.deformAmount();
        ds.inflationFraction = p.inflationFraction();
        ds.stage             = p.stage();
        ds.deltaMbar         = p.deltaMbar();
        ds.tensionPause      = false;
        ds.lastUpdateTick    = currentTick();
    }

    public static void onStageAdvance(WorldPressureNetwork.StageAdvancePacket p) {
        var ds = STATES.computeIfAbsent(p.pos().immutable(), k -> new DeformState());
        ds.stage        = p.newStage();
        ds.deltaMbar    = p.deltaMbar();
        ds.breachMode   = p.breachMode();
        ds.tensionPause = false;
        ds.lastUpdateTick = currentTick();
        playStageSoundAtPos(p.pos(), p.newStage(), p.deltaMbar());
    }

    public static void onTensionPause(WorldPressureNetwork.TensionPausePacket p) {
        var ds = STATES.computeIfAbsent(p.pos().immutable(), k -> new DeformState());
        ds.stage          = p.currentStage();
        ds.deltaMbar      = p.deltaMbar();
        ds.tensionPause   = true;
        ds.lastUpdateTick = currentTick();
        playTensionGroan(p.pos(), p.deltaMbar());
    }

    public static void onBreach(WorldPressureNetwork.BreachPacket p) {
        // Remove deform state — block is gone
        STATES.remove(p.pos().immutable());
        playBreachEffect(p.pos(), p.mode(), p.deltaMbar());
    }

    // ── Sound / effect helpers ────────────────────────────────────────────────

    private static void playStageSoundAtPos(BlockPos pos, int stage, float deltaMbar) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float pitch = 0.8f + stage * 0.1f;
        float vol   = Math.min(1.0f, 0.3f + Math.abs(deltaMbar) / 2000f);
        mc.level.playLocalSound(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                stage >= 2 ? SoundEvents.IRON_DOOR_CLOSE : SoundEvents.IRON_TRAPDOOR_CLOSE,
                SoundSource.BLOCKS, vol, pitch, false);
    }

    private static void playTensionGroan(BlockPos pos, float deltaMbar) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float vol = Math.min(0.6f, 0.1f + Math.abs(deltaMbar) / 3000f);
        mc.level.playLocalSound(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.IRON_GOLEM_DAMAGE,
                SoundSource.BLOCKS, vol, 0.4f, false);
    }

    private static void playBreachEffect(BlockPos pos,
                                          BlockPhysicsData.PressureBreachMode mode,
                                          float deltaMbar) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float vol = Math.min(1.0f, 0.5f + Math.abs(deltaMbar) / 1000f);
        var sound = switch (mode) {
            case SHATTER -> SoundEvents.GLASS_BREAK;
            case TEAR    -> SoundEvents.WOOL_BREAK;
            default      -> SoundEvents.METAL_BREAK;
        };
        mc.level.playLocalSound(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                sound, SoundSource.BLOCKS, vol, 0.9f, false);
    }

    private static long currentTick() {
        var mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }
}
