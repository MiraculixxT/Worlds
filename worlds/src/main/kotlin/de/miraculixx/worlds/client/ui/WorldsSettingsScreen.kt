package de.miraculixx.worlds.client.ui

import de.miraculixx.common.client.ui.SettingsCategory
import de.miraculixx.common.client.ui.SettingsList
import de.miraculixx.showmyworld.ShowMyWorld
import de.miraculixx.worlds.client.DisplaySettings
import de.miraculixx.worlds.client.WorldsConfig
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component


class WorldsSettingsScreen(private val parent: Screen) : Screen(Component.translatable("worlds.settings.title")) {

    private val display = WorldsConfig.settings.display

    /** The panorama is its own mod, so its category is a leaf redirecting into that mod's screen. */
    private val categories = listOf(
        SettingsCategory(I18n.get("worlds.settings.display")).apply { expanded = true },
        SettingsCategory(I18n.get("worlds.settings.panorama")) { ShowMyWorld.openSettings(this) },
    )

    private lateinit var list: SettingsList

    override fun init() {
        val listW = (width - 40).coerceAtMost(PANEL_W)
        val listX = (width - listW) / 2
        list = SettingsList(minecraft, categories, ::rowsFor)
        list.updateSizeAndPosition(listW, height - 34 - LIST_TOP, listX, LIST_TOP)
        list.rebuild()
        addRenderableWidget(list)

        addRenderableWidget(
            Button.builder(Component.translatable("controls.reset")) { resetAll() }
                .bounds(width / 2 - 100, height - 28, 98, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width / 2 + 2, height - 28, 98, 20).build()
        )
    }

    private fun resetAll() {
        list.commitEdits()
        display.showPacks = DisplaySettings().showPacks
        list.rebuild()
    }

    private fun rowsFor(category: SettingsCategory): List<SettingsList.Row> =
        when (categories.indexOf(category)) {
            0 -> displayRows()
            else -> emptyList()
        }

    private fun displayRows(): List<SettingsList.Row> = listOf(
        list.ToggleRow(I18n.get("worlds.settings.display.show_packs"), display.showPacks) {
            display.showPacks = it
        },
    )

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, 12, -1)
    }

    override fun onClose() {
        list.commitEdits()
        WorldsConfig.save()
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        const val PANEL_W = 320
        const val LIST_TOP = 30
    }
}
