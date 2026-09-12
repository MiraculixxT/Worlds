package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.ChunkClips
import de.miraculixx.chunkeditor.data.ClipInfo
import de.miraculixx.common.client.ui.HOVER_COLOR
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.drawBox
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.nio.file.Files

private const val MARGIN = 16
private const val LIST_TOP = 40
private const val ROW_H = 26
private const val SELECTED_COLOR = 0x5033B5E5

/** Asks for the clip's folder name before an export runs. */
internal class ClipNameScreen(
    private val parent: Screen,
    private val suggestion: String,
    private val onAccept: (String) -> Unit,
) : Screen(Component.translatable("chunkeditor.clip.export_title")) {

    private val panelW = 260
    private var panelTop = 0
    private var panelBottom = 0
    private lateinit var field: EditBox

    override fun init() {
        val left = width / 2 - panelW / 2 + 10
        val top = height / 2 - 30
        panelTop = top - 26
        panelBottom = top + 60

        field = addRenderableWidget(EditBox(font, left, top, panelW - 20, 20, title))
        field.value = suggestion
        field.setMaxLength(64)
        setInitialFocus(field)

        addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.clip.export")) { accept() }
                .bounds(left, top + 30, panelW / 2 - 12, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + 2, top + 30, panelW / 2 - 12, 20).build()
        )
    }

    private fun accept() {
        val name = field.value.trim()
        if (name.isEmpty()) return
        onAccept(ChunkClips.sanitize(name))
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isConfirmation) {
            accept()
            return true
        }
        return super.keyPressed(event)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        drawBox(graphics, width / 2 - panelW / 2, panelTop, width / 2 + panelW / 2, panelBottom)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.text(font, title.copy().withStyle { it.withBold(true) }, width / 2 - panelW / 2 + 10, panelTop + 9, -1)
    }

    override fun onClose() = minecraft.gui.setScreen(parent)
}

/**
 * The clips in `<gamedir>/chunkclips`
 */
internal class ClipLibraryScreen(
    private val parent: Screen,
    private val onPick: (ClipInfo) -> Unit,
) : Screen(Component.translatable("chunkeditor.clip.library_title")) {

    private var clips: List<ClipInfo> = emptyList()
    private var loading = true
    // init re-runs on every resize; the library is only read when something actually changed.
    private var needsLoad = true

    private lateinit var list: ClipList
    private lateinit var importButton: Button
    private lateinit var deleteButton: Button

    override fun init() {
        list = addRenderableWidget(ClipList(minecraft, width - 2 * MARGIN, listBottom() - LIST_TOP, LIST_TOP))
        list.updateSizeAndPosition(width - 2 * MARGIN, listBottom() - LIST_TOP, MARGIN, LIST_TOP)
        list.setClips(clips)

        if (needsLoad) {
            needsLoad = false
            load()
        }
        val y = height - 32
        importButton = addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.clip.import")) { pick() }
                .bounds(MARGIN, y, 100, 20).build()
        )
        deleteButton = addRenderableWidget(
            Button.builder(Component.translatable("selectWorld.delete")) { confirmDelete() }
                .bounds(MARGIN + 104, y, 100, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.clip.open_folder")) { openFolder() }
                .bounds(width - MARGIN - 184, y, 100, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width - MARGIN - 80, y, 80, 20).build()
        )
        syncButtons()
    }

    private fun listBottom() = height - 40

    private fun load() {
        Constants.SCOPE.launch {
            val found = ChunkClips.list()
            minecraft.execute {
                clips = found
                loading = false
                list.setClips(found)
                syncButtons()
            }
        }
    }

    private fun syncButtons() {
        val selected = list.selected != null
        importButton.active = selected
        deleteButton.active = selected
    }

    private fun pick() {
        onPick(list.selected?.clip ?: return)
    }

    private fun confirmDelete() {
        val clip = list.selected?.clip ?: return
        minecraft.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        ChunkClips.delete(clip)
                        loading = true
                        needsLoad = true
                    }
                    minecraft.gui.setScreen(this)
                },
                Component.translatable("chunkeditor.clip.delete_title", clip.name),
                Component.translatable("chunkeditor.clip.delete_warning"),
            )
        )
    }

    private fun openFolder() {
        val dir = ChunkClips.libraryDir()
        runCatching { Files.createDirectories(dir) }
        Util.getPlatform().openPath(dir)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.text(font, title, MARGIN, 16, -1)
        if (loading || clips.isEmpty()) {
            val key = if (loading) "chunkeditor.map.reading" else "chunkeditor.clip.empty"
            graphics.text(font, I18n.get(key), MARGIN + 8, LIST_TOP + 10, SUBTEXT_COLOR)
        }
    }

    override fun onClose() = minecraft.gui.setScreen(parent)

    /** Rows carry no widgets, so the plain selection list is enough. */
    inner class ClipList(minecraft: Minecraft, width: Int, height: Int, y: Int) :
        ObjectSelectionList<ClipList.ClipRow>(minecraft, width, height, y, ROW_H) {

        fun setClips(clips: List<ClipInfo>) {
            val previous = selected?.clip?.dir
            replaceEntries(clips.map { ClipRow(it) })
            setSelected(children().firstOrNull { it.clip.dir == previous })
        }

        override fun setSelected(entry: ClipRow?) {
            super.setSelected(entry)
            if (::importButton.isInitialized) syncButtons()
        }

        override fun getRowWidth(): Int = width - 12

        override fun scrollBarX(): Int = x + width - 8

        override fun extractListBackground(graphics: GuiGraphicsExtractor) =
            drawBox(graphics, x, y, x + width, y + height)

        override fun extractListSeparators(graphics: GuiGraphicsExtractor) = Unit

        inner class ClipRow(val clip: ClipInfo) : Entry<ClipRow>() {
            override fun getNarration(): Component = Component.literal(clip.name)

            override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                this@ClipList.setSelected(this)
                if (doubleClick) pick()
                return true
            }

            override fun keyPressed(event: KeyEvent): Boolean {
                if (!event.isConfirmation) return false
                pick()
                return true
            }

            override fun extractContent(
                graphics: GuiGraphicsExtractor,
                mouseX: Int,
                mouseY: Int,
                hovered: Boolean,
                partialTick: Float,
            ) {
                if (this@ClipList.selected === this) {
                    graphics.fill(contentX - 2, contentY - 2, contentRight + 2, contentBottom + 2, SELECTED_COLOR)
                } else if (hovered) {
                    graphics.fill(contentX - 2, contentY - 2, contentRight + 2, contentBottom + 2, HOVER_COLOR)
                }
                graphics.text(minecraft.font, clip.name, contentX + 2, contentY + 2, -1)
                graphics.text(minecraft.font, subtitle(clip), contentX + 2, contentY + 13, SUBTEXT_COLOR)
            }
        }
    }

    private fun subtitle(clip: ClipInfo) = I18n.get(
        "chunkeditor.clip.info", clip.chunks.size, clip.dimension, clip.mcVersion, bytes(clip.bytes),
    )

    private fun bytes(value: Long): String = when {
        value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
        value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
        else -> "%.0f KB".format(value / 1024.0)
    }
}
