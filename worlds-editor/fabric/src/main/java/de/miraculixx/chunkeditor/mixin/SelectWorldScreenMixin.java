package de.miraculixx.chunkeditor.mixin;

import de.miraculixx.chunkeditor.ChunkEditor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Icon button left of the vanilla edit button (`renameButton` is what 1.21.11 calls it)
 */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {
    @Unique
    private static final Identifier CHUNKEDITOR_SPRITE = Identifier.fromNamespaceAndPath("chunkeditor", "remote_icon");
    @Unique
    private static final int CHUNKEDITOR_BUTTON_SIZE = 20;
    @Unique
    private static final int CHUNKEDITOR_GAP = 8;

    @Shadow
    private Button renameButton;

    @Shadow
    private WorldSelectionList list;

    @Unique
    private SpriteIconButton chunkeditor$button;

    private SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void chunkeditor$addButton(CallbackInfo ci) {
        SpriteIconButton button = SpriteIconButton
                .builder(Component.translatable("chunkeditor.open"), b -> chunkeditor$open(), true)
                .width(CHUNKEDITOR_BUTTON_SIZE)
                .sprite(CHUNKEDITOR_SPRITE, 16, 16)
                .withTootip()
                .build();
        button.active = this.renameButton != null && this.renameButton.active;
        addRenderableWidget(button);
        chunkeditor$button = button;
        chunkeditor$place();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void chunkeditor$reposition(CallbackInfo ci) {
        chunkeditor$place();
    }

    @Inject(method = "updateButtonStatus", at = @At("TAIL"))
    private void chunkeditor$updateStatus(CallbackInfo ci) {
        if (chunkeditor$button != null && this.renameButton != null) chunkeditor$button.active = this.renameButton.active;
    }

    @Unique
    private void chunkeditor$place() {
        if (chunkeditor$button == null || this.renameButton == null) return;
        chunkeditor$button.setPosition(
                this.renameButton.getX() - CHUNKEDITOR_GAP - CHUNKEDITOR_BUTTON_SIZE,
                this.renameButton.getY()
        );
    }

    @Unique
    private void chunkeditor$open() {
        if (this.list == null) return;
        this.list.getSelectedOpt().ifPresent(entry -> ChunkEditor.INSTANCE.openOwned(this, entry.getLevelSummary().getLevelId()));
    }
}
