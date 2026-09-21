package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.ShowMyWorld;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The world list drops its selection on the way out, this keeps the joined world up while it loads
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @Inject(method = "openWorld", at = @At("HEAD"))
    private void showmyworld$holdWorld(String levelId, Runnable onFail, CallbackInfo ci) {
        ShowMyWorld.INSTANCE.joinWorld(levelId);
    }
}
