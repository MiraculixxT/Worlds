package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.client.ui.markdown.Markdown
import de.miraculixx.worlds.client.ui.markdown.MdBlock
import de.miraculixx.worlds.data.InstallResult
import de.miraculixx.worlds.data.MapEntry
import de.miraculixx.worlds.data.MapInstaller
import de.miraculixx.worlds.data.MapRepository
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.Util
import java.util.Optional

/** The in-game map browser: Browse / Installed tabs, list on the left, detail panel on the right. */
class WorldsScreen(private val parent: Screen?) : Screen(Component.literal("Worlds")) {

    private enum class Tab { BROWSE, INSTALLED }

    private var tab = Tab.BROWSE
    private var allEntries: List<MapEntry> = emptyList()
    private var status: String? = null
    private var actionMessage: String? = null

    private var selected: MapEntry? = null
    private var readmeBlocks: List<MdBlock> = emptyList()
    private var readmeScroll = 0.0
    private var readmeContentHeight = 0
    private var scrollbarDragging = false
    private var dragGrabOffset = 0.0

    // Clickable link hit-boxes in the readme, rebuilt every frame (26.2 has no style-at-width test).
    private data class LinkRect(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val url: String)
    private val linkRects = ArrayList<LinkRect>()

    private lateinit var list: MapListWidget
    private lateinit var search: EditBox
    private lateinit var browseTab: Button
    private lateinit var installedTab: Button
    private lateinit var refreshButton: Button

    // Refresh cooldown so we don't hammer the APIs.
    private var lastRefresh = 0L
    private lateinit var primaryButton: Button
    private lateinit var websiteButton: Button
    private lateinit var trailerButton: Button

    // Layout (computed in init).
    private var leftLeft = 8
    private var leftWidth = 200
    private var listTop = 68
    private var listBottom = 0
    private var rightLeft = 0
    private var rightRight = 0
    private var buttonsY = 0
    private var readmeTop = 0

    override fun init() {
        leftLeft = 8
        leftWidth = (width * 0.42).toInt().coerceIn(160, 320)
        val leftRight = leftLeft + leftWidth
        listTop = 68
        listBottom = height - 32
        rightLeft = leftRight + 12
        rightRight = width - 8
        buttonsY = listTop + 54
        readmeTop = buttonsY + 26

        val half = (leftWidth - 4) / 2
        browseTab = addRenderableWidget(
            Button.builder(Component.literal("Browse")) { switchTab(Tab.BROWSE) }
                .bounds(leftLeft, 24, half, 20).build()
        )
        installedTab = addRenderableWidget(
            Button.builder(Component.literal("Installed")) { switchTab(Tab.INSTALLED) }
                .bounds(leftLeft + half + 4, 24, half, 20).build()
        )

        refreshButton = addRenderableWidget(
            Button.builder(Component.literal("Refresh")) { onRefresh() }
                .bounds(rightRight - 70, 24, 70, 20).build()
        )

        search = EditBox(font, leftLeft, 46, leftWidth, 16, Component.literal("Search"))
        search.setHint(Component.literal("Search maps…"))
        search.setResponder { applyFilter() }
        addRenderableWidget(search)

        list = MapListWidget(minecraft, leftWidth, listBottom - listTop, listTop, ::onSelect)
        list.updateSizeAndPosition(leftWidth, listBottom - listTop, leftLeft, listTop)
        addRenderableWidget(list)

        val bw = ((rightRight - rightLeft - 8) / 3).coerceIn(50, 100)
        primaryButton = addRenderableWidget(
            Button.builder(Component.literal("Install")) { onPrimary() }
                .bounds(rightLeft, buttonsY, bw, 20).build()
        )
        // One link button: the source page (Modrinth/GitHub) is the website when present.
        websiteButton = addRenderableWidget(
            Button.builder(Component.literal("Website")) { openUrl(selected?.linkUrl()) }
                .bounds(rightLeft + bw + 4, buttonsY, bw, 20).build()
        )
        trailerButton = addRenderableWidget(
            Button.builder(Component.literal("Trailer")) { openUrl(selected?.trailerUrl) }
                .bounds(rightLeft + (bw + 4) * 2, buttonsY, bw, 20).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Open Saves Folder")) {
                Util.getPlatform().openPath(minecraft.gameDirectory.toPath().resolve("saves"))
            }.bounds(leftLeft, height - 26, 140, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width - 108, height - 26, 100, 20).build()
        )

        if (allEntries.isEmpty()) loadCurrentTab() else applyFilter()
    }

    private fun switchTab(newTab: Tab) {
        if (tab == newTab) return
        tab = newTab
        selected = null
        readmeBlocks = emptyList()
        search.value = ""
        loadCurrentTab()
    }

    /** Re-fetch the current tab, bypassing caches. No-op while the cooldown is active. */
    private fun onRefresh() {
        if (System.currentTimeMillis() - lastRefresh < REFRESH_COOLDOWN_MS) return
        lastRefresh = System.currentTimeMillis()
        selected = null
        readmeBlocks = emptyList()
        MapRepository.invalidate()
        loadCurrentTab(force = true)
    }

    private fun loadCurrentTab(force: Boolean = false) {
        status = "Loading…"
        allEntries = emptyList()
        list.setEntries(emptyList())
        val loadingTab = tab
        Constants.SCOPE.launch {
            val entries = when (loadingTab) {
                Tab.BROWSE -> MapRepository.loadBrowse(force)
                Tab.INSTALLED -> MapRepository.scanInstalled().map { installed ->
                    MapEntry(
                        id = installed.meta.id,
                        source = installed.meta.source,
                        slug = null,
                        title = installed.meta.title,
                        description = "Installed • ${installed.saveFolder}",
                        iconUrl = installed.meta.icon,
                        mcVersions = emptyList(),
                        categories = emptyList(),
                        website = installed.meta.website,
                        trailerUrl = installed.meta.trailer,
                    ).also {
                        it.installedFolder = installed.saveFolder
                        it.requiredMods = installed.meta.requiredMods
                        it.requiredPacks = installed.meta.requiredPacks
                        it.detailLoaded = true
                    }
                }
            }
            Minecraft.getInstance().execute {
                if (tab != loadingTab) return@execute
                allEntries = entries
                status = if (entries.isEmpty()) {
                    if (loadingTab == Tab.INSTALLED) "No maps installed yet." else "No maps found."
                } else null
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = if (::search.isInitialized) search.value.trim().lowercase() else ""
        val filtered = if (query.isEmpty()) allEntries else allEntries.filter {
            it.title.lowercase().contains(query) ||
                it.description.lowercase().contains(query) ||
                it.categories.any { c -> c.lowercase().contains(query) }
        }
        list.setEntries(filtered)
        if (selected != null && selected !in filtered) selected = null
    }

    private fun onSelect(entry: MapEntry) {
        selected = entry
        readmeScroll = 0.0
        actionMessage = null
        readmeBlocks = Markdown.parse(entry.readmeMarkdown ?: entry.description)
        if (!entry.detailLoaded) {
            Constants.SCOPE.launch {
                MapRepository.loadDetail(entry)
                Minecraft.getInstance().execute {
                    if (selected === entry) readmeBlocks = Markdown.parse(entry.readmeMarkdown ?: entry.description)
                }
            }
        }
    }

    private fun onPrimary() {
        val entry = selected ?: return
        val folder = entry.installedFolder
        if (folder != null) {
            minecraft.createWorldOpenFlows().openWorld(folder) {
                minecraft.gui.setScreen(this)
            }
            return
        }
        actionMessage = "Installing…"
        Constants.SCOPE.launch {
            val result = MapInstaller.install(entry)
            Minecraft.getInstance().execute {
                actionMessage = when (result) {
                    is InstallResult.Success -> "Installed to saves/${result.saveFolder}"
                    is InstallResult.Failure -> result.message
                }
            }
        }
    }

    /** Preferred external link: the source page (Modrinth/GitHub) if set, else the website. */
    private fun MapEntry.linkUrl(): String? = sourceUrl?.takeIf { it.isNotBlank() } ?: website

    private fun openUrl(url: String?) {
        if (!url.isNullOrBlank()) Util.getPlatform().openUri(url)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        updateWidgets()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 8, -1)
        graphics.fill(rightLeft - 6, listTop, rightLeft - 5, listBottom, 0x40FFFFFF)

        val entry = selected
        if (entry == null) {
            graphics.text(font, "Select a map to see details.", rightLeft, listTop + 4, 0xFFA0A0A0.toInt())
        } else {
            drawDetailHeader(graphics, entry)
            drawReadme(graphics, mouseX, mouseY)
        }

        status?.let { graphics.text(font, it, leftLeft + 4, listTop + 12, 0xFFA0A0A0.toInt()) }
        actionMessage?.let { graphics.text(font, it, rightLeft, buttonsY + 22, 0xFFFFE066.toInt()) }
    }

    private fun updateWidgets() {
        val entry = selected
        browseTab.active = tab != Tab.BROWSE
        installedTab.active = tab != Tab.INSTALLED
        val remaining = REFRESH_COOLDOWN_MS - (System.currentTimeMillis() - lastRefresh)
        if (remaining > 0) {
            refreshButton.active = false
            refreshButton.message = Component.literal("${remaining / 1000 + 1}s")
        } else {
            refreshButton.active = true
            refreshButton.message = Component.literal("Refresh")
        }
        primaryButton.visible = entry != null
        websiteButton.visible = entry != null && !entry.linkUrl().isNullOrBlank()
        trailerButton.visible = entry != null && !entry.trailerUrl.isNullOrBlank()
        if (entry != null) {
            primaryButton.message = Component.literal(if (entry.installedFolder != null) "Play" else "Install")
        }
    }

    private fun drawDetailHeader(graphics: GuiGraphicsExtractor, entry: MapEntry) {
        val iconSize = 36
        val icon = MapTextures.get(entry.iconUrl)
        if (icon != null) {
            graphics.blit(
                RenderPipelines.GUI_TEXTURED, icon.id, rightLeft, listTop, 0f, 0f,
                iconSize, iconSize, icon.width, icon.height, icon.width, icon.height,
            )
        } else {
            graphics.fill(rightLeft, listTop, rightLeft + iconSize, listTop + iconSize, 0xFF2A2A2A.toInt())
        }
        val textX = rightLeft + iconSize + 8
        graphics.text(font, Component.literal(entry.title).withStyle { it.withBold(true) }, textX, listTop + 2, -1)
        graphics.textWithWordWrap(font, Component.literal(entry.description), textX, listTop + 14, rightRight - textX, 0xFFB0B0B0.toInt())
        val category = entry.categories.firstOrNull()
        if (category != null) {
            CategoryBadge.draw(graphics, font, category, rightLeft, listTop + iconSize + 2)
        } else {
            graphics.text(font, entry.source.name.lowercase(), rightLeft, listTop + iconSize + 2, 0xFF6699FF.toInt())
        }
    }

    private fun drawReadme(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val bottom = listBottom
        linkRects.clear()
        graphics.enableScissor(rightLeft, readmeTop, rightRight, bottom)
        var y = readmeTop - readmeScroll.toInt()
        val innerWidth = rightRight - rightLeft - (SCROLLBAR_W + 2)
        for (block in readmeBlocks) {
            y += renderBlock(graphics, block, rightLeft, y, innerWidth)
        }
        readmeContentHeight = (y + readmeScroll.toInt()) - readmeTop
        graphics.disableScissor()

        // Clamp scroll now that content height is known.
        val max = (readmeContentHeight - (bottom - readmeTop)).coerceAtLeast(0)
        readmeScroll = readmeScroll.coerceIn(0.0, max.toDouble())

        // Draggable scrollbar when the readme overflows its viewport.
        if (max > 0) {
            val trackX = rightRight - SCROLLBAR_W
            val thumbH = thumbHeight()
            val thumbY = readmeTop + ((readmeViewportH() - thumbH) * (readmeScroll / max)).toInt()
            graphics.fill(trackX, readmeTop, trackX + SCROLLBAR_W, bottom, 0x30FFFFFF)
            val thumbColor = if (scrollbarDragging) 0xFFFFFFFF.toInt() else 0x90FFFFFF.toInt()
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, thumbColor)
        }
    }

    private fun readmeViewportH(): Int = listBottom - readmeTop

    private fun thumbHeight(): Int {
        val vh = readmeViewportH()
        return (vh.toLong() * vh / readmeContentHeight.coerceAtLeast(1)).toInt().coerceIn(20, vh)
    }

    /** Map a desired thumb-top pixel [thumbTop] back to a scroll offset. */
    private fun setScrollFromThumbTop(thumbTop: Double) {
        val maxScroll = (readmeContentHeight - readmeViewportH()).coerceAtLeast(0)
        val range = (readmeViewportH() - thumbHeight()).coerceAtLeast(1)
        val frac = ((thumbTop - readmeTop) / range).coerceIn(0.0, 1.0)
        readmeScroll = frac * maxScroll
    }

    /** Draw a block at (x,y); returns the vertical space it consumed. */
    private fun renderBlock(graphics: GuiGraphicsExtractor, block: MdBlock, x: Int, y: Int, width: Int): Int {
        val lh = font.lineHeight + 1
        return when (block) {
            MdBlock.Spacer -> 4
            MdBlock.Rule -> { graphics.fill(x, y + 3, x + width, y + 4, 0x40FFFFFF); 8 }
            is MdBlock.Heading -> {
                val scale = when (block.level) { 1 -> 1.6f; 2 -> 1.35f; else -> 1.15f }
                val wrapped = font.split(block.text, (width / scale).toInt())
                val pose = graphics.pose()
                pose.pushMatrix()
                pose.translate(x.toFloat(), y.toFloat())
                pose.scale(scale, scale)
                wrapped.forEachIndexed { i, seq -> graphics.text(font, seq, 0, (i * lh), -1) }
                pose.popMatrix()
                (wrapped.size * lh * scale).toInt() + 3
            }
            is MdBlock.Paragraph -> drawWrappedWithLinks(graphics, block.text, x, y, width, 0xFFDDDDDD.toInt()) + 2
            is MdBlock.ListItem -> {
                val indent = 10
                graphics.text(font, block.bullet, x, y, 0xFFDDDDDD.toInt())
                drawWrappedWithLinks(graphics, block.text, x + indent, y, width - indent, 0xFFDDDDDD.toInt()) + 1
            }
            is MdBlock.Code -> {
                val wrapped = font.split(block.text, width)
                graphics.fill(x - 2, y - 1, x + width, y + wrapped.size * lh + 1, 0x40000000)
                wrapped.forEachIndexed { i, seq -> graphics.text(font, seq, x, y + i * lh, 0xFFBBBBBB.toInt()) }
                wrapped.size * lh + 4
            }
            is MdBlock.Image -> {
                val img = MapTextures.get(block.url) ?: return 12
                val drawW = width.coerceAtMost(img.width)
                val drawH = (img.height.toFloat() * drawW / img.width).toInt()
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED, img.id, x, y, 0f, 0f,
                    drawW, drawH, img.width, img.height, img.width, img.height,
                )
                drawH + 4
            }
        }
    }

    /**
     * Draw a word-wrapped [text] at (x,y), preserving inline styles, and record hit-boxes for any
     * spans carrying an [ClickEvent.OpenUrl] so [mouseClicked] can open them. Returns height used.
     */
    private fun drawWrappedWithLinks(
        graphics: GuiGraphicsExtractor, text: Component, x: Int, y: Int, width: Int, color: Int,
    ): Int {
        val lh = font.lineHeight + 1
        val lines: List<FormattedText> = font.splitIgnoringLanguage(text, width)
        lines.forEachIndexed { i, line ->
            val ly = y + i * lh
            var cx = x
            line.visit({ style: Style, segment: String ->
                val w = font.width(segment)
                val click = style.clickEvent
                if (click is ClickEvent.OpenUrl) {
                    linkRects.add(LinkRect(cx, ly - 1, cx + w, ly + font.lineHeight, click.uri().toString()))
                }
                cx += w
                Optional.empty<Unit>()
            }, Style.EMPTY)
            graphics.text(font, Language.getInstance().getVisualOrder(line), x, ly, color)
        }
        return lines.size * lh
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.x()
        val my = event.y()
        if (event.button() == 0 && selected != null) {
            // Scrollbar: grab the thumb to drag, or click the track to jump.
            val maxScroll = (readmeContentHeight - readmeViewportH()).coerceAtLeast(0)
            if (maxScroll > 0 && mx >= rightRight - SCROLLBAR_W && mx <= rightRight &&
                my >= readmeTop && my <= listBottom
            ) {
                val thumbH = thumbHeight()
                val thumbY = readmeTop + ((readmeViewportH() - thumbH) * (readmeScroll / maxScroll)).toInt()
                if (my >= thumbY && my <= thumbY + thumbH) {
                    dragGrabOffset = my - thumbY
                } else {
                    setScrollFromThumbTop(my - thumbH / 2.0)
                    dragGrabOffset = thumbH / 2.0
                }
                scrollbarDragging = true
                return true
            }
            if (mx >= rightLeft && mx <= rightRight && my >= readmeTop && my <= listBottom) {
                val hit = linkRects.firstOrNull { mx >= it.x1 && mx <= it.x2 && my >= it.y1 && my <= it.y2 }
                if (hit != null) {
                    openUrl(hit.url)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (scrollbarDragging) {
            setScrollFromThumbTop(event.y() - dragGrabOffset)
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (scrollbarDragging && event.button() == 0) scrollbarDragging = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (selected != null && mouseX >= rightLeft && mouseX <= rightRight && mouseY >= readmeTop && mouseY <= listBottom) {
            val max = (readmeContentHeight - (listBottom - readmeTop)).coerceAtLeast(0)
            readmeScroll = (readmeScroll - scrollY * 16).coerceIn(0.0, max.toDouble())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        const val REFRESH_COOLDOWN_MS = 10_000L
        const val SCROLLBAR_W = 4
    }
}
