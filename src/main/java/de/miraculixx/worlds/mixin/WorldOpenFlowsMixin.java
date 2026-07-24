package de.miraculixx.worlds.mixin;

import de.miraculixx.worlds.data.WorldResourcePacks;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Vanilla only auto-loads `resourcepacks/resources.zip`, then why is there even a resource-packs folder??
 * Changed to load all packs inside the bundled folder like it should.
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @Inject(method = "loadBundledResourcePack", at = @At("HEAD"), cancellable = true)
    private void worlds_pushAllWorldPacks(
            DownloadedPackSource packSource,
            LevelStorageSource.LevelStorageAccess levelSourceAccess,
            CallbackInfoReturnable<CompletableFuture<Void>> cir
    ) {
        cir.setReturnValue(WorldResourcePacks.INSTANCE.pushWorldPacks(packSource, levelSourceAccess));
    }
}
