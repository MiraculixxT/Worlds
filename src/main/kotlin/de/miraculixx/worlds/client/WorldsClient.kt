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
        ScreenEvents.AFTER_INIT.register { _, screen, scaledWidth, _ ->
            if (screen !is TitleScreen) return@register
            val widgets = Screens.getWidgets(screen)

            // Slot the Maps button in as a new row directly under Singleplayer, matching its size.
            val singleplayer = widgets.firstOrNull { it.message == Component.translatable("menu.singleplayer") }
            val bx: Int; val by: Int; val bw: Int
            if (singleplayer != null) {
                bx = singleplayer.x; bw = singleplayer.width
                by = singleplayer.y + 24
                // Push everything at/below the insertion row down by one row to make space.
                widgets.forEach { if (it.y >= by) it.y = it.y + 24 }
            } else {
                bw = 200; bx = scaledWidth / 2 - 100; by = 96
            }

            val button = Button.builder(Component.literal("Maps")) {
                Minecraft.getInstance().gui.setScreen(WorldsScreen(screen))
            }.bounds(bx, by, bw, 20).build()
            widgets.add(button)
        }
        // Per-world resource-pack loading is handled by WorldOpenFlowsMixin (piggy-backs vanilla's
        // bundled-pack load), so no join/disconnect hooks are needed here.
    }
}
