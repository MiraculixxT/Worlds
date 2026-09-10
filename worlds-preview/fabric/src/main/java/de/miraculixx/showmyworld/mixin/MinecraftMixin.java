package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.client.ui.panorama.PanoramaCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World leave hook
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void showmyworld$capturePanorama(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        PanoramaCapture.INSTANCE.onLeaveWorld((Minecraft) (Object) this);
    }
}
