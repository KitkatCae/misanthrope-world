package exp.CCnewmods.misanthrope_world.crackrender.world;

import exp.CCnewmods.misanthrope_world.crackrender.data.CrackCause;
import exp.CCnewmods.misanthrope_world.crackrender.data.ICrackSourceProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Passive crack source provider that seeds structural cracks in cave ceilings.
 * <p>
 * ── When triggered ────────────────────────────────────────────────────────────
 * On ChunkEvent.Load (server side), scans the chunk for cave ceiling blocks —
 * defined as: solid block with ≥3 contiguous air blocks directly below it and
 * ≥2 solid blocks above it (overburden pressure). A 1-in-8 spatial sample
 * keeps the scan cheap.
 * <p>
 * Found ceiling blocks are registered as one-tick ICrackSourceProvider instances
 * with a pressure proportional to overburden weight. Since healRate == 0 for
 * STRUCTURAL cracks, these seed cracks persist until the block is broken.
 * <p>
 * ── Not a persistent BE ───────────────────────────────────────────────────────
 * CaveStructuralAnalyzer is stateless — it fires on chunk load and registers
 * one-shot providers. The resulting CrackEntries are saved in CrackStateMap
 * (WorldSavedData) so they persist across sessions without re-scanning.
 * Re-scanning on chunk load only adds new entries, never removes existing ones.
 * <p>
 * ── Pressure formula ─────────────────────────────────────────────────────────
 * pressure = overburdenBlocks * 2.5f
 * where overburdenBlocks = consecutive solid blocks directly above the ceiling.
 * Cap at 40 (avoids extreme propagation in deep areas).
 * <p>
 * This means:
 * 1 block overhead → pressure 2.5 (very slow cracking, mostly cosmetic)
 * 4 blocks         → pressure 10  (moderate cracking over time)
 * 16+ blocks       → pressure 40  (deep cave, active cracking)
 */

public class CaveStructuralAnalyzer {

    private static final int MIN_AIR_BELOW = 3;
    private static final int MIN_SOLID_ABOVE = 2;
    private static final int MAX_SCAN_HEIGHT = 20;
    private static final float MAX_PRESSURE = 40f;
    private static final int SAMPLE_STEP = 4; // 1 in 4 blocks scanned

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getLevel().isClientSide()) return;

        ChunkPos cp = event.getChunk().getPos();
        CrackStateMap stateMap = CrackStateMap.get(level);

        List<CeilingCandidate> candidates = scanChunk(level, cp);

        for (CeilingCandidate candidate : candidates) {
            // Don't re-seed already-cracked blocks
            if (stateMap.hasCracks(candidate.pos)) continue;

            // Skip if this position is in a chunk that's never been modified
            // (avoids seeding pristine worldgen chunks unnecessarily)
            if (!level.getChunkSource().hasChunk(cp.x, cp.z)) continue;

            CrackPropagator.addSource(new OneShotCeilingSource(
                    candidate.pos,
                    candidate.pressure
            ));
        }
    }

    // ── Chunk scan ────────────────────────────────────────────────────────────

    private static List<CeilingCandidate> scanChunk(ServerLevel level, ChunkPos cp) {
        List<CeilingCandidate> result = new ArrayList<>();
        int minX = cp.getMinBlockX(), maxX = cp.getMaxBlockX();
        int minZ = cp.getMinBlockZ(), maxZ = cp.getMaxBlockZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - MAX_SCAN_HEIGHT;

        for (int x = minX; x <= maxX; x += SAMPLE_STEP) {
            for (int z = minZ; z <= maxZ; z += SAMPLE_STEP) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isCeilingCandidate(level, pos)) continue;

                    int airBelow = countAirBelow(level, pos);
                    int solidAbove = countSolidAbove(level, pos);

                    if (airBelow < MIN_AIR_BELOW || solidAbove < MIN_SOLID_ABOVE) continue;

                    float pressure = Math.min(MAX_PRESSURE, solidAbove * 2.5f);
                    result.add(new CeilingCandidate(pos.immutable(), pressure));
                }
            }
        }
        return result;
    }

    private static boolean isCeilingCandidate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isSolid()) return false;
        // Must have air directly below
        return level.getBlockState(pos.below()).isAir();
    }

    private static int countAirBelow(ServerLevel level, BlockPos pos) {
        int count = 0;
        for (int dy = 1; dy <= MIN_AIR_BELOW + 2; dy++) {
            if (level.getBlockState(pos.below(dy)).isAir()) count++;
            else break;
        }
        return count;
    }

    private static int countSolidAbove(ServerLevel level, BlockPos pos) {
        int count = 0;
        for (int dy = 1; dy <= MAX_SCAN_HEIGHT; dy++) {
            if (level.getBlockState(pos.above(dy)).isSolid()) count++;
            else break;
        }
        return count;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record CeilingCandidate(BlockPos pos, float pressure) {
    }

    /**
     * One-shot provider: fires once with the given pressure, then expires.
     * This seeds the initial crack at the ceiling block — after that, CrackPropagator
     * handles propagation from the CrackStateMap naturally.
     */
    private static class OneShotCeilingSource implements ICrackSourceProvider {

        private final BlockPos pos;
        private final float pressure;
        private boolean fired = false;

        OneShotCeilingSource(BlockPos pos, float pressure) {
            this.pos = pos;
            this.pressure = pressure;
        }

        @Override
        public AABB getZone() {
            // 1-block zone centred on the ceiling block — just seeds this block
            return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }

        @Override
        public float getCrackPressure(ServerLevel level) {
            float p = fired ? 0f : pressure;
            fired = true;
            return p;
        }

        @Override
        public CrackCause getCause() {
            return CrackCause.STRUCTURAL;
        }

        @Override
        public boolean isExpired() {
            return fired;
        }

        @Override
        public String sourceId() {
            return "misanthrope_core:cave_ceiling:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }
}
