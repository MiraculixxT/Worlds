package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.ShowMyWorld;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the edited world's preview up while its settings are open
 */
@Mixin(EditWorldScreen.class)
public class EditWorldScreenMixin {
    @Shadow
    @Final
    private LevelStorageSource.LevelStorageAccess levelAccess;

    @Inject(method = "init", at = @At("TAIL"))
    private void showmyworld$keepPreview(CallbackInfo ci) {
        ShowMyWorld.INSTANCE.select(this.levelAccess.getLevelId());
    }
}
