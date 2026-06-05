package exp.CCnewmods.misanthrope_world.physics.bellows;

import net.minecraft.core.Direction;

/**
 * Interface for block entities that can receive bellows air input.
 * <p>
 * Implemented by every furnace/bloomery block entity that benefits from bellows.
 * The bellows mixin calls {@link #onBellowsBlow} when it detects a blow event
 * from any adjacent bellows source.
 * <p>
 * ── Bellows sources ───────────────────────────────────────────────────────────
 * - Supplementaries bellows (manual or redstone-powered)
 * - IE Blast Furnace Preheater (repurposed as air supply)
 * - Create mechanical fan (detected separately via Create's airflow system)
 * <p>
 * ── Intensity values ──────────────────────────────────────────────────────────
 * 0.0  — no air (bellows not firing)
 * 0.25 — slow manual bellows pump
 * 0.5  — medium (IE preheater at low power, manual bellows at moderate rate)
 * 0.75 — strong (fast manual bellows, IE preheater at full power)
 * 1.0  — maximum (Create mechanical fan, electrical blower)
 * <p>
 * Intensity decays back to 0 within ~40 ticks if no new blow is received.
 */
public interface IBellowsTarget {

    /**
     * Called when a bellows (or equivalent air supply) produces a blow
     * directed toward this block entity's face.
     *
     * @param face       which face of this block the air is entering from
     * @param intensity  air intensity, 0.0–1.0
     * @param sourceType what kind of bellows produced the blow
     */
    void onBellowsBlow(Direction face, float intensity, BellowsSourceType sourceType);

    /**
     * Current bellows intensity this block entity is experiencing.
     * Used by the furnace tick to modify combustion.
     *
     * @return 0.0–1.0 intensity, decaying each tick without fresh input
     */
    float getCurrentBellowsIntensity();

    /**
     * Whether this furnace accepts bellows air from the given face.
     * For bloomeries: only the tuyère face is valid.
     * For open forges: any horizontal face is valid.
     */
    default boolean acceptsBellowsFromFace(Direction face) {
        return true;
    }

    /**
     * The kind of air supply device producing the blow.
     * Affects the max intensity and oxygen content of the air delivered.
     */
    enum BellowsSourceType {
        /**
         * Supplementaries manual or redstone-powered bellows
         */
        SUPPLEMENTARIES_BELLOWS,
        /**
         * IE Blast Furnace Preheater (repurposed as air blower)
         */
        IE_PREHEATER,
        /**
         * Create mechanical fan
         */
        CREATE_FAN,
        /**
         * Our own electric blower (future tier)
         */
        ELECTRIC_BLOWER;

        /**
         * Maximum intensity this source can deliver.
         */
        public float maxIntensity() {
            return switch (this) {
                case SUPPLEMENTARIES_BELLOWS -> 0.75f;
                case IE_PREHEATER -> 0.80f;
                case CREATE_FAN -> 1.0f;
                case ELECTRIC_BLOWER -> 1.0f;
            };
        }

        /**
         * Oxygen enrichment factor — how much extra O₂ this source injects
         * relative to standard air. Standard air = 1.0.
         * IE preheater pre-heats air but doesn't enrich it; create fan same.
         * An electric blower with O₂ input could go above 1.0 (future feature).
         */
        public float oxygenEnrichmentFactor() {
            return 1.0f; // all current sources blow normal air
        }
    }
}
