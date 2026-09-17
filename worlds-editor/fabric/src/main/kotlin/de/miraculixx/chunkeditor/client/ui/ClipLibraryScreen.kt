package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.ChunkClips
import de.miraculixx.chunkeditor.data.ClipEntry
import de.miraculixx.chunkeditor.data.ClipLibrary
import de.miraculixx.chunkeditor.data.SelectionEntry
import de.miraculixx.common.client.ui.HOVER_COLOR
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.drawBox
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.components.tabs.GridLayoutTab
import net.minecraft.client.gui.components.tabs.MenuTabBar
import net.minecraft.client.gui.components.tabs.TabManager
import net.minecraft.client.gui.components.tabs.Tab as GuiTab
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.function.Consumer

private const val MARGIN = 16
private const val LIST_TOP = 32
private const val ROW_H = 26
private const val SELECTED_COLOR = 0x5033B5E5

/** In the world list format, `?` for unknown */
internal fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "?"
    return WorldSelectionList.DATE_FORMAT.format(
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
    )
}

/** What an export writes: the chunks themselves, the list of which ones are selected, or both */
enum class ExportKind { CLIP, SELECTION }

/** Asks for the name before anything is exported */
internal class ClipNameScreen(
    private val parent: Screen,
    private val suggestion: String,
    private val onAccept: (String, Set<ExportKind>) -> Unit,
) : Screen(Component.translatable("chunkeditor.clip.export_title")) {

    private val panelW = 260
    private var panelTop = 0
    private var panelBottom = 0
    private lateinit var field: EditBox
    private lateinit var clipToggle: Checkbox
    private lateinit var selectionToggle: Checkbox
    private lateinit var acceptButton: Button
    private var swallowChars = true

    override fun init() {
        val left = width / 2 - panelW / 2 + 10
        val fieldW = panelW - 20
        val top = height / 2 - 54
        panelTop = top - 26
        panelBottom = top + 106

        field = addRenderableWidget(EditBox(font, left, top, fieldW, 20, title))
        field.value = suggestion
        field.setMaxLength(64)
        setInitialFocus(field)

        clipToggle = addRenderableWidget(toggle(left, top + 26, "chunkeditor.clip.export_clip", true))
        selectionToggle = addRenderableWidget(toggle(left, top + 50, "chunkeditor.clip.export_selection", false))

        addRenderableWidget(
            guideButton(
                width / 2 + panelW / 2 - 8 - GUIDE_SIZE, panelTop + 6, "export",
                "Tip: Exports land in '<instance>/chunkclips/<name>'",
            )
        )
        acceptButton = addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.clip.export")) { accept() }
                .bounds(left, top + 80, panelW / 2 - 12, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + 2, top + 80, panelW / 2 - 12, 20).build()
        )
        syncAccept()
    }

    private fun toggle(x: Int, y: Int, key: String, initial: Boolean): Checkbox =
        Checkbox.builder(Component.translatable(key), font)
            .pos(x, y).selected(initial).onValueChange { _, _ -> syncAccept() }.build()

    private fun kinds(): Set<ExportKind> = buildSet {
        if (clipToggle.selected()) add(ExportKind.CLIP)
        if (selectionToggle.selected()) add(ExportKind.SELECTION)
    }

    private fun syncAccept() {
        acceptButton.active = kinds().isNotEmpty()
    }

    private fun accept() {
        val name = field.value.trim()
        val kinds = kinds()
        if (name.isEmpty() || kinds.isEmpty()) return
        onAccept(ChunkClips.sanitize(name), kinds)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isConfirmation) {
            accept()
            return true
        }
        return super.keyPressed(event)
    }

    /**
     * Avoid entering the pressed shortcut into a text field
     */
    override fun charTyped(event: CharacterEvent): Boolean {
        if (swallowChars) {
            swallowChars = false
            return true
        }
        return super.charTyped(event)
    }

    override fun tick() {
        swallowChars = false
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
 * Everything a [ClipLibrary] holds (local or remote)
 * @param pickLabel what the pick button says (import or upload)
 * @param selectionLabel the same on the selections tab
 * @param onPickSelection null hides the selections tab, for a browse that is only about clips
 */
internal class ClipLibraryScreen(
    private val parent: Screen,
    private val library: ClipLibrary,
    private val pickLabel: Component,
    private val onPickClip: (ClipEntry) -> Unit,
    private val onPickSelection: ((SelectionEntry) -> Unit)?,
    private val selectionLabel: Component = Component.translatable("chunkeditor.clip.select"),
) : Screen(Component.translatable("chunkeditor.clip.library_title")) {

    private enum class Tab(val key: String) {
        CLIPS("chunkeditor.clip.tab.clips"),
        SELECTIONS("chunkeditor.clip.tab.selections"),
    }

    private var tab = Tab.CLIPS
    private val pages = if (onPickSelection == null) listOf(Tab.CLIPS) else Tab.entries
    private val tabPages = pages.map { GridLayoutTab(Component.translatable(it.key)) }
    private val tabManager = TabManager(
        { addRenderableWidget(it) },
        { removeWidget(it) },
        tabConsumer { page -> if (page != null) onTabSelected(page) },
        tabConsumer { },
    )
    private lateinit var tabBar: MenuTabBar

    private var clips: List<ClipEntry> = emptyList()
    private var selections: List<SelectionEntry> = emptyList()
    private var loading = true
    private var needsLoad = true

    private lateinit var list: LibraryList
    private lateinit var importButton: Button
    private lateinit var deleteButton: Button
    private var busy = false

    override fun init() {
        tabBar = addRenderableWidget(
            MenuTabBar.builder(tabManager, width).addTabs(*tabPages.toTypedArray()).build()
        )
        tabBar.arrangeElements(width)
        tabBar.selectTab(tab.ordinal, false)

        list = addRenderableWidget(LibraryList(minecraft, width - 2 * MARGIN, listBottom() - LIST_TOP, LIST_TOP))
        list.updateSizeAndPosition(width - 2 * MARGIN, listBottom() - LIST_TOP, MARGIN, LIST_TOP)
        list.fill()

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
        // Can't open a remote folder :D
        if (library.folder != null) {
            addRenderableWidget(
                Button.builder(Component.translatable("chunkeditor.clip.open_folder")) { openFolder() }
                    .bounds(width - MARGIN - 184, y, 100, 20).build()
            )
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width - MARGIN - 80, y, 80, 20).build()
        )
        syncButtons()
    }

    /** `oldTab` is null on the first selection */
    private fun tabConsumer(action: (GuiTab?) -> Unit): Consumer<GuiTab> =
        @Suppress("UNCHECKED_CAST") (Consumer<GuiTab?> { action(it) } as Consumer<GuiTab>)

    private fun onTabSelected(page: GuiTab) {
        if (!::list.isInitialized) return
        tab = pages[tabPages.indexOf(page).coerceAtLeast(0)]
        list.fill()
        syncButtons()
    }

    override fun keyPressed(event: KeyEvent): Boolean =
        tabBar.keyPressed(event) || super.keyPressed(event)

    private fun listBottom() = height - 40

    private fun load() {
        Constants.SCOPE.launch {
            val foundClips = runCatching { library.clips() }.getOrDefault(emptyList())
            val foundSelections =
                if (onPickSelection == null) emptyList()
                else runCatching { library.selections() }.getOrDefault(emptyList())
            minecraft.execute {
                clips = foundClips
                selections = foundSelections
                loading = false
                list.fill()
                syncButtons()
            }
        }
    }

    private fun syncButtons() {
        val selected = list.selected != null && !busy
        importButton.active = selected
        deleteButton.active = selected
        importButton.message =
            if (tab == Tab.CLIPS) pickLabel else selectionLabel
    }

    private fun pick() {
        if (busy) return
        when (val row = list.selected) {
            is LibraryList.ClipRow -> onPickClip(row.clip)
            is LibraryList.SelectionRow -> onPickSelection?.invoke(row.selection)
            else -> {}
        }
    }

    private fun confirmDelete() {
        val row = list.selected ?: return
        minecraft.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) delete(row)
                    minecraft.gui.setScreen(this)
                },
                Component.translatable("chunkeditor.clip.delete_title", row.title),
                Component.translatable(
                    if (row is LibraryList.ClipRow) "chunkeditor.clip.delete_warning"
                    else "chunkeditor.clip.delete_selection_warning"
                ),
            )
        )
    }

    private fun delete(row: LibraryList.Row) {
        busy = true
        Constants.SCOPE.launch {
            runCatching {
                when (row) {
                    is LibraryList.ClipRow -> library.deleteClip(row.clip.name)
                    is LibraryList.SelectionRow -> library.deleteSelection(row.selection.name)
                }
            }
            minecraft.execute {
                busy = false
                loading = true
                load()
            }
        }
    }

    private fun openFolder() {
        val dir = (if (tab == Tab.CLIPS) library.folder else library.selectionFolder) ?: return
        runCatching { Files.createDirectories(dir) }
        Util.getPlatform().openPath(dir)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val empty = if (tab == Tab.CLIPS) clips.isEmpty() else selections.isEmpty()
        if (loading || empty) {
            val key = when {
                loading -> "chunkeditor.map.reading"
                tab == Tab.CLIPS -> "chunkeditor.clip.empty"
                else -> "chunkeditor.clip.empty_selections"
            }
            graphics.text(font, I18n.get(key), MARGIN + 8, LIST_TOP + 10, SUBTEXT_COLOR)
        }
    }

    override fun onClose() = minecraft.gui.setScreen(parent)

    /** Rows carry no widgets, so the plain selection list is enough */
    inner class LibraryList(minecraft: Minecraft, width: Int, height: Int, y: Int) :
        ObjectSelectionList<LibraryList.Row>(minecraft, width, height, y, ROW_H) {

        /** Rebuilds from whichever tab is open, keeping the selection when the same row is still there */
        fun fill() {
            val previous = selected?.title
            val rows: List<Row> =
                if (tab == Tab.CLIPS) clips.map { ClipRow(it) } else selections.map { SelectionRow(it) }
            replaceEntries(rows)
            setSelected(children().firstOrNull { it.title == previous })
        }

        override fun setSelected(entry: Row?) {
            super.setSelected(entry)
            if (::importButton.isInitialized) syncButtons()
        }

        override fun getRowWidth(): Int = width - 12

        override fun scrollBarX(): Int = x + width - 8

        override fun extractListBackground(graphics: GuiGraphicsExtractor) =
            drawBox(graphics, x, y, x + width, y + height)

        override fun extractListSeparators(graphics: GuiGraphicsExtractor) = Unit

        abstract inner class Row : Entry<Row>() {
            abstract val title: String
            abstract val subtitle: String

            override fun getNarration(): Component = Component.literal(title)

            override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                this@LibraryList.setSelected(this)
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
                if (this@LibraryList.selected === this) {
                    graphics.fill(contentX - 2, contentY - 2, contentRight + 2, contentBottom + 2, SELECTED_COLOR)
                } else if (hovered) {
                    graphics.fill(contentX - 2, contentY - 2, contentRight + 2, contentBottom + 2, HOVER_COLOR)
                }
                graphics.text(minecraft.font, title, contentX + 2, contentY + 2, -1)
                graphics.text(minecraft.font, subtitle, contentX + 2, contentY + 13, SUBTEXT_COLOR)
            }
        }

        inner class ClipRow(val clip: ClipEntry) : Row() {
            override val title: String get() = clip.name
            override val subtitle: String
                get() = I18n.get(
                    "chunkeditor.clip.info", clip.chunks, clip.dimension, clip.mcVersion,
                    bytes(clip.bytes), formatDate(clip.modified),
                )
        }

        inner class SelectionRow(val selection: SelectionEntry) : Row() {
            override val title: String get() = selection.name
            override val subtitle: String
                get() = I18n.get(
                    if (selection.inverted) "chunkeditor.clip.selection_info_inverted"
                    else "chunkeditor.clip.selection_info",
                    selection.chunks, bytes(selection.bytes), formatDate(selection.modified),
                )
        }
    }

    private fun bytes(value: Long): String = when {
        value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
        value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
        else -> "%.0f KB".format(value / 1024.0)
    }
}
