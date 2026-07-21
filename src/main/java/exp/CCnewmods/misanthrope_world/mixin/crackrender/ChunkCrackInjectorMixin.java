package exp.CCnewmods.misanthrope_world.mixin.crackrender;

import com.mojang.blaze3d.vertex.BufferBuilder;
import exp.CCnewmods.misanthrope_world.crackrender.client.mesh.CrackMeshInjector;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Injects crack trough geometry into the chunk mesh WHILE vanilla's own
 * per-layer BufferBuilders are still open, instead of trying to patch
 * {@code CompileResults} after {@code compile()} has already returned.
 * <p>
 * ── Why this replaced the old RETURN-based approach ──────────────────────────
 * Two things made the original {@code @Inject(at = @At("RETURN"))} version a
 * dead end, not just a bug to patch in place:
 * <p>
 * 1. {@code RebuildTask.region} is read into a local variable and immediately
 * nulled out as the very first thing {@code compile()} does
 * ({@code RenderChunkRegion renderchunkregion = this.region; this.region = null;}
 * — confirmed via CFR decompile of the real class). Reading the {@code region}
 * *field* after {@code compile()} returns therefore always reads {@code null} —
 * the region was never actually available at the point the old mixin read it,
 * so {@code CrackMeshInjector.inject()} was never even reached.
 * <p>
 * 2. Even with that fixed, {@code CompileResults.renderedLayers} is a
 * {@code Map<RenderType, BufferBuilder.RenderedBuffer>} (confirmed via javap),
 * not a {@code Set<RenderType>}. By the time {@code compile()} returns, every
 * {@code RenderType} vanilla actually used has already had
 * {@code endOrDiscardIfEmpty()} called on it and an immutable
 * {@code RenderedBuffer} stored in that map — the underlying
 * {@code BufferBuilder} has already been reset/detached from that data.
 * Re-opening it post-hoc and trying to {@code Map.put()} a second buffer in
 * would have *replaced* the vanilla terrain geometry for that RenderType in
 * the whole section, not added to it.
 * <p>
 * ── New approach ──────────────────────────────────────────────────────────────
 * {@code compile()}'s own finalization loop is:
 * <pre>
 *   for (RenderType rendertype1 : set) {
 *       RenderedBuffer buf = pChunkBufferBuilderPack.builder(rendertype1).endOrDiscardIfEmpty();
 *       if (buf != null) results.renderedLayers.put(rendertype1, buf);
 *   }
 * </pre>
 * We {@code @Redirect} the {@code endOrDiscardIfEmpty()} call itself. The
 * first time it fires for a given {@code compile()} invocation, we write all
 * crack geometry for this section directly into whichever of the solid/cutout
 * {@code BufferBuilder}s vanilla already has open (checked via the public
 * {@code building()} accessor — if a RenderType was never begun this section,
 * we skip it rather than force-opening it, since nothing would ever call
 * {@code endOrDiscardIfEmpty()} on it and it would leak into the next pooled
 * use of that buffer). Whichever specific RenderType's turn it already was in
 * the loop then gets finalized completely normally afterward — our geometry
 * rides along inside the SAME {@code RenderedBuffer} vanilla produces, so
 * {@code results.renderedLayers} never needs to be touched by us at all.
 * <p>
 * ── Region capture ────────────────────────────────────────────────────────────
 * Since the field is nulled before we'd ever get to read it, we grab
 * {@code this.region} at {@code @At("HEAD")} instead — before vanilla's own
 * null-out runs — and stash it in a {@link ThreadLocal}. Chunk compile tasks
 * run one at a time per worker thread (each {@code compile()} call is
 * synchronous start-to-finish on whichever thread picked up the task), so a
 * plain ThreadLocal is sufficient; no per-instance bookkeeping/cleanup needed
 * beyond clearing it once we're done with it.
 * <p>
 * ── Field access via reflection ───────────────────────────────────────────────
 * Still can't use @Shadow for fields in this inner class — the annotation
 * processor writes bad refmap entries for inner class fields even with
 * remap = false on @Mixin. "region" and "this$1" accessed via cached
 * reflection Fields, same as before.
 * <p>
 * ── SRG name verification ──────────────────────────────────────────────────────
 * Both targets below are confirmed directly against this project's own
 * {@code output.tsrg} (not guessed):
 * {@code compile(float, float, float, ChunkBufferBuilderPack)} → {@code m_234467_},
 * {@code BufferBuilder.endOrDiscardIfEmpty()} → {@code m_231168_}. An earlier
 * version of this mixin used the literal Mojang name for the latter instead of
 * its real SRG identifier, which failed Mixin's injection-target scan at
 * class-load time (0/1 matched) since {@code remap = false} takes the target
 * string completely literally. Field reflection elsewhere in this class
 * ({@code region}, {@code this$1}) is unaffected by this — those are plain
 * Java reflection calls resolved against the final, fully-loaded class after
 * Forge's own runtime remapping, not Mixin annotation strings, so they see the
 * official (Mojang) names as written.
 */
@Mixin(
        targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask",
        remap = false
)
public class ChunkCrackInjectorMixin {

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/ChunkCrackInjectorMixin");

    // Cached reflection fields — initialized once on first use
    private static Field REGION_FIELD;
    private static Field PARENT_FIELD;
    private static volatile boolean FIELDS_INITIALIZED = false;

    // Per-thread state for the single in-flight compile() call on this thread.
    private static final ThreadLocal<RenderChunkRegion> CAPTURED_REGION = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> INJECTED_THIS_COMPILE = ThreadLocal.withInitial(() -> false);

    private static void initFields(Object instance) {
        if (FIELDS_INITIALIZED) return;
        synchronized (ChunkCrackInjectorMixin.class) {
            if (FIELDS_INITIALIZED) return;
            try {
                Class<?> rebuildTaskClass = instance.getClass();
                REGION_FIELD = rebuildTaskClass.getDeclaredField("region");
                REGION_FIELD.setAccessible(true);

                PARENT_FIELD = rebuildTaskClass.getDeclaredField("this$1");
                PARENT_FIELD.setAccessible(true);

                FIELDS_INITIALIZED = true;
            } catch (Exception e) {
                LOGGER.error("[ChunkCrackInjector] Failed to cache reflection fields: {}", e.getMessage());
            }
        }
    }

    /**
     * Grabs {@code this.region} before vanilla's own first line of
     * {@code compile()} nulls it out.
     */
    @Inject(
            method = "m_234467_",
            at = @At("HEAD"),
            remap = false
    )
    private void misanthrope_captureRegion(
            float camX, float camY, float camZ,
            ChunkBufferBuilderPack buffers,
            CallbackInfoReturnable<Object> cir) {

        INJECTED_THIS_COMPILE.set(false);
        CAPTURED_REGION.remove();

        initFields(this);
        if (!FIELDS_INITIALIZED) return;

        try {
            RenderChunkRegion region = (RenderChunkRegion) REGION_FIELD.get(this);
            if (region != null) {
                CAPTURED_REGION.set(region);
            }
        } catch (Exception e) {
            LOGGER.warn("[ChunkCrackInjector] Failed to capture region at HEAD: {}", e.getMessage());
        }
    }

    /**
     * Fires once per RenderType vanilla finalizes this compile() call. On the
     * first firing (regardless of which RenderType it happens to be), writes
     * all crack geometry for this section into whichever of solid/cutout are
     * actually open, then lets the real {@code endOrDiscardIfEmpty()} proceed
     * for whichever RenderType this particular call is for.
     * <p>
     * Target confirmed against output.tsrg — see class doc.
     */
    @Redirect(
            method = "m_234467_",
            at = @At(
                    value = "INVOKE",
                    // Verified against this build's own output.tsrg:
                    // endOrDiscardIfEmpty() -> m_231168_. (compile()'s own
                    // target, m_234467_ above, was independently confirmed
                    // correct from the same file.)
                    target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;m_231168_()Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;"
            ),
            remap = false
    )
    private BufferBuilder.RenderedBuffer misanthrope_injectCrackGeometryBeforeEnd(
            BufferBuilder builder,
            // Appended: compile()'s own parameters, captured in order per
            // vanilla Mixin's redirect-handler local-capture convention (no
            // MixinExtras needed for enclosing-method parameters).
            float camX, float camY, float camZ,
            ChunkBufferBuilderPack buffers) {

        if (!Boolean.TRUE.equals(INJECTED_THIS_COMPILE.get())) {
            INJECTED_THIS_COMPILE.set(true);
            try {
                runInjection(buffers);
            } catch (Exception e) {
                LOGGER.warn("[ChunkCrackInjector] Exception during crack injection: {}", e.getMessage());
            } finally {
                CAPTURED_REGION.remove();
            }
        }

        return builder.endOrDiscardIfEmpty();
    }

    private void runInjection(ChunkBufferBuilderPack buffers) {
        RenderChunkRegion region = CAPTURED_REGION.get();
        if (region == null) return;

        ChunkRenderDispatcher.RenderChunk parent;
        try {
            parent = (ChunkRenderDispatcher.RenderChunk) PARENT_FIELD.get(this);
        } catch (Exception e) {
            LOGGER.warn("[ChunkCrackInjector] Could not read parent RenderChunk: {}", e.getMessage());
            return;
        }
        if (parent == null) return;

        BlockPos origin = parent.getOrigin();

        BufferBuilder solidBuf = buffers.builder(RenderType.solid());
        BufferBuilder cutoutBuf = buffers.builder(RenderType.cutout());

        // Only write into buffers vanilla actually opened this section —
        // writing into an un-begun (pooled, idle) buffer would leave it in a
        // "building" state that nothing ever closes, corrupting its next use.
        boolean solidOpen = solidBuf.building();
        boolean cutoutOpen = cutoutBuf.building();
        if (!solidOpen && !cutoutOpen) return;

        CrackMeshInjector.inject(
                region, origin,
                solidOpen ? solidBuf : null,
                cutoutOpen ? cutoutBuf : null
        );
    }
}
