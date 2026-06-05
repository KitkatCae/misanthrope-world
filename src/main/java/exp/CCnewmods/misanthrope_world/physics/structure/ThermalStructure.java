package exp.CCnewmods.misanthrope_world.physics.structure;

import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsData;
import exp.CCnewmods.misanthrope_world.physics.BlockPhysicsRegistry;
import exp.CCnewmods.misanthrope_world.physics.DynamicHeatSourceRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Describes a thermal zone — an enclosed or semi-enclosed space containing one
 * or more heat sources.  Built by scanning the world from a heat source position
 * using a BFS flood fill through permeable blocks, stopped by airtight walls or
 * the maximum search radius.
 * <p>
 * ── What it detects ───────────────────────────────────────────────────────────
 * <p>
 * Interior cells  — air/permeable block positions enclosed by walls.
 * Wall cells      — solid blocks adjacent to the interior, checked up to
 * MAX_WALL_DEPTH deep for insulation stacking.
 * Gap cells       — positions where the BFS hit the radius limit or found a
 * non-airtight boundary — openings that leak heat.
 * Chimney shaft   — a vertical column of permeable blocks above the topmost
 * interior cell that opens to sky.
 * Heat sources    — all heat-source blocks found inside or directly adjacent
 * to the interior.
 * <p>
 * ── Shape agnostic ────────────────────────────────────────────────────────────
 * Works for any shape: box, L-shape, dome, cylinder approximation, open hearth,
 * sealed kiln, rotary kiln shaft.  No hardcoded geometry.
 * <p>
 * ── Dimension-aware radius ────────────────────────────────────────────────────
 * The default maximum flood-fill radius is {@link #DEFAULT_MAX_RADIUS} (64 blocks).
 * Specific dimensions can override this — e.g. space dimensions set it to -1
 * (unlimited, stop only at walls or vacuum).  Injected via
 * {@link #scan(Level, BlockPos, int)} with the dimension's configured radius.
 * <p>
 * ── Performance ───────────────────────────────────────────────────────────────
 * BFS is bounded by the radius and capped at {@link #MAX_INTERIOR_CELLS} cells.
 * Above that cap the structure is treated as "open" (too large to simulate as
 * an enclosed zone).  Typical small furnaces visit ~10–100 cells.  A large
 * blast furnace might visit ~5000.  A 64-block dome: ~130,000 — the cap kicks in.
 * <p>
 * Re-scan is triggered by block changes adjacent to the interior (handled by
 * callers — BloomeryBlockEntity, ThermalField, etc.).
 */
public final class ThermalStructure {

    // ── Configuration ─────────────────────────────────────────────────────────

    public static final int DEFAULT_MAX_RADIUS = 64;
    public static final int MAX_INTERIOR_CELLS = 32768;
    public static final int MAX_WALL_DEPTH = 5;

    // ── Structure data ────────────────────────────────────────────────────────

    public final Set<BlockPos> interior;
    public final Set<BlockPos> wallBlocks;
    public final List<WallFace> wallFaces;
    public final List<GapFace> gapFaces;
    public final List<HeatSourceEntry> heatSources;
    public final List<BlockPos> chimneyShaft;
    public final boolean isSealed;
    public final boolean isOpen;
    public final int volume;
    public final double avgWallInsulationR;
    public final double totalWallThermalMass;

    // ── Nested types ──────────────────────────────────────────────────────────

    public record WallFace(
            BlockPos interiorPos,
            Direction direction,
            double totalR,
            double thermalMass
    ) {
        public double conductance() {
            return totalR <= 0 ? Double.MAX_VALUE : 1.0 / totalR;
        }
    }

    public record GapFace(BlockPos interiorPos, Direction direction) {
    }

    /**
     * A heat source block entry.
     * {@link #emission} is non-null for static sources defined in
     * {@code material_properties/}. For dynamic BE sources (crucibles, etc.)
     * it may be null — the BE manages its own output and registers via
     * {@link DynamicHeatSourceRegistry}.
     */
    public record HeatSourceEntry(
            BlockPos pos,
            BlockState state,
            @Nullable BlockPhysicsData.HeatEmission emission
    ) {
        /**
         * True if this source is active given block state and O₂.
         */
        public boolean isActive(float o2Mbar) {
            return emission == null || emission.isActive(state, o2Mbar);
        }

        /**
         * Effective watts output. Returns 0 for dynamic sources (BE manages output).
         */
        public double effectiveWatts(float o2Mbar) {
            if (emission == null) return 0.0;
            return emission.effectiveWatts(o2Mbar);
        }

        public boolean isInduction() {
            return emission != null && emission.isInduction();
        }

        public boolean requiresOxygen() {
            return emission != null && emission.requiresOxygen();
        }

        public double peakCelsius() {
            return emission != null ? emission.peakCelsius() : Double.NaN;
        }

        public double wattsPerBlock() {
            return emission != null ? emission.wattsPerBlock() : 0.0;
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    private ThermalStructure(
            Set<BlockPos> interior,
            Set<BlockPos> wallBlocks,
            List<WallFace> wallFaces,
            List<GapFace> gapFaces,
            List<HeatSourceEntry> heatSources,
            List<BlockPos> chimneyShaft,
            boolean isSealed,
            boolean isOpen
    ) {
        this.interior = Collections.unmodifiableSet(interior);
        this.wallBlocks = Collections.unmodifiableSet(wallBlocks);
        this.wallFaces = Collections.unmodifiableList(wallFaces);
        this.gapFaces = Collections.unmodifiableList(gapFaces);
        this.heatSources = Collections.unmodifiableList(heatSources);
        this.chimneyShaft = Collections.unmodifiableList(chimneyShaft);
        this.isSealed = isSealed;
        this.isOpen = isOpen;
        this.volume        = interior.size();

        double totalR = 0, totalMass = 0;
        for (WallFace wf : wallFaces) {
            totalR    += wf.totalR();
            totalMass += wf.thermalMass();
        }
        this.avgWallInsulationR = wallFaces.isEmpty() ? 0 : totalR / wallFaces.size();
        this.totalWallThermalMass = totalMass;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static ThermalStructure scan(Level level, BlockPos origin, int maxRadius) {
        Set<BlockPos> interior = new LinkedHashSet<>();
        Set<BlockPos> wallSet = new LinkedHashSet<>();
        List<WallFace> wallFaces = new ArrayList<>();
        List<GapFace> gapFaces = new ArrayList<>();
        List<HeatSourceEntry> sources = new ArrayList<>();

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<Long>       visited = new HashSet<>();

        // Determine whether origin is interior or a heat source adjacent to interior
        BlockState originState = level.getBlockState(origin);
        BlockPhysicsData originData = BlockPhysicsRegistry.get(originState);

        if (originData.emission != null || originState.isAir() || !originData.isAirtight) {
            queue.add(origin);
            visited.add(origin.asLong());
        } else {
            for (Direction d : Direction.values()) {
                BlockPos adj = origin.relative(d);
                if (!BlockPhysicsRegistry.get(level.getBlockState(adj)).isAirtight) {
                    queue.add(adj);
                    visited.add(adj.asLong());
                }
            }
        }

        boolean hitCap = false;

        // ── BFS flood fill ────────────────────────────────────────────────────
        while (!queue.isEmpty()) {
            if (interior.size() >= MAX_INTERIOR_CELLS) {
                hitCap = true;
                break;
            }

            BlockPos pos   = queue.poll();
            BlockState state = level.getBlockState(pos);
            BlockPhysicsData data = BlockPhysicsRegistry.get(state);

            if (data.isAirtight && data.emission == null) continue; // wall — skip

            interior.add(pos.immutable());

            // Record if it's a static heat source
            if (data.emission != null) {
                sources.add(new HeatSourceEntry(pos.immutable(), state, data.emission));
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbour = pos.relative(dir);
                if (visited.contains(neighbour.asLong())) continue;
                visited.add(neighbour.asLong());

                if (maxRadius >= 0) {
                    int dist = Math.abs(neighbour.getX() - origin.getX())
                            + Math.abs(neighbour.getY() - origin.getY())
                            + Math.abs(neighbour.getZ() - origin.getZ());
                    if (dist > maxRadius) {
                        gapFaces.add(new GapFace(pos.immutable(), dir));
                        continue;
                    }
                }

                if (!level.isLoaded(neighbour)) {
                    gapFaces.add(new GapFace(pos.immutable(), dir));
                    continue;
                }

                BlockState nState = level.getBlockState(neighbour);
                BlockPhysicsData nData = BlockPhysicsRegistry.get(nState);

                if (nData.isAirtight && nData.emission == null) {
                    wallSet.add(neighbour.immutable());
                    wallFaces.add(buildWallFace(level, pos, dir, neighbour, nData));
                } else {
                    if (!nData.isAirtight) {
                        gapFaces.add(new GapFace(pos.immutable(), dir));
                    }
                    queue.add(neighbour);
                }
            }
        }

        // Also pick up adjacent static sources not found inside the BFS
        Set<BlockPos> checkedAdj = new HashSet<>();
        for (BlockPos ip : interior) {
            for (Direction d : Direction.values()) {
                BlockPos adj = ip.relative(d);
                if (interior.contains(adj) || !checkedAdj.add(adj)) continue;
                if (!level.isLoaded(adj)) continue;
                BlockState adjState = level.getBlockState(adj);
                BlockPhysicsData adjData = BlockPhysicsRegistry.get(adjState);
                if (adjData.emission != null) {
                    BlockPos adjImm = adj.immutable();
                    if (sources.stream().noneMatch(s -> s.pos().equals(adjImm))) {
                        sources.add(new HeatSourceEntry(adjImm, adjState, adjData.emission));
                    }
                }
            }
        }

        List<BlockPos> chimney = detectChimney(level, interior, maxRadius);
        boolean sealed = gapFaces.isEmpty() && !hitCap;

        return new ThermalStructure(interior, wallSet, wallFaces, gapFaces,
                sources, chimney, sealed, hitCap);
    }

    public static ThermalStructure scan(Level level, BlockPos origin) {
        return scan(level, origin, DEFAULT_MAX_RADIUS);
    }

    // ── Wall face computation ──────────────────────────────────────────────────

    private static WallFace buildWallFace(Level level, BlockPos interiorPos,
                                          Direction dir, BlockPos firstWallPos,
                                          BlockPhysicsData firstData) {
        double totalR = firstData.insulationR * firstData.thicknessFraction;
        double totalMass = firstData.thermalMass    * firstData.thicknessFraction;

        BlockPos cursor = firstWallPos;
        for (int depth = 1; depth < MAX_WALL_DEPTH; depth++) {
            cursor = cursor.relative(dir);
            if (!level.isLoaded(cursor)) break;
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) break;
            BlockPhysicsData d = BlockPhysicsRegistry.get(state);
            if (!d.isAirtight) break;
            totalR += d.insulationR * d.thicknessFraction;
            totalMass += d.thermalMass    * d.thicknessFraction;
        }

        return new WallFace(interiorPos.immutable(), dir, totalR, totalMass);
    }

    // ── Chimney detection ──────────────────────────────────────────────────────

    private static List<BlockPos> detectChimney(Level level, Set<BlockPos> interior,
                                                int maxRadius) {
        if (interior.isEmpty()) return Collections.emptyList();

        int maxY = interior.stream().mapToInt(BlockPos::getY).max().orElse(0);
        List<BlockPos> topRow = interior.stream().filter(p -> p.getY() == maxY).toList();
        List<BlockPos> best   = Collections.emptyList();

        for (BlockPos top : topRow) {
            List<BlockPos> shaft = new ArrayList<>();
            BlockPos cursor = top.above();
            int limit = maxRadius >= 0 ? maxRadius : 256;
            for (int i = 0; i < limit; i++) {
                if (!level.isLoaded(cursor)) break;
                BlockState state = level.getBlockState(cursor);
                BlockPhysicsData d = BlockPhysicsRegistry.get(state);
                if (d.isAirtight && d.emission == null) break;
                shaft.add(cursor.immutable());
                if (level.canSeeSky(cursor)) {
                    if (shaft.size() > best.size()) best = shaft;
                    break;
                }
                cursor = cursor.above();
            }
        }

        return Collections.unmodifiableList(best);
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    public double chimneyDraftFactor() {
        if (chimneyShaft.isEmpty()) return 0.0;
        return Math.min(1.0, chimneyShaft.size() / 16.0);
    }

    public double opennessFraction() {
        int total = wallFaces.size() + gapFaces.size();
        return total == 0 ? 1.0 : (double) gapFaces.size() / total;
    }

    public boolean hasInductionSource() {
        return heatSources.stream().anyMatch(HeatSourceEntry::isInduction);
    }

    public double nominalHeatInput() {
        return heatSources.stream().mapToDouble(HeatSourceEntry::wattsPerBlock).sum();
    }
}
