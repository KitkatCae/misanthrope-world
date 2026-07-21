package exp.CCnewmods.misanthrope_world.compat.coldsweat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Non-blocking chunk-residency check for Cold Sweat's biome temperature modifiers.
 *
 * <p>Root cause this exists to work around: {@code BiomeTempModifier.calculate} /
 * {@code CaveBiomeTempModifier.calculate} sample a small grid/cube of {@link BlockPos}
 * around the entity and call {@code Level.getBiomeManager().getBiome(pos)} for each one.
 * On the server thread, if any sampled position lands in a chunk that isn't resident yet
 * (e.g. right after world join, before spawn-area chunkgen has fully caught up under a
 * 667-mod chunk pipeline), that call falls through to
 * {@code ServerChunkCache.getChunk(...)} with {@code require=true}, which synchronously
 * {@code .join()}s a {@link java.util.concurrent.CompletableFuture} on the main thread.
 * If that chunk's own generation needs main-thread work to finish (common past a certain
 * generation stage), the main thread ends up blocked waiting on itself — not just slow,
 * a real hang. This is exactly the freeze the watchdog caught (single tick > 21 minutes).
 *
 * <p>Fix strategy: never let Cold Sweat's sampling loop reach an unloaded chunk in the
 * first place. {@link Level#hasChunk(int, int)} is a plain lookup against the server's
 * chunk map — no future join, no generation trigger — so checking every chunk in the
 * sample radius up front is always safe and cheap (at most a double-digit count of
 * hashmap lookups, and only run at Cold Sweat's own modifier tick rate, not every tick).
 *
 * <p>Assumption flagged for verification: {@code Level.hasChunk(int,int)} being a
 * non-blocking chunk-map lookup is standard 1.20.1 vanilla behavior, but this session
 * did not have the vanilla mapped jar available to {@code javap} and confirm directly
 * against bytecode — worth a quick independent check before relying on this in prod.
 */
public final class ColdSweatChunkSafety
{
    private ColdSweatChunkSafety() {}

    /**
     * Conservative safety radius, in blocks, covering the sample footprint of both
     * {@code BiomeTempModifier} (grid, samples=16, interval=10 -> radius ~20) and
     * {@code CaveBiomeTempModifier} (cube, sampleRoot=6, interval=6 -> radius ~18),
     * both confirmed against the shipped {@code WorldHelper.getPositionGrid} /
     * {@code getPositionCube} bytecode. Rounded up with margin rather than replicating
     * each modifier's exact geometry, so this stays correct even if either modifier's
     * sample count is reconfigured via Cold Sweat's own config.
     */
    public static final int SAFETY_RADIUS_BLOCKS = 32;

    /**
     * True only if every chunk within {@code blockRadius} blocks of {@code center}
     * (inclusive, square footprint) is already resident in {@code level}'s chunk map.
     * Never blocks, never triggers generation.
     */
    public static boolean isSampleAreaLoaded(Level level, BlockPos center, int blockRadius)
    {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int chunkRadius = (blockRadius >> 4) + 1;

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++)
        {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++)
            {
                if (!level.hasChunk(centerChunkX + dx, centerChunkZ + dz))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /** Convenience overload using {@link #SAFETY_RADIUS_BLOCKS}. */
    public static boolean isSampleAreaLoaded(Level level, BlockPos center)
    {
        return isSampleAreaLoaded(level, center, SAFETY_RADIUS_BLOCKS);
    }
}
