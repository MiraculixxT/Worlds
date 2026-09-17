package de.miraculixx.chunkeditor.mixin;

import de.miraculixx.chunkeditor.ChunkEditor;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Icon button in the pause menu's icon row to open the live map
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Unique
    private static final Identifier CHUNKEDITOR_REMOTE_SPRITE = Identifier.fromNamespaceAndPath("chunkeditor", "remote_icon");

    private PauseScreenMixin(Component title) {
        super(title);
    }

    @ModifyArg(method = "createPauseMenu", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
            index = 0)
    private LayoutElement chunkeditor$addButton(LayoutElement element) {
        if (!(element instanceof LinearLayout iconRow)) return element;
        ChunkEditor.RemoteState state = ChunkEditor.INSTANCE.remoteState();
        SpriteIconButton button = SpriteIconButton
                .builder(Component.translatable("chunkeditor.remote.open"), b -> ChunkEditor.INSTANCE.openRemote(this), true)
                .width(20)
                .sprite(CHUNKEDITOR_REMOTE_SPRITE, 16, 16)
                .withTootip()
                .build();
        button.active = state == ChunkEditor.RemoteState.READY;
        if (!button.active) button.setTooltip(Tooltip.create(Component.translatable(chunkeditor$reason(state))));
        iconRow.addChild(button);
        return element;
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
