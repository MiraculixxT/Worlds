package de.miraculixx.worlds.mixin;

import de.miraculixx.worlds.client.ui.WorldsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Relink/rebrand the singleplayer button in-place
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @ModifyArgs(
            method = "createNormalMenuOptions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/Button;builder(Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/components/Button$OnPress;)Lnet/minecraft/client/gui/components/Button$Builder;",
                    ordinal = 0
            )
    )
    private void worlds_replaceSingleplayerButton(Args args) {
        Screen screen = (Screen) (Object) this;
        args.set(0, Component.translatable("worlds.menu.worlds"));
        args.set(1, (Button.OnPress) button -> Minecraft.getInstance().gui.setScreen(new WorldsScreen(screen)));
    }
}
