package de.miraculixx.showmyworld.client.ui

import de.miraculixx.common.client.ui.NumberField
import de.miraculixx.common.client.ui.SettingsCategory
import de.miraculixx.common.client.ui.SettingsList
import de.miraculixx.common.client.ui.WIDGET_W
import de.miraculixx.showmyworld.client.PreviewConfig
import de.miraculixx.showmyworld.client.PreviewSettings
import de.miraculixx.showmyworld.client.ui.panorama.DefaultPanorama
import de.miraculixx.showmyworld.client.ui.panorama.WorldPanorama
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Edits apply immediately, the file is written once in [onClose].
 */
class PreviewSettingsScreen(private val onDone: Runnable) : Screen(Component.translatable("showmyworld.settings.title")) {

    constructor(parent: Screen) : this(Runnable { Minecraft.getInstance().gui.setScreen(parent) })

    private val settings = PreviewConfig.settings

    private val categories = listOf(
        SettingsCategory(I18n.get("showmyworld.settings.panorama")).apply { expanded = true },
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

    /** Commits first: [SettingsList.rebuild] commits too, writing the old text back over the reset. */
    private fun resetAll() {
        list.commitEdits()
        val defaults = PreviewSettings()
        settings.show = defaults.show
        settings.autoCreate = defaults.autoCreate
        settings.default = defaults.default
        settings.fade = defaults.fade
        list.rebuild()
        WorldPanorama.refresh()
    }

    private fun rowsFor(category: SettingsCategory): List<SettingsList.Row> = listOf(
        list.ToggleRow(I18n.get("showmyworld.settings.show"), settings.show) {
            settings.show = it
            WorldPanorama.refresh()
        },
        list.ToggleRow(I18n.get("showmyworld.settings.auto_create"), settings.autoCreate) {
            settings.autoCreate = it
        },
        list.CycleRow(
            I18n.get("showmyworld.settings.default"),
            DefaultPanorama.entries,
            settings.default,
            { Component.literal(it.label) },
        ) {
            settings.default = it
            WorldPanorama.refresh()
        },
        list.NumberRow(
            I18n.get("showmyworld.settings.fade"),
            listOf(NumberField(font, WIDGET_W, settings.fade, FADE_RANGE) { settings.fade = it }),
        ),
    )

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, 12, -1)
    }

    override fun onClose() {
        list.commitEdits()
        PreviewConfig.save()
        onDone.run()
    }

    private companion object {
        const val PANEL_W = 320
        const val LIST_TOP = 30
        val FADE_RANGE = 0L..10_000L
    }
}
