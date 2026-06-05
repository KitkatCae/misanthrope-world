package exp.CCnewmods.misanthrope_world.furnace.bellows;

import exp.CCnewmods.misanthrope_world.physics.bellows.IBellowsTarget;
import exp.CCnewmods.misanthrope_world.physics.bellows.IBellowsTarget.BellowsSourceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Detects bellows blow events from all supported sources and routes them
 * to adjacent {@link IBellowsTarget} block entities.
 * <p>
 * ── Detection strategy ────────────────────────────────────────────────────────
 * <p>
 * Supplementaries bellows:
 * BellowsBlock.use() calls setManualPress() on the tile.
 * BellowsBlock has POWER (IntegerProperty, 0-14) blockstate.
 * When POWER rises (neighborChanged fires on adjacent blocks), we detect it.
 * We inject into BellowsBlockTile.tick() via mixin to catch the exact moment
 * a blow cycle fires — see {@link BellowsBlockTileMixin}.
 * <p>
 * IE Blast Furnace Preheater:
 * BlastFurnacePreheaterBlockEntity extends SmartBlockEntity and ticks.
 * We detect it via neighbor block entity lookup — the preheater's facing
 * direction is stored in its block state (FACING property, confirmed from
 * bytecode: class extends DirectionalBlock). We check each tick whether
 * an IBellowsTarget sits in front of the preheater.
 * Intensity is derived from the preheater's energy input (via reflection on
 * the 'energyStorage' field — also confirmed from bytecode).
 * This handler fires from our own server tick via MServerTickHandler.
 * <p>
 * Create mechanical fan:
 * Create's AirCurrentHandler pushes entities and fills Create's own airflow
 * system. We detect Create fans via the Create API:
 * AirCurrent.getSource() — if it's a FanBlockEntity whose airflow hits an
 * IBellowsTarget, we call onBellowsBlow with CREATE_FAN type.
 * This is handled separately in BellowsFanIntegration (Create-specific compat).
 * <p>
 * ── Routing ───────────────────────────────────────────────────────────────────
 * When a blow is detected, we scan all 6 faces of the bellows for an adjacent
 * IBellowsTarget and call onBellowsBlow with the correct inbound Direction
 * (the face of the TARGET that the air enters through, which is opposite to
 * the face of the bellows that faces the target).
 */
@Mod.EventBusSubscriber(modid = "misanthrope_core", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BellowsIntegration {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/Bellows");

    private static final boolean SUPP_LOADED = ModList.get().isLoaded("supplementaries");
    private static final boolean IE_LOADED = ModList.get().isLoaded("immersiveengineering");

    // ── Public entry points (called from mixins / tick handlers) ──────────────

    /**
     * Called by {@link BellowsBlockTileMixin} when a Supplementaries bellows
     * fires a blow cycle. The bellows tile position and its FACING direction
     * are passed in.
     *
     * @param level         server level
     * @param bellowsPos    position of the BellowsBlockTile
     * @param bellowsFacing the direction the bellows face (FACING blockstate)
     * @param power         the current POWER value (0-14) as intensity proxy
     */
    public static void onSupplementariesBlow(Level level, BlockPos bellowsPos,
                                             Direction bellowsFacing, int power) {
        if (!SUPP_LOADED) return;
        float intensity = Math.min(0.75f, power / 14.0f * 0.75f);
        notifyTarget(level, bellowsPos, bellowsFacing, intensity,
                BellowsSourceType.SUPPLEMENTARIES_BELLOWS);
    }

    /**
     * Called from MServerTickHandler every server tick to poll IE preheaters.
     * IE preheaters are found by scanning level block entities — this is
     * acceptable because we use the existing preheater's own ticker to avoid
     * adding a new scan.
     * <p>
     * Alternatively: call this from the preheater BE's own tick via mixin.
     * That approach is cleaner; implement if needed.
     */
    public static void onIEPreheaterTick(Level level, BlockPos preheaterPos,
                                         Direction preheaterFacing,
                                         float normalizedEnergy) {
        if (!IE_LOADED) return;
        float intensity = Math.min(0.80f, normalizedEnergy * 0.80f);
        notifyTarget(level, preheaterPos, preheaterFacing, intensity,
                BellowsSourceType.IE_PREHEATER);
    }

    /**
     * Called by Create fan integration when a fan airflow hits an IBellowsTarget.
     */
    public static void onCreateFanBlow(Level level, BlockPos targetPos,
                                       Direction inboundFace, float intensity) {
        BlockEntity be = level.getBlockEntity(targetPos);
        if (be instanceof IBellowsTarget target) {
            if (target.acceptsBellowsFromFace(inboundFace)) {
                target.onBellowsBlow(inboundFace, intensity,
                        BellowsSourceType.CREATE_FAN);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Find the IBellowsTarget directly in front of the bellows and notify it.
     *
     * @param bellowsPos    position of the bellows device
     * @param bellowsFacing the direction the bellows FACE (air exits in this direction)
     * @param intensity     blow intensity 0.0–1.0
     * @param sourceType    type of bellows
     */
    private static void notifyTarget(Level level, BlockPos bellowsPos,
                                     Direction bellowsFacing, float intensity,
                                     BellowsSourceType sourceType) {
        // The target is one block in the direction the bellows face
        BlockPos targetPos = bellowsPos.relative(bellowsFacing);

        BlockEntity be = level.getBlockEntity(targetPos);
        if (!(be instanceof IBellowsTarget target)) return;

        // The air enters the target through the face opposite to bellowsFacing
        Direction inboundFace = bellowsFacing.getOpposite();
        if (!target.acceptsBellowsFromFace(inboundFace)) return;

        target.onBellowsBlow(inboundFace, intensity, sourceType);
    }
}
