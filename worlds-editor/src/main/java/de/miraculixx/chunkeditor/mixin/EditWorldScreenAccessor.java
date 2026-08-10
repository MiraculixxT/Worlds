package de.miraculixx.chunkeditor.mixin;

import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The screen's own {@code LevelStorageAccess} is the only usable one — it holds the save's session
 * lock, so opening a second access for the same world would fail.
 */
@Mixin(EditWorldScreen.class)
public interface EditWorldScreenAccessor {
    @Accessor("levelAccess")
    LevelStorageSource.LevelStorageAccess chunkeditor$levelAccess();
}
