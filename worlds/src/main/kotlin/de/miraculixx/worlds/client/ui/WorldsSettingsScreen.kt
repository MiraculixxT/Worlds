package de.miraculixx.worlds.client.ui

import de.miraculixx.common.client.ui.NumberField
import de.miraculixx.common.client.ui.SettingsCategory
import de.miraculixx.common.client.ui.SettingsList
import de.miraculixx.common.client.ui.WIDGET_W
import de.miraculixx.worlds.client.WorldsConfig
import de.miraculixx.worlds.client.ui.panorama.DefaultPanorama
import de.miraculixx.worlds.client.ui.panorama.WorldPanorama
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component


class WorldsSettingsScreen(private val parent: Screen) : Screen(Component.translatable("worlds.settings.title")) {

    private val panorama = WorldsConfig.settings.panorama

    private val categories = listOf(
        SettingsCategory(I18n.get("worlds.settings.panorama")).apply { expanded = true },
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
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20).build()
        )
    }

    private fun rowsFor(category: SettingsCategory): List<SettingsList.Row> =
        when (categories.indexOf(category)) {
            0 -> panoramaRows()
            else -> emptyList()
        }

    private fun panoramaRows(): List<SettingsList.Row> = listOf(
        list.ToggleRow(I18n.get("worlds.settings.panorama.show"), panorama.show) {
            panorama.show = it
            WorldPanorama.refresh()
        },
        list.ToggleRow(I18n.get("worlds.settings.panorama.auto_create"), panorama.autoCreate) {
            panorama.autoCreate = it
        },
        list.CycleRow(
            I18n.get("worlds.settings.panorama.default"),
            DefaultPanorama.entries,
            panorama.default,
            { Component.literal(it.label) },
        ) {
            panorama.default = it
            WorldPanorama.refresh()
        },
        list.NumberRow(
            I18n.get("worlds.settings.panorama.fade"),
            listOf(NumberField(font, WIDGET_W, panorama.fade, FADE_RANGE) { panorama.fade = it }),
        ),
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
        val FADE_RANGE = 0L..10_000L
    }
}
