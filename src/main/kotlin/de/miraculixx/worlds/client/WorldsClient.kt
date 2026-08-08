package de.miraculixx.worlds.client

import de.miraculixx.worlds.client.ui.WorldsScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component

class WorldsClient : ClientModInitializer {

    override fun onInitializeClient() {
        ModUpdate.check()

        ScreenEvents.AFTER_INIT.register { _, screen, scaledWidth, _ ->
            if (screen !is TitleScreen) return@register
            val widgets = Screens.getWidgets(screen)

            // Replace single player button
            val index = widgets.indexOfFirst { it.message == Component.translatable("menu.singleplayer") }
            val slot = widgets.getOrNull(index)
            val bx = slot?.x ?: (scaledWidth / 2 - 100)
            val by = slot?.y ?: 96
            val bw = slot?.width ?: 200

            val button = Button.builder(Component.translatable("worlds.menu.worlds")) {
                Minecraft.getInstance().gui.setScreen(WorldsScreen(screen))
            }.bounds(bx, by, bw, 20).build()

            if (slot != null) widgets[index] = button else widgets.add(button)
        }
        // Per-world resource-pack loading is handled by WorldOpenFlowsMixin (piggy-backs vanilla's
        // bundled-pack load), so no join/disconnect hooks are needed here.
    }
}
