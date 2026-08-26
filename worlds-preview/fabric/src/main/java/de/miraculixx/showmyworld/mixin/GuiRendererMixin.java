package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.client.ui.panorama.WorldPanorama;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.PanoramaRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the selected world's own panorama on top of the title one
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Shadow @Final private GuiRenderState renderState;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/CubeMap;render(FF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void showmyworld$renderWorldPanorama(CallbackInfo ci) {
        PanoramaRenderState panorama = this.renderState.panoramaRenderState;
        if (panorama != null) WorldPanorama.INSTANCE.render(10.0F, panorama.spin());
    }
}
