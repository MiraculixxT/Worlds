package de.miraculixx.chunkeditor.mixin;

import de.miraculixx.chunkeditor.ChunkEditor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Icon button left of the vanilla edit button (`renameButton` is what 1.21 calls it)
 */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {
    @Unique
    private static final ResourceLocation CHUNKEDITOR_SPRITE = ResourceLocation.fromNamespaceAndPath("chunkeditor", "remote_icon");
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

    /** 1.21's {@code WorldListEntry} exposes no summary, so the selected id is remembered here */
    @Unique
    private String chunkeditor$selected;

    private SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void chunkeditor$addButton(CallbackInfo ci) {
        SpriteIconButton button = SpriteIconButton
                .builder(Component.translatable("chunkeditor.open"), b -> chunkeditor$open(), true)
                .width(CHUNKEDITOR_BUTTON_SIZE)
                .sprite(CHUNKEDITOR_SPRITE, 16, 16)
                .build();
        button.active = this.renameButton != null && this.renameButton.active;
        addRenderableWidget(button);
        chunkeditor$button = button;
        chunkeditor$place();
    }

    /**
     * 1.21's {@code SelectWorldScreen} declares no {@code repositionElements}, so a resize goes
     * through {@code Screen}'s default, which re-runs {@code init}.
     */
    @Inject(method = "updateButtonStatus", at = @At("TAIL"))
    private void chunkeditor$updateStatus(LevelSummary summary, CallbackInfo ci) {
        chunkeditor$selected = summary == null ? null : summary.getLevelId();
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
        if (chunkeditor$selected != null) ChunkEditor.INSTANCE.openOwned(this, chunkeditor$selected);
    }
}
