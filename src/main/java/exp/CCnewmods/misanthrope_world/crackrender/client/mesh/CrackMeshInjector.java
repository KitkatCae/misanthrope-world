package exp.CCnewmods.misanthrope_world.crackrender.client.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import exp.CCnewmods.misanthrope_world.crackrender.client.ClientCrackCache;
import exp.CCnewmods.misanthrope_world.crackrender.data.CrackEntry;
import exp.CCnewmods.misanthrope_world.crackrender.data.VeinSegment;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Injects crack trough geometry directly into vanilla's own open chunk-mesh
 * BufferBuilders during {@code compile()} — WHILE they're still being written
 * to, before vanilla calls {@code endOrDiscardIfEmpty()} on them.
 * <p>
 * ── Called from ───────────────────────────────────────────────────────────────
 * {@code ChunkCrackInjectorMixin.misanthrope_injectCrackGeometryBeforeEnd()},
 * a {@code @Redirect} on {@code BufferBuilder.endOrDiscardIfEmpty()} inside
 * {@code RebuildTask.compile()}. See that class's doc for the full reasoning
 * on why this replaced an earlier RETURN-based approach that never actually
 * ran (region field read after being nulled) and, even fixed, would have
 * clobbered vanilla terrain geometry (renderedLayers is a Map, not a Set —
 * a post-hoc put() would replace the section's real buffer, not extend it).
 * <p>
 * Because we now write into the SAME BufferBuilder instances vanilla itself
 * populated and will later close, {@code solidBuf}/{@code cutoutBuf} here are
 * literally vanilla's own buffers, still mid-batch. We don't begin() them
 * (vanilla already did, if it's using them at all) and we don't end() them
 * (the mixin's real endOrDiscardIfEmpty() call does that immediately after
 * this returns) — we just append quads.
 * <p>
 * ── What this does ────────────────────────────────────────────────────────────
 * 1. Queries ClientCrackCache for all entries in this 16³ section.
 * 2. For each cracked block, for each VeinSegment, for each face the segment
 * crosses: calls CrackTroughGeometry.emitFace() writing into whichever of
 * solidBuf/cutoutBuf are non-null.
 * <p>
 * Either parameter may be null if vanilla never opened that RenderType for
 * this section (e.g. a section with no cutout-rendered blocks at all) — the
 * mixin already checked BufferBuilder.building() before calling in. In that
 * case we just skip geometry that would have gone to the missing layer
 * (e.g. a severe crack's void gap, which lives in cutout) rather than force
 * -opening a buffer nothing would ever close.
 * <p>
 * ── Face visibility check ─────────────────────────────────────────────────────
 * Before emitting geometry for a face, we check that the adjacent block is not
 * opaque — same culling logic vanilla uses. No point injecting trough geometry
 * on a face that's completely hidden inside another solid block.
 * <p>
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * This runs on a chunk compile worker thread. It only reads from ClientCrackCache
 * (ConcurrentHashMap, safe for concurrent reads) and writes to BufferBuilder
 * instances that are per-task, not shared between threads.
 */
public final class CrackMeshInjector {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeCore/CrackMeshInjector");

    private CrackMeshInjector() {
    }

    /**
     * Main injection entry point. Called by the mixin redirect just before
     * vanilla finalizes this section's BufferBuilders.
     *
     * @param region        the RenderChunkRegion for this section (block state queries)
     * @param sectionOrigin the BlockPos origin of this 16³ section
     * @param solidBuf      vanilla's open solid-layer BufferBuilder, or null if not open this section
     * @param cutoutBuf     vanilla's open cutout-layer BufferBuilder, or null if not open this section
     */
    public static void inject(RenderChunkRegion region,
                               BlockPos sectionOrigin,
                               @Nullable BufferBuilder solidBuf,
                               @Nullable BufferBuilder cutoutBuf) {
        if (solidBuf == null && cutoutBuf == null) return;

        int sx = sectionOrigin.getX() >> 4;
        int sy = sectionOrigin.getY() >> 4;
        int sz = sectionOrigin.getZ() >> 4;

        Collection<CrackEntry> entries = ClientCrackCache.getEntriesInSection(sx, sy, sz);
        if (entries.isEmpty()) return;

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
                        emit(solidBuf, cutoutBuf, pose, entry, segment, segment.entryFace(), blockPos);
                    }
                }

                // Emit geometry for the exit face if this block isn't the terminus
                if (segment.exitFace() != null) {
                    if (isFaceVisible(region, blockPos, segment.exitFace())) {
                        emit(solidBuf, cutoutBuf, pose, entry, segment, segment.exitFace(), blockPos);
                    }
                }

                // If origin block (no entry face), emit geometry on all exposed faces
                if (segment.isOrigin()) {
                    for (Direction face : Direction.values()) {
                        if (isFaceVisible(region, blockPos, face)) {
                            emit(solidBuf, cutoutBuf, pose, entry, segment, face, blockPos);
                        }
                    }
                }
            }

            pose.popPose();
        }
    }

    /**
     * Thin wrapper around CrackTroughGeometry.emitFace() that tolerates a
     * null solidBuf or cutoutBuf (see class doc) by substituting a no-op
     * sink so CrackTroughGeometry doesn't need to know about the null case.
     */
    private static void emit(@Nullable BufferBuilder solidBuf,
                              @Nullable BufferBuilder cutoutBuf,
                              PoseStack pose,
                              CrackEntry entry,
                              VeinSegment segment,
                              Direction face,
                              BlockPos blockPos) {
        if (solidBuf == null && cutoutBuf == null) return;
        CrackTroughGeometry.emitFace(
                solidBuf != null ? solidBuf : NoOpVertexConsumer.INSTANCE,
                cutoutBuf != null ? cutoutBuf : NoOpVertexConsumer.INSTANCE,
                pose.last(),
                entry, segment, face,
                null, // uvs — BakedQuad UVs, extracted by the face-suppression mixin elsewhere
                combinedLight(blockPos),
                combinedOverlay()
        );
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

    private static int combinedLight(BlockPos pos) {
        // Full brightness for crack geometry — it should look naturally dark
        // from its vertex colors rather than from light levels
        return 0xF000F0;
    }

    private static int combinedOverlay() {
        return net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
    }
}
