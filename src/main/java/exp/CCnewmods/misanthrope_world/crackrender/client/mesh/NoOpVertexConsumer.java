package exp.CCnewmods.misanthrope_world.crackrender.client.mesh;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Discards everything written to it. Used by {@link CrackMeshInjector} in
 * place of a null solid/cutout BufferBuilder — vanilla only opens a
 * RenderType's buffer for a section if it actually used it, and we must not
 * write into (or begin/end) a buffer vanilla never opened, since nothing
 * would ever close it and the pooled BufferBuilder would leak a "building"
 * state into its next use. Passing this sink instead lets
 * {@code CrackTroughGeometry.emitFace()} always have two real
 * {@link VertexConsumer}s to write to, without it needing any null-handling
 * of its own — geometry aimed at the missing layer (e.g. a severe crack's
 * void gap when cutout wasn't open this section) is simply dropped.
 */
final class NoOpVertexConsumer implements VertexConsumer {

    static final NoOpVertexConsumer INSTANCE = new NoOpVertexConsumer();

    private NoOpVertexConsumer() {
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        return this;
    }

    @Override
    public void endVertex() {
        // no-op
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
        // no-op
    }

    @Override
    public void unsetDefaultColor() {
        // no-op
    }
}
