package de.miraculixx.chunkeditor.client

import de.miraculixx.chunkeditor.ChunkEditor
import de.miraculixx.chunkeditor.mixin.EditWorldScreenAccessor
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen
import net.minecraft.network.chat.Component

private const val BUTTON_W = 200
private const val BUTTON_H = 20

/** Distance from the bottom edge, one row above vanilla's Save / Cancel pair. */
private const val BUTTON_BOTTOM_OFFSET = 52

/**
 * Hangs the editor off vanilla's [EditWorldScreen].
 */
object ChunkEditorClient : ClientModInitializer {
    override fun onInitializeClient() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, height ->
            if (screen !is EditWorldScreen) return@register
            val access = (screen as EditWorldScreenAccessor).`chunkeditor$levelAccess`()
            Screens.getWidgets(screen).add(
                Button.builder(Component.translatable("chunkeditor.open")) { ChunkEditor.open(screen, access) }
                    .bounds(
                        (screen.width - BUTTON_W) / 2,
                        height - BUTTON_BOTTOM_OFFSET,
                        BUTTON_W,
                        BUTTON_H,
                    )
                    .build()
            )
        }
    }
}
