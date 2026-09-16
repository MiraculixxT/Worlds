package de.miraculixx.chunkeditor.mixin;

import de.miraculixx.chunkeditor.ChunkEditor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The button for servers (and technically single player) to open the live map
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Unique
    private static final int CHUNKEDITOR_BUTTON_W = 98;
    @Unique
    private static final int CHUNKEDITOR_BUTTON_H = 20;
    @Unique
    private static final int CHUNKEDITOR_MARGIN = 8;

    private PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void chunkeditor$addButton(CallbackInfo ci) {
        if (!((PauseScreen) (Object) this).showsPauseMenu()) return;
        ChunkEditor.RemoteState state = ChunkEditor.INSTANCE.remoteState();
        Button button = Button
                .builder(Component.translatable("chunkeditor.remote.open"), b -> ChunkEditor.INSTANCE.openRemote(this))
                .bounds(this.width - CHUNKEDITOR_MARGIN - CHUNKEDITOR_BUTTON_W, CHUNKEDITOR_MARGIN,
                        CHUNKEDITOR_BUTTON_W, CHUNKEDITOR_BUTTON_H)
                .build();
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
