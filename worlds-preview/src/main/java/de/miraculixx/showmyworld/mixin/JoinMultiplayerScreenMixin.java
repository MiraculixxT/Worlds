package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.ShowMyWorld;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Panorama preview in the vanilla server list
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin {
    @Shadow
    protected ServerSelectionList serverSelectionList;

    /** {@code ServerSelectionList.setSelected} calls this, so it is the selection hook */
    @Inject(method = "onSelectedChange", at = @At("TAIL"))
    private void showmyworld$select(CallbackInfo ci) {
        showmyworld$push();
    }

    /**
     * A sub screen (Edit, Delete, Direct Connect) ran {@code removed()} on the way out
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void showmyworld$initSelect(CallbackInfo ci) {
        showmyworld$push();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void showmyworld$repositionSelect(CallbackInfo ci) {
        showmyworld$push();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void showmyworld$deselect(CallbackInfo ci) {
        ShowMyWorld.INSTANCE.selectServer(null);
    }

    @Unique
    private void showmyworld$push() {
        ShowMyWorld.INSTANCE.selectServer(showmyworld$address());
    }

    /**
     * LAN entries are skipped
     */
    @Unique
    private String showmyworld$address() {
        if (this.serverSelectionList == null) return null;
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (selected instanceof ServerSelectionList.OnlineServerEntry online) return online.getServerData().ip;
        return null;
    }
}
