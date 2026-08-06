package de.miraculixx.worlds.mixin;

import de.miraculixx.worlds.client.ui.OfflineStatsScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Make stats menu compatible outside of worlds by passing our own data instead of requesting from a server
 */
@Mixin(StatsScreen.class)
public abstract class StatsScreenMixin implements OfflineStatsScreen {

    @Unique
    private boolean worlds_local;

    @Shadow
    public abstract void onStatsUpdated();

    @Override
    public void worlds_useLocalStats() {
        worlds_local = true;
    }

    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;getConnection()Lnet/minecraft/client/multiplayer/ClientPacketListener;"
            ),
            cancellable = true
    )
    private void worlds_skipStatsRequest(CallbackInfo ci) {
        if (!worlds_local) return;
        ci.cancel();
        onStatsUpdated();
    }
}
