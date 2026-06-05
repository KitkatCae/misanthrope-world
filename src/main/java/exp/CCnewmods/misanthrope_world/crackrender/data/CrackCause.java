package exp.CCnewmods.misanthrope_world.crackrender.data;

/**
 * The physical cause of a crack, which drives both visual variation and
 * gameplay behaviour.
 * <p>
 * ── Visual variation ──────────────────────────────────────────────────────────
 * THERMAL:    narrow, branching, sharp-edged. Orange/red glow at level 3.
 * STRUCTURAL: wide, irregular, rough edges. Dark interior. Common in caves.
 * IMPACT:     radiating from a point, short branches. Spiderweb pattern.
 * EROSION:    rounded, flowing lines. Bluish-dark interior. Water-worn look.
 * MAGICAL:    thin, geometric. Purple/cyan inner glow regardless of level.
 * <p>
 * ── Propagation behaviour ─────────────────────────────────────────────────────
 * troughDepth:    how deep the rendered trough goes into the block (0–1 units).
 * Level 3 always punches through regardless.
 * healRate:       ticks between healing steps when the source is removed.
 * 0 = never heals (IMPACT, MAGICAL).
 * crossBlockBias: how strongly the vein prefers to cross block boundaries
 * rather than staying on one face. Higher = longer veins.
 */
public enum CrackCause {

    THERMAL(
            0.06f,   // trough depth
            6000,    // heal rate (5 min per step)
            0.7f     // cross-block bias
    ),
    STRUCTURAL(
            0.10f,
            0,       // structural cracks don't heal — rebuild required
            0.85f
    ),
    IMPACT(
            0.08f,
            0,       // never heals
            0.4f     // short veins radiating from impact point
    ),
    EROSION(
            0.05f,
            12000,   // slow heal (10 min)
            0.6f
    ),
    MAGICAL(
            0.04f,
            0,       // never heals
            0.5f
    );

    /**
     * Depth the trough geometry recesses into the block face (in block units).
     */
    public final float troughDepth;

    /**
     * Ticks between each heal step when the cause is no longer active.
     * 0 means the crack is permanent until the block is replaced.
     */
    public final int healRate;

    /**
     * Bias [0,1] for the vein path crossing into adjacent blocks.
     * Higher = more likely to jump faces and propagate into neighbours.
     */
    public final float crossBlockBias;

    CrackCause(float troughDepth, int healRate, float crossBlockBias) {
        this.troughDepth = troughDepth;
        this.healRate = healRate;
        this.crossBlockBias = crossBlockBias;
    }

    /**
     * Whether this cause produces an inner glow at level 3.
     */
    public boolean hasGlow() {
        return this == THERMAL || this == MAGICAL;
    }

    /**
     * Inner glow colour as packed ARGB (used by the mesh injector for
     * the level-3 void interior faces).
     * Returns 0 for causes with no glow.
     */
    public int glowColor() {
        return switch (this) {
            case THERMAL -> 0xFFD04010; // orange-red
            case MAGICAL -> 0xFF8030FF; // purple
            default -> 0x00000000;
        };
    }

    /**
     * Line width base for the seeded vein path (in block UV units).
     */
    public float baseLineWidth(int level) {
        float base = switch (this) {
            case STRUCTURAL -> 0.018f;
            case IMPACT -> 0.014f;
            case EROSION -> 0.010f;
            default -> 0.008f;
        };
        return base * (1f + (level - 1) * 0.6f);
    }
}
