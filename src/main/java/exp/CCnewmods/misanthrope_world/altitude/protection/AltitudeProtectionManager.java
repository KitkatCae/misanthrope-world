package exp.CCnewmods.misanthrope_world.altitude.protection;

import exp.CCnewmods.misanthrope_world.altitude.temperature.AltitudeBand;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Calculates how much of the altitude temperature modifier is cancelled by
 * protection items the player is wearing (armor slots checked against the
 * band's configured item tag).
 */
public final class AltitudeProtectionManager {

    public static final AltitudeProtectionManager INSTANCE = new AltitudeProtectionManager();
    private AltitudeProtectionManager() {}

    /**
     * Returns a multiplier in [0, 1]: 0 = no protection, 1 = full cancellation.
     */
    public double protectionMultiplier(Player player, AltitudeBand band) {
        TagKey<Item> tag = band.protectionTag();
        if (tag == null || band.requiredPieces() <= 0) return 0.0;

        int protectedPieces = 0;
        for (ItemStack armor : player.getInventory().armor) {
            if (!armor.isEmpty() && armor.is(tag)) protectedPieces++;
        }

        if (protectedPieces < band.requiredPieces()) return 0.0;
        return Mth.clamp(protectedPieces * band.protectionReductionPerPiece(), 0.0, 1.0);
    }
}
