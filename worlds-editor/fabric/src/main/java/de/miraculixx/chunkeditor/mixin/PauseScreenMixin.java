package de.miraculixx.chunkeditor.mixin;

import de.miraculixx.chunkeditor.ChunkEditor;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Icon button in the pause menu's top left corner to open the live map.
 * 1.21's pause menu has no icon row to append to, so the button stands on its own.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Unique
    private static final ResourceLocation CHUNKEDITOR_REMOTE_SPRITE = ResourceLocation.fromNamespaceAndPath("chunkeditor", "remote_icon");
    @Unique
    private static final int CHUNKEDITOR_BUTTON_SIZE = 20;
    @Unique
    private static final int CHUNKEDITOR_MARGIN = 8;

    private PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void chunkeditor$addButton(CallbackInfo ci) {
        if (!((PauseScreen) (Object) this).showsPauseMenu()) return;
        ChunkEditor.RemoteState state = ChunkEditor.INSTANCE.remoteState();
        SpriteIconButton button = SpriteIconButton
                .builder(Component.translatable("chunkeditor.remote.open"), b -> ChunkEditor.INSTANCE.openRemote(this), true)
                .width(CHUNKEDITOR_BUTTON_SIZE)
                .sprite(CHUNKEDITOR_REMOTE_SPRITE, 16, 16)
                .build();
        button.setPosition(CHUNKEDITOR_MARGIN, CHUNKEDITOR_MARGIN);
        button.active = state == ChunkEditor.RemoteState.READY;
        if (!button.active) button.setTooltip(Tooltip.create(Component.translatable(chunkeditor$reason(state))));
        addRenderableWidget(button);
    }

    @Unique
    private static String chunkeditor$reason(ChunkEditor.RemoteState state) {
        return switch (state) {
            case NO_PERMISSION -> "chunkeditor.remote.off.permission";
            case WRONG_VERSION -> "chunkeditor.remote.off.version";
            default -> "chunkeditor.remote.off.missing";
        };
    }
}
