package exp.CCnewmods.misanthrope_world.mixin.crackrender;

import exp.CCnewmods.misanthrope_world.crackrender.client.mesh.CrackMeshInjector;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Injects crack trough geometry into the chunk mesh after vanilla block
 * geometry has been compiled.
 * <p>
 * ── Field access via reflection ───────────────────────────────────────────────
 * We cannot use @Shadow for fields in this inner class — the annotation
 * processor writes bad refmap entries for inner class fields even with
 * remap = false on @Mixin, causing "field not located" errors at startup.
 * <p>
 * Instead we access "region" and "this$1" via cached reflection fields.
 * Both field names are confirmed exact from the recomp jar's constant pool.
 * <p>
 * ── Target ────────────────────────────────────────────────────────────────────
 * net.minecraft.client.renderer.chunk
 * .ChunkRenderDispatcher$RenderChunk$RebuildTask
 * method: m_234467_ (compile)
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

    @Inject(
            method = "m_234467_",
            at = @At("RETURN"),
            remap = false
    )
    private void misanthrope_injectCrackGeometry(
            float camX, float camY, float camZ,
            ChunkBufferBuilderPack buffers,
            CallbackInfoReturnable<Object> cir) {

        Object results = cir.getReturnValue();
        if (results == null) return;

        initFields(this);
        if (!FIELDS_INITIALIZED) return;

        try {
            RenderChunkRegion region = (RenderChunkRegion) REGION_FIELD.get(this);
            if (region == null) return;

            ChunkRenderDispatcher.RenderChunk parent =
                    (ChunkRenderDispatcher.RenderChunk) PARENT_FIELD.get(this);
            if (parent == null) return;

            BlockPos origin = parent.getOrigin();
            CrackMeshInjector.inject(region, origin, buffers, results);

        } catch (Exception e) {
            LOGGER.warn("[ChunkCrackInjector] Exception during crack injection: {}", e.getMessage());
        }
    }
}
