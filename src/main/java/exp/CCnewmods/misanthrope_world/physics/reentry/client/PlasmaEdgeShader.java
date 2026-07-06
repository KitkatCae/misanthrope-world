package exp.CCnewmods.misanthrope_world.physics.reentry.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterShadersEvent;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Loads and holds a reference to the {@code plasma_edge} ShaderInstance.
 *
 * <p>Registered via {@link RegisterShadersEvent} on the mod event bus.
 * The shader uses Minecraft's {@link DefaultVertexFormat#POSITION_COLOR_TEX}
 * format: Position (3f) + Color (4ub) + UV0 (2f).
 *
 * <h3>Uniform layout</h3>
 * <ul>
 *   <li>{@code Intensity}     — [0,1] heating intensity, set each frame</li>
 *   <li>{@code Time}          — game time in seconds, drives turbulence animation</li>
 *   <li>{@code BloomStrength} — [0,1] fraction written to bloom buffer</li>
 *   <li>{@code ModelViewMat} / {@code ProjMat} — standard camera matrices</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class PlasmaEdgeShader {

    private PlasmaEdgeShader() {}

    @Nullable
    private static ShaderInstance instance;

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Called from the mod event bus {@link RegisterShadersEvent} subscriber.
     * Registers the shader so Minecraft loads it on resource reload.
     */
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(
                        event.getResourceProvider(),
                        new ResourceLocation("misanthrope_world", "plasma_edge"),
                        DefaultVertexFormat.POSITION_COLOR_TEX),
                shader -> instance = shader);
    }

    // ── Access ────────────────────────────────────────────────────────────────

    @Nullable
    public static ShaderInstance get() { return instance; }

    /**
     * Sets per-frame uniforms. Call immediately before issuing draw calls.
     *
     * @param intensity heating intensity [0,1]
     * @param timeSecs  game time in seconds (used for turbulence animation)
     * @param bloomStr  bloom buffer contribution [0,1]
     */
    public static void setUniforms(float intensity, float timeSecs, float bloomStr) {
        if (instance == null) return;

        var uIntensity = instance.getUniform("Intensity");
        var uTime      = instance.getUniform("Time");
        var uBloom     = instance.getUniform("BloomStrength");

        if (uIntensity != null) uIntensity.set(intensity);
        if (uTime      != null) uTime.set(timeSecs);
        if (uBloom     != null) uBloom.set(bloomStr);
    }

    /** Apply the shader (binds the program and uploads uniforms). */
    public static void apply() {
        if (instance != null) instance.apply();
    }

    /** Clear the shader after drawing. */
    public static void clear() {
        if (instance != null) instance.clear();
    }
}
