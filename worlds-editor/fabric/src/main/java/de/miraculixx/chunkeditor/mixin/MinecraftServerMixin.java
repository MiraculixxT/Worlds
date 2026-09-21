package de.miraculixx.chunkeditor.mixin;

import de.miraculixx.chunkeditor.server.ServerJobs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Queued actions (writes) are performed on shutdown, when world is safe to edit
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void chunkeditor$applyQueuedJobs(CallbackInfo ci) {
        ServerJobs.INSTANCE.applyAll(((MinecraftServer) (Object) this).getWorldPath(LevelResource.ROOT));
    }
}
