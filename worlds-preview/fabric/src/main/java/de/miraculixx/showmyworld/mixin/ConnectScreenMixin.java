package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.ShowMyWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link WorldOpenFlowsMixin} for servers. LAN entries have no panorama folder
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void showmyworld$holdServer(
            Screen parent, Minecraft minecraft, ServerAddress address, ServerData serverData,
            boolean isQuickPlay, TransferState transferState, CallbackInfo ci
    ) {
        if (serverData == null || serverData.isLan() || serverData.ip.isBlank()) return;
        ShowMyWorld.INSTANCE.joinServer(serverData.ip);
    }
}
