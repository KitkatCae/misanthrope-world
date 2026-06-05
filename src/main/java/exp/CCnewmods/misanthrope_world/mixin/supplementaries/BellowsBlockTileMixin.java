package exp.CCnewmods.misanthrope_world.mixin.supplementaries;

import exp.CCnewmods.misanthrope_world.furnace.bellows.BellowsIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the Supplementaries BellowsBlockTile tick to detect blow events.
 * <p>
 * ── Target ────────────────────────────────────────────────────────────────────
 * net.mehvahdjukaar.supplementaries.common.block.tiles.BellowsBlockTile
 * Static tick method: tick(Level, BlockPos, BlockState, BellowsBlockTile)
 * <p>
 * ── Approach ──────────────────────────────────────────────────────────────────
 * The bellows tile has a `MAX_COMPRESSION` float field (confirmed from bytecode)
 * and tracks a compression cycle internally. The POWER blockstate (0-14) reflects
 * the current output.
 * <p>
 * Rather than trying to read internal compression state, we:
 * 1. Inject at HEAD of the tick method
 * 2. Read the current POWER value from the blockstate
 * 3. Track the previous POWER in a thread-local (or just always call if POWER > 0)
 * 4. When POWER > 0 (bellows is blowing), call BellowsIntegration with the
 * FACING direction from the blockstate
 * <p>
 * This fires every tick that the bellows has POWER > 0, which is correct —
 * IBellowsTarget.onBellowsBlow is designed to be called repeatedly and applies
 * intensity each tick. The intensity decays on the target side between calls.
 * <p>
 * ── Registration ──────────────────────────────────────────────────────────────
 * Add to misanthrope_core_late.mixins.json (supplementaries loads late):
 * "furnace.BellowsBlockTileMixin"
 * <p>
 * The mixin config entry:
 * {
 * "package": "exp.CCnewmods.misanthrope_core.mixin",
 * "required": false,
 * "refmap": "...",
 * "compatibilityLevel": "JAVA_17",
 * "mixins": [],
 * "client": [],
 * "server": []
 * }
 * <p>
 * Since supplementaries is a soft dep, this mixin should be in the LATE config
 * and use MixinPlugin to skip if supplementaries is absent.
 */
@Mixin(
        targets = "net.mehvahdjukaar.supplementaries.common.block.tiles.BellowsBlockTile",
        remap = false
)
public class BellowsBlockTileMixin {

    /**
     * Inject into the static tick method at HEAD.
     * <p>
     * Supplementaries tick signature (confirmed from bytecode):
     * tick(Level, BlockPos, BlockState, BellowsBlockTile)
     * <p>
     * We read POWER from the blockstate (IntegerProperty named "POWER", 0-14).
     * FACING is also a blockstate property (DirectionProperty named "FACING").
     * Both were confirmed in the BellowsBlock constant pool.
     */
    @Inject(
            method = "tick",
            at = @At("HEAD"),
            remap = false
    )
    private static void misanthrope_detectBellowsBlow(
            Level level,
            BlockPos pos,
            BlockState state,
            @Coerce Object tile,
            CallbackInfo ci) {
        if (level.isClientSide()) return;
        try {
            int power = 0;
            Direction facing = Direction.NORTH;
            for (var prop : state.getProperties()) {
                if ("power".equals(prop.getName())) {   // ← lowercase, not "POWER"
                    Object val = state.getValue(prop);
                    if (val instanceof Integer i) power = i;
                }
                if ("facing".equals(prop.getName())) {  // ← lowercase, not "FACING"
                    Object val = state.getValue(prop);
                    if (val instanceof Direction d) facing = d;
                }
            }
            if (power > 0) {
                BellowsIntegration.onSupplementariesBlow(level, pos, facing, power);
            }
        } catch (Exception ignored) {
        }
    }
}