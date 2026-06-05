package exp.CCnewmods.misanthrope_world.temperature.overlay;

/**
 * Temperature-to-tint logic for the item temperature visual system.
 *
 * This class is now a pure utility — it no longer registers an ItemColor
 * against Forge's ItemColors system. Tinting is applied globally to every
 * item via {@link exp.CCnewmods.misanthrope_core.mixin.temperature.ItemTemperatureTintMixin},
 * which injects directly into ItemRenderer.renderQuadList at render time.
 *
 * ── Why the change ─────────────────────────────────────────────────────────
 * The old approach registered an ItemColor against every item in minecraft:,
 * tconstruct:, and misanthrope_core: namespaces during RegisterColorHandlersEvent.
 * This fired during the resource-reload pipeline and blocked the "Minecraft
 * Progress" bar for hours with 833+ mods loaded.
 *
 * The new approach has zero load-time cost: the mixin intercepts the color
 * local inside renderQuadList for every item render call and multiplies the
 * temperature tint on top. This covers every item in the game — including mods
 * we don't know about — without any registration.
 *
 * ── What this class still provides ───────────────────────────────────────
 * {@link #getFallbackTint(double)} — the generic heat/cold ramp used when
 * no thermal_material JSON exists for an item. This is also duplicated in
 * the mixin so it runs without a class boundary call — kept here for
 * external use (e.g. GUI temperature displays, tooltip colouring).
 *
 * {@link #multiplyArgb(int, int)} — shared ARGB multiply used by the mixin
 * and potentially other rendering code.
 */
public final class ItemTemperatureColorHandler {

    private ItemTemperatureColorHandler() {
    }

    /**
     * Returned when no tint applies — pure white = no colour change.
     */
    public static final int NO_TINT = 0xFFFFFFFF;

    /**
     * Generic heat/cold tint ramp. Used when no {@link ThermalMaterialData}
     * exists for an item. Covers the full realistic temperature spectrum from
     * deep-frozen to molten.
     *
     * @param celsius  item temperature in degrees Celsius
     * @return ARGB tint int, or 0 if temperature is in the neutral range
     */
    public static int getFallbackTint(double celsius) {
        if (celsius >= 1000) return 0xFFFFCC44; // white-orange (molten)
        else if (celsius >= 600) return 0xC0FF8800; // bright orange (red hot)
        else if (celsius >= 200) return 0x80FF6600; // orange-red (hot)
        else if (celsius >= 50) return 0x40FF4400; // faint red (warm)
        else if (celsius <= -50) return 0x8088CCFF; // strong frost blue (frozen)
        else if (celsius <= -10) return 0x4044AAFF; // faint blue (cold)
        return 0;
    }

    /**
     * Multiply two ARGB packed color ints channel-by-channel.
     *
     * <pre>
     *   result_channel = base_channel + (base*tint/255 - base_channel) * tint_alpha/255
     * </pre>
     * <p>
     * Tint alpha controls blend intensity: 0xFF = full saturation toward tint
     * color, 0x40 = subtle shimmer. Base alpha is always preserved so item
     * transparency is unaffected.
     *
     * @param base existing ARGB color (from ItemColors or 0xFFFFFFFF)
     * @param tint temperature tint ARGB
     * @return blended ARGB
     */
    public static int multiplyArgb(int base, int tint) {
        int bA = (base >> 24) & 0xFF;
        int bR = (base >> 16) & 0xFF;
        int bG = (base >> 8) & 0xFF;
        int bB = base & 0xFF;

        int tA = (tint >> 24) & 0xFF;
        int tR = (tint >> 16) & 0xFF;
        int tG = (tint >> 8) & 0xFF;
        int tB = tint & 0xFF;

        int mR = (bR * tR) / 255;
        int mG = (bG * tG) / 255;
        int mB = (bB * tB) / 255;

        int rR = bR + ((mR - bR) * tA / 255);
        int rG = bG + ((mG - bG) * tA / 255);
        int rB = bB + ((mB - bB) * tA / 255);

        return (bA << 24) | (rR << 16) | (rG << 8) | rB;
    }
}
