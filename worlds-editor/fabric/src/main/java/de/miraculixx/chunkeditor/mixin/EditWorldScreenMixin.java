package de.miraculixx.chunkeditor.mixin;

import de.miraculixx.chunkeditor.ChunkEditor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The button is added once and moved by hand.
 */
@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {
    @Unique
    private static final int CHUNKEDITOR_BUTTON_W = 200;
    @Unique
    private static final int CHUNKEDITOR_BUTTON_H = 20;

    @Unique
    private static final int CHUNKEDITOR_BUTTON_BOTTOM_OFFSET = 52;

    @Shadow
    @Final
    private LevelStorageSource.LevelStorageAccess levelAccess;

    @Unique
    private Button chunkeditor$button;

    private EditWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void chunkeditor$addButton(CallbackInfo ci) {
        chunkeditor$button = Button
                .builder(Component.translatable("chunkeditor.open"), b -> ChunkEditor.INSTANCE.open(this, this.levelAccess))
                .bounds(0, 0, CHUNKEDITOR_BUTTON_W, CHUNKEDITOR_BUTTON_H)
                .build();
        chunkeditor$place();
        addRenderableWidget(chunkeditor$button);
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void chunkeditor$repositionButton(CallbackInfo ci) {
        if (chunkeditor$button != null) chunkeditor$place();
    }

    @Unique
    private void chunkeditor$place() {
        chunkeditor$button.setPosition((this.width - CHUNKEDITOR_BUTTON_W) / 2, this.height - CHUNKEDITOR_BUTTON_BOTTOM_OFFSET);
    }
}