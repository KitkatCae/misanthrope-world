package exp.CCnewmods.misanthrope_world.crackrender.client.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import exp.CCnewmods.misanthrope_world.crackrender.client.ClientCrackCache;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.data.VeinSegment;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Set;

/**
 * Injects crack trough geometry into the chunk mesh buffers during compile().
 * <p>
 * ── Called from ───────────────────────────────────────────────────────────────
 * ChunkCrackInjectorMixin.misanthrope_injectCrackGeometry(), which is an
 *
 * @Inject at RETURN of RebuildTask.compile().
 * <p>
 * ── What this does ────────────────────────────────────────────────────────────
 * 1. Gets the section origin from the parent RenderChunk.
 * 2. Queries ClientCrackCache for all entries in this 16³ section.
 * 3. For each cracked block, for each VeinSegment, for each face the segment
 * crosses: calls CrackTroughGeometry.emitFace() writing into the solid and
 * cutout BufferBuilders from the ChunkBufferBuilderPack.
 * 4. Adds RenderType.solid() and RenderType.cutout() to CompileResults.renderedLayers
 * if any geometry was written (so the chunk uploader picks up the buffers).
 * <p>
 * ── Face visibility check ─────────────────────────────────────────────────────
 * Before emitting geometry for a face, we check that the adjacent block is not
 * opaque — same culling logic vanilla uses. No point injecting trough geometry
 * on a face that's completely hidden inside another solid block.
 * <p>
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * This runs on a chunk compile worker thread. It only reads from ClientCrackCache
 * (ConcurrentHashMap, safe for concurrent reads) and writes to the BufferBuilder
 * instances from the pack (which are per-task, not shared between threads).
 */
public final class CrackMeshInjector {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/CrackMeshInjector");

    // Reflection cache for renderedLayers field on CompileResults
    private static Field renderedLayersField;

    static {
        try {
            Class<?> compileResultsClass = Class.forName(
                    "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask$CompileResults");
            renderedLayersField = compileResultsClass.getDeclaredField("renderedLayers");
            renderedLayersField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.error("[CrackMeshInjector] Failed to cache renderedLayers field: {}", e.getMessage());
        }
    }

    private CrackMeshInjector() {
    }

    /**
     * Main injection entry point. Called by the mixin after vanilla compile() returns.
     *
     * @param region        the RenderChunkRegion for this section (block state queries)
     * @param sectionOrigin the BlockPos origin of this 16³ section
     * @param buffers       the ChunkBufferBuilderPack being written
     * @param results       the CompileResults to add renderedLayers to
     */
    @SuppressWarnings("unchecked")
    public static void inject(RenderChunkRegion region,
                              BlockPos sectionOrigin,
                              ChunkBufferBuilderPack buffers,
                              Object results) {
        int sx = sectionOrigin.getX() >> 4;
        int sy = sectionOrigin.getY() >> 4;
        int sz = sectionOrigin.getZ() >> 4;

        Collection<CrackEntry> entries = ClientCrackCache.getEntriesInSection(sx, sy, sz);
        if (entries.isEmpty()) return;

        BufferBuilder solidBuf = buffers.builder(RenderType.solid());
        BufferBuilder cutoutBuf = buffers.builder(RenderType.cutout());

        boolean wroteToSolid = false;
        boolean wroteToCutout = false;

        // Ensure builders are in drawing state — beginLayer() if not already begun
        boolean solidStarted = ensureBegun(solidBuf, RenderType.solid());
        boolean cutoutStarted = ensureBegun(cutoutBuf, RenderType.cutout());

        PoseStack pose = new PoseStack();

        for (CrackEntry entry : entries) {
            if (!entry.hasCracks()) continue;

            BlockPos blockPos = entry.pos();

            // Translate pose to this block's local space within the section
            // The chunk compile coordinate space has the section origin at (0,0,0)
            float lx = blockPos.getX() - sectionOrigin.getX();
            float ly = blockPos.getY() - sectionOrigin.getY();
            float lz = blockPos.getZ() - sectionOrigin.getZ();

            pose.pushPose();
            pose.translate(lx, ly, lz);

            for (VeinSegment segment : entry.segments()) {
                // Emit geometry for the entry face if this block isn't the origin
                if (segment.entryFace() != null) {
                    if (isFaceVisible(region, blockPos, segment.entryFace())) {
                        float[][] uvs = null; // BakedQuad UVs — extracted by face suppression mixin
                        CrackTroughGeometry.emitFace(
                                solidBuf, cutoutBuf, pose.last(),
                                entry, segment,
                                segment.entryFace(),
                                uvs,
                                combinedLight(blockPos),
                                combinedOverlay()
                        );
                        wroteToSolid = true;
                        if (entry.isSevere()) wroteToCutout = true;
                    }
                }

                // Emit geometry for the exit face if this block isn't the terminus
                if (segment.exitFace() != null) {
                    if (isFaceVisible(region, blockPos, segment.exitFace())) {
                        float[][] uvs = null;
                        CrackTroughGeometry.emitFace(
                                solidBuf, cutoutBuf, pose.last(),
                                entry, segment,
                                segment.exitFace(),
                                uvs,
                                combinedLight(blockPos),
                                combinedOverlay()
                        );
                        wroteToSolid = true;
                        if (entry.isSevere()) wroteToCutout = true;
                    }
                }

                // If origin block (no entry face), emit geometry on all exposed faces
                if (segment.isOrigin()) {
                    for (Direction face : Direction.values()) {
                        if (isFaceVisible(region, blockPos, face)) {
                            CrackTroughGeometry.emitFace(
                                    solidBuf, cutoutBuf, pose.last(),
                                    entry, segment,
                                    face,
                                    null,
                                    combinedLight(blockPos),
                                    combinedOverlay()
                            );
                            wroteToSolid = true;
                        }
                    }
                }
            }

            pose.popPose();
        }

        // Add the render types we wrote to CompileResults.renderedLayers
        if (results != null && renderedLayersField != null) {
            try {
                Set<RenderType> layers = (Set<RenderType>) renderedLayersField.get(results);
                if (wroteToSolid) layers.add(RenderType.solid());
                if (wroteToCutout) layers.add(RenderType.cutout());
            } catch (Exception e) {
                LOGGER.warn("[CrackMeshInjector] Could not add to renderedLayers: {}", e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Check if a face of a block is visible (adjacent block is not opaque/solid).
     * Uses the RenderChunkRegion for the same block state data vanilla uses.
     */
    private static boolean isFaceVisible(RenderChunkRegion region, BlockPos pos, Direction face) {
        BlockPos adjacent = pos.relative(face);
        try {
            BlockState adjState = region.getBlockState(adjacent);
            return !adjState.isSolidRender(region, adjacent);
        } catch (Exception e) {
            return true; // default to visible on error
        }
    }

    /**
     * Ensure a BufferBuilder is in drawing state for the BLOCK vertex format.
     * Returns true if we called begin() (so the caller knows to call
     * storeRenderedBuffer when done if needed).
     * <p>
     * Note: in 1.20.1 Forge, the chunk compile already calls beginLayer() on
     * each buffer before calling renderBatched. We check isCurrentBatchEmpty()
     * rather than starting fresh — if the buffer is already open we just append.
     */
    private static boolean ensureBegun(BufferBuilder builder, RenderType type) {
        try {
            // If buffer isn't building yet, begin it
            // We check via the building field — if false, call begin()
            Field buildingField = BufferBuilder.class.getDeclaredField("building");
            buildingField.setAccessible(true);
            boolean building = buildingField.getBoolean(builder);
            if (!building) {
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                return true;
            }
            return false;
        } catch (Exception e) {
            // Assume it's already begun — better to append than to crash
            return false;
        }
    }

    private static int combinedLight(BlockPos pos) {
        // Full brightness for crack geometry — it should look naturally dark
        // from its vertex colors rather than from light levels
        return 0xF000F0;
    }

    private static int combinedOverlay() {
        return net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
    }
}
