package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.ShowMyWorld;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the edited server's preview up while its settings are open, the way
 * {@code EditWorldScreenMixin} does for a world. The same screen also serves Add Server, whose
 * {@code ServerData} carries an empty address — {@code selectServer} then falls through to nothing
 */
@Mixin(ManageServerScreen.class)
public class ManageServerScreenMixin {
    @Shadow
    @Final
    private ServerData serverData;

    @Inject(method = "init", at = @At("TAIL"))
    private void showmyworld$keepPreview(CallbackInfo ci) {
        String ip = this.serverData.ip;
        ShowMyWorld.INSTANCE.selectServer(ip == null || ip.isBlank() ? null : ip);
    }
}
