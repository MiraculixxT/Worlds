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
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is TitleScreen) return@register
            val button = Button.builder(Component.literal("Maps")) {
                Minecraft.getInstance().gui.setScreen(WorldsScreen(screen))
            }.bounds(8, 8, 60, 20).build()
            Screens.getWidgets(screen).add(button)
        }
    }
}
