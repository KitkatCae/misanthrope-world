package exp.CCnewmods.misanthrope_world;

import exp.CCnewmods.misanthrope_world.physics.pressure.hull.client.HullDeformationRenderer;
import exp.CCnewmods.misanthrope_world.physics.reentry.client.PlasmaTrailRenderer;
import exp.CCnewmods.misanthrope_world.physics.sonicboom.client.SoundBarrierPostEffect;
import exp.CCnewmods.misanthrope_world.physics.vaporise.client.VaporiseFlashRenderer;
import exp.CCnewmods.misanthrope_world.physics.vaporise.client.VaporiseBillboardRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client tick/render dispatcher for the VS2 systems ported from MVSE (hull
 * pressure, reentry heating, sonic booms, vaporization).
 *
 * <p>Ported from MVSE's {@code AerodynamicsClientEvents}, split across mods as
 * each system moved over. That file is now genuinely fully empty in MVSE
 * (safe to delete) since every system it drove has moved here — vaporize was
 * the last one. Currently drives:
 * <ul>
 *   <li>{@link HullDeformationRenderer#clientTick()}</li>
 *   <li>{@link PlasmaTrailRenderer#clientTick()} / {@code #updateFogIntensity}</li>
 *   <li>{@link SoundBarrierPostEffect#renderFrame} — sonic-boom bloom flash,
 *       drawn on {@code RenderGuiOverlayEvent.Pre} (after world, before HUD),
 *       same timing MVSE used it at.</li>
 *   <li>{@link VaporiseFlashRenderer#clientTick()} / {@link VaporiseBillboardRenderer#clientTick()}</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "misanthrope_world", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VSPhysicsClientEvents {

    private VSPhysicsClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;

        HullDeformationRenderer.clientTick();

        PlasmaTrailRenderer.clientTick();
        PlasmaTrailRenderer.updateFogIntensity(mc);

        VaporiseFlashRenderer.clientTick();
        VaporiseBillboardRenderer.clientTick();
    }

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // don't render over open screens

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        SoundBarrierPostEffect.renderFrame(event.getGuiGraphics().pose(), w, h);
    }
}

