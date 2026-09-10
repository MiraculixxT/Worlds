package de.miraculixx.showmyworld.mixin;

import de.miraculixx.showmyworld.ShowMyWorld;
import de.miraculixx.common.client.ui.IconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Panorama preview in the vanilla world list, plus the settings button this mod is reached
 * through when Worlds is not installed
 */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {
    @Unique
    private static final ResourceLocation SHOWMYWORLD$ICON = ResourceLocation.fromNamespaceAndPath("showmyworld", "menu");
    @Unique
    private static final int SHOWMYWORLD$BUTTON_SIZE = 20;
    @Unique
    private static final int SHOWMYWORLD$MARGIN = 6;

    @Shadow
    private WorldSelectionList list;

    @Unique
    private IconButton showmyworld$button;

    /** 1.21's {@code WorldListEntry} exposes no summary, so the pushed selection is remembered here */
    @Unique
    private String showmyworld$selected;

    private SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "updateButtonStatus", at = @At("HEAD"))
    private void showmyworld$select(LevelSummary summary, CallbackInfo ci) {
        showmyworld$selected = summary == null ? null : summary.getLevelId();
        ShowMyWorld.INSTANCE.select(showmyworld$selected);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void showmyworld$deselect(CallbackInfo ci) {
        ShowMyWorld.INSTANCE.select(null);
    }

    /**
     * 1.21's has no {@code repositionElements}, so a resize is managed by Screens default
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void showmyworld$addButton(CallbackInfo ci) {
        showmyworld$button = new IconButton(
                0, 0, SHOWMYWORLD$BUTTON_SIZE,
                Component.translatable("showmyworld.settings.title"), SHOWMYWORLD$ICON,
                this::showmyworld$openSettings
        );
        showmyworld$button.setTooltip(Tooltip.create(Component.translatable("showmyworld.settings.title")));
        showmyworld$place();
        addRenderableWidget(showmyworld$button);
    }

    @Unique
    private void showmyworld$openSettings() {
        String selected = showmyworld$selected;
        ShowMyWorld.INSTANCE.openSettings(() -> this.minecraft.setScreen((Screen) (Object) this));
        ShowMyWorld.INSTANCE.select(selected);
    }

    @Unique
    private void showmyworld$place() {
        showmyworld$button.setPosition(this.width - SHOWMYWORLD$BUTTON_SIZE - SHOWMYWORLD$MARGIN, SHOWMYWORLD$MARGIN);
    }
}
