package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.data.EditorConfig
import de.miraculixx.chunkeditor.data.EditorSettings
import de.miraculixx.common.client.ui.NativeDialogs
import de.miraculixx.common.client.ui.SettingsCategory
import de.miraculixx.common.client.ui.SettingsList
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Util

/**
 * Edits apply immediately, the file is written once in [onClose]
 */
class EditorSettingsScreen(private val parent: Screen?) : Screen(Component.translatable("chunkeditor.settings.title")) {

    private val settings = EditorConfig.settings

    private val general = SettingsCategory("General").apply { expanded = true }
    private val library = SettingsCategory("Clip Library").apply { expanded = true }

    private val categories = listOf(general, library)

    private lateinit var list: SettingsList

    /** Shown in red under the title until the next successful change */
    private var warning: String? = null

    private var picking = false

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
        warning = null
        val defaults = EditorSettings()
        settings.libraryDir = defaults.libraryDir
        settings.skipOpenWarning = defaults.skipOpenWarning
        list.rebuild()
    }

    private fun rowsFor(category: SettingsCategory): List<SettingsList.Row> = when (category) {
        general -> listOf(
            list.ToggleRow("Skip Opening Warnings", settings.skipOpenWarning) { settings.skipOpenWarning = it },
        )

        else -> listOf(
            list.TextRow("Change Folder", shorten(EditorConfig.libraryDir().toString()), Component.literal("Select")) {
                pickFolder()
            },
            list.TextRow("Library Folder", "", Component.literal("Open")) {
                Util.getPlatform().openPath(EditorConfig.libraryDir())
            },
        )
    }

    /**
     * Native folder picker, the screen stays usable while it is open
     */
    private fun pickFolder() {
        if (picking) return
        picking = true
        NativeDialogs.pickFolder("Select the clip library folder", EditorConfig.libraryDir()) { picked ->
            picking = false
            if (picked == null) return@pickFolder
            val path = runCatching { picked.toAbsolutePath().normalize() }.getOrNull()
            if (path == null || !EditorConfig.writable(path)) {
                warning = "That folder cannot be written to - keeping the old one"
                return@pickFolder
            }
            warning = null
            settings.libraryDir = if (path == EditorConfig.defaultLibraryDir.toAbsolutePath().normalize()) "" else path.toString()
            EditorConfig.save()
            list.rebuild()
        }
    }

    /** Right aligned paths run into the label, so keep the tail that fits */
    private fun shorten(path: String): String {
        val max = PANEL_W / 2 - 2
        if (font.width(path) <= max) return path
        var cut = path
        while (cut.isNotEmpty() && font.width("…$cut") > max) cut = cut.substring(1)
        return "…$cut"
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, 12, -1)
        warning?.let { graphics.centeredText(font, it, width / 2, height - 42, -65536) }
    }

    override fun onClose() {
        list.commitEdits()
        EditorConfig.save()
        minecraft.setScreen(parent)
    }

    private companion object {
        const val PANEL_W = 320
        const val LIST_TOP = 30
    }
}
