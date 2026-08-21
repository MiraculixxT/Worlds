package de.miraculixx.worlds.mixin;

import de.miraculixx.worlds.client.ui.panorama.PanoramaCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World leave hook
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "disconnectFromWorld", at = @At("HEAD"))
    private void worlds_capturePanorama(Component message, CallbackInfo ci) {
        PanoramaCapture.INSTANCE.onLeaveWorld((Minecraft) (Object) this);
    }
}
