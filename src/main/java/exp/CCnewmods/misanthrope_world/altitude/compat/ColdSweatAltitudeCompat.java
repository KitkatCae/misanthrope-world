package exp.CCnewmods.misanthrope_world.altitude.compat;

import com.momosoftworks.coldsweat.api.temperature.modifier.SimpleTempModifier;
import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.api.util.placement.Matcher;
import com.momosoftworks.coldsweat.api.util.placement.Placement;
import exp.CCnewmods.misanthrope_world.config.AltitudeBandConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Applies and removes ColdSweat {@link SimpleTempModifier}s representing the active
 * altitude band's temperature contribution to the player's WORLD trait.
 *
 * Confirmed API from bytecode:
 *  - SimpleTempModifier(double, Operation)       — constructor
 *  - SimpleTempModifier.setTemperature(double)   — update in-place
 *  - SimpleTempModifier.setOperation(Operation)  — update in-place
 *  - TempModifier.tickRate(int)                  — sets update rate
 *  - Temperature.getModifier(entity, Trait, Predicate) → Optional<TempModifier>
 *  - Temperature.addModifier(entity, modifier, Trait, Placement) → boolean
 *  - Temperature.removeModifiers(entity, Trait, Predicate)
 *  - Temperature.updateModifiers(entity)
 *  - Matcher enum values: IGNORE, SAME_CLASS, SUBCLASS, EQUALS  (no BY_CLASS)
 *  - Placement.LAST.noDuplicates(Matcher) → Placement
 */
public final class ColdSweatAltitudeCompat {

    public static final ColdSweatAltitudeCompat INSTANCE = new ColdSweatAltitudeCompat();
    private ColdSweatAltitudeCompat() {}

    private static final String ALTITUDE_MARKER  = "ColdSweatAltitude";
    private static final String BAND_ID_KEY      = "BandId";
    private static final int    MODIFIER_TICK_RATE = 20;

    // SAME_CLASS: matches any modifier that is exactly SimpleTempModifier (not subclasses)
    private static final Placement ALTITUDE_PLACEMENT =
            Placement.LAST.noDuplicates(Matcher.SAME_CLASS);

    // ── Apply / remove ─────────────────────────────────────────────────────────

    public void applyAltitudeModifier(ServerPlayer player, String bandId,
                                       double modifier,
                                       AltitudeBandConfig.ModifierMode mode) {
        if (isNeutral(modifier, mode)) {
            removeAltitudeModifier(player);
            return;
        }

        SimpleTempModifier.Operation csOp = (mode == AltitudeBandConfig.ModifierMode.MULTIPLY)
                ? SimpleTempModifier.Operation.MULTIPLY
                : SimpleTempModifier.Operation.ADD;

        // Reuse an existing altitude modifier rather than stacking
        Optional<TempModifier> existing = Temperature.getModifier(
                player, Temperature.Trait.WORLD, this::isAltitudeModifier);

        if (existing.isPresent() && existing.get() instanceof SimpleTempModifier stm) {
            stm.setTemperature(modifier);
            stm.setOperation(csOp);
            stm.tickRate(MODIFIER_TICK_RATE);
        } else {
            SimpleTempModifier mod = new SimpleTempModifier(modifier, csOp);
            mod.tickRate(MODIFIER_TICK_RATE);
            CompoundTag nbt = mod.getNBT();
            nbt.putBoolean(ALTITUDE_MARKER, true);
            nbt.putString(BAND_ID_KEY, bandId);
            Temperature.addModifier(player, mod, Temperature.Trait.WORLD, ALTITUDE_PLACEMENT);
        }

        Temperature.updateModifiers(player);
    }

    public void removeAltitudeModifier(ServerPlayer player) {
        Temperature.removeModifiers(player, Temperature.Trait.WORLD, this::isAltitudeModifier);
        Temperature.updateModifiers(player);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isAltitudeModifier(TempModifier mod) {
        return mod instanceof SimpleTempModifier && mod.getNBT().getBoolean(ALTITUDE_MARKER);
    }

    private boolean isNeutral(double modifier, AltitudeBandConfig.ModifierMode mode) {
        return switch (mode) {
            case ADD      -> modifier == 0.0;
            case MULTIPLY -> modifier == 1.0;
        };
    }
}
