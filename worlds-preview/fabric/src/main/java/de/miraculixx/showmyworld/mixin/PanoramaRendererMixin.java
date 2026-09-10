package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.client.ui.panorama.WorldPanorama;
import net.minecraft.client.renderer.PanoramaRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the selected world's own panorama on top of the title one, between vanilla's cube map and
 * its overlay texture, so the layering matches what vanilla draws.
 */
@Mixin(PanoramaRenderer.class)
public class PanoramaRendererMixin {

    @Shadow private float spin;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/CubeMap;render(Lnet/minecraft/client/Minecraft;FFF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void showmyworld$renderWorldPanorama(CallbackInfo ci) {
        WorldPanorama.INSTANCE.render(10.0F, -this.spin);
    }
}
