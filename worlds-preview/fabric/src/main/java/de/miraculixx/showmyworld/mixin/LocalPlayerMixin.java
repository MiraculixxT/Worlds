package de.miraculixx.showmyworld.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A passengers view yaw is its head rotation, which {@code grabPanoramixScreenshot} never turns,
 * so all four side faces came out identical while mounted (vanilla bug)
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void showmyworld$panoramaYaw(float a, CallbackInfoReturnable<Float> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isPassenger() && Minecraft.getInstance().gameRenderer.isPanoramicMode()) {
            cir.setReturnValue(self.getYRot());
        }
    }
}
