package exp.CCnewmods.misanthrope_world.physics.structural.grid;

import javax.annotation.Nullable;

/**
 * One 16³ cell of the persistent structural stress grid. Mirrors
 * {@code EnvironmentSection}'s internal shape (MGE's gas grid) — dense local
 * arrays, lazily allocated, a sentinel value for "never written," local index
 * packing — but with exactly two fixed channels instead of gas's sparse
 * per-type map, since there are only ever two stress channels here.
 * <p>
 * See {@code StressGrid_Design_v1.md} for the full design. This class is
 * Stage 1: just the data structure. No simulation writes into it yet, and no
 * invalidation logic reads out of it yet — both come in later stages.
 */
public final class StressSection {

    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE; // 4096

    /** Sentinel for "never written" — mirrors EnvironmentSection.AMBIENT_SENTINEL. */
    public static final float UNSET = Float.NaN;

    @Nullable private float[] compressive = null;
    @Nullable private float[] tensile = null;

    /** Set by any write; consumed by whatever sync/render path needs to know this section changed. */
    public boolean dirty = false;

    /**
     * Game time this section was last read or written. Used by
     * {@link StressGrid}'s idle-sweep eviction — a backstop for
     * environments with no reliable unload signal (see StressGrid's doc).
     */
    public long lastTouchedGameTime = 0;

    /** Packs local (0-15) coordinates into a single 0-4095 array index. */
    public static int index(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    public static boolean isSet(float value) {
        return !Float.isNaN(value);
    }

    // ── Compressive channel ─────────────────────────────────────────────────

    public float getCompressive(int lx, int ly, int lz) {
        if (compressive == null) return UNSET;
        return compressive[index(lx, ly, lz)];
    }

    public void setCompressive(int lx, int ly, int lz, float value) {
        if (compressive == null) {
            compressive = newSentinelArray();
        }
        compressive[index(lx, ly, lz)] = value;
        dirty = true;
    }

    public void clearCompressive(int lx, int ly, int lz) {
        if (compressive == null) return;
        compressive[index(lx, ly, lz)] = UNSET;
        dirty = true;
    }

    // ── Tensile channel ──────────────────────────────────────────────────────

    public float getTensile(int lx, int ly, int lz) {
        if (tensile == null) return UNSET;
        return tensile[index(lx, ly, lz)];
    }

    public void setTensile(int lx, int ly, int lz, float value) {
        if (tensile == null) {
            tensile = newSentinelArray();
        }
        tensile[index(lx, ly, lz)] = value;
        dirty = true;
    }

    public void clearTensile(int lx, int ly, int lz) {
        if (tensile == null) return;
        tensile[index(lx, ly, lz)] = UNSET;
        dirty = true;
    }

    // ── Bulk state ────────────────────────────────────────────────────────────

    /** True if this section has never had either channel written to — safe to evict/drop. */
    public boolean isEmpty() {
        return compressive == null && tensile == null;
    }

    private static float[] newSentinelArray() {
        float[] arr = new float[VOLUME];
        java.util.Arrays.fill(arr, UNSET);
        return arr;
    }
}
