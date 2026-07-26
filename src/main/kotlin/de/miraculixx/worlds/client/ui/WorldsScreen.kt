package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.client.ui.markdown.Markdown
import de.miraculixx.worlds.client.ui.markdown.MdBlock
import de.miraculixx.worlds.data.InstallResult
import de.miraculixx.worlds.data.InstalledMap
import de.miraculixx.worlds.data.MapEntry
import de.miraculixx.worlds.data.MapInstaller
import de.miraculixx.worlds.data.MapRepository
import de.miraculixx.worlds.data.MapRequirement
import de.miraculixx.worlds.data.MapSource
import de.miraculixx.worlds.data.compareMcVersions
import de.miraculixx.worlds.data.WorldResourcePacks
import kotlinx.coroutines.launch
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.tabs.GridLayoutTab
import net.minecraft.client.gui.components.tabs.MenuTabBar
import net.minecraft.client.gui.components.tabs.TabManager
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.components.tabs.Tab as GuiTab
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import java.util.Optional
import java.util.function.Consumer

/** The in-game map browser: Installed / Browse tabs, list on the left, detail panel on the right. */
class WorldsScreen(private val parent: Screen?) : Screen(Component.literal("Worlds")) {

    /** Ordinal doubles as the index in the [MenuTabBar] — Installed left, Browse right. */
    private enum class Tab(val label: String) { INSTALLED("Installed"), BROWSE("Browse") }

    private var tab = Tab.INSTALLED

    // Native tab header (same widget the world-creation screen uses). The tabs hold no widgets of
    // their own — this screen owns the list/detail panel and only swaps its data source.
    private val tabPages = Tab.entries.map { GridLayoutTab(Component.literal(it.label)) }
    private val tabManager = TabManager(
        { addRenderableWidget(it) },
        { removeWidget(it) },
        tabConsumer { page -> if (page != null) onTabSelected(page) },
        tabConsumer { },
    )
    private lateinit var tabBar: MenuTabBar
    private var allEntries: List<MapEntry> = emptyList()
    private var status: String? = null
    private var actionMessage: String? = null

    private var selected: MapEntry? = null
    // Ids of maps already present in saves/ — used to disable Install in Browse.
    private var installedIds: Set<String> = emptySet()
    // After an install we switch to Installed and auto-select the map with this id.
    private var pendingSelectId: String? = null
    private var readmeBlocks: List<MdBlock> = emptyList()
    private var readmeScroll = 0.0
    private var readmeContentHeight = 0
    private var scrollbarDragging = false
    private var dragGrabOffset = 0.0

    // Clickable link hit-boxes in the readme, rebuilt every frame (26.2 has no style-at-width test).
    private data class LinkRect(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val url: String)
    private val linkRects = ArrayList<LinkRect>()

    /** Filter + sort selection of one tab. Each tab keeps its own — they never sync. */
    private class FilterState {
        var category: String? = null
        var version = VersionMode.ALL
        var sort = SortMode.AZ
        var reverse = false

        val isActive: Boolean
            get() = category != null || version != VersionMode.ALL || sort != SortMode.AZ || reverse
    }

    private val filterStates = Tab.entries.associateWith { FilterState() }
    private val filters: FilterState get() = filterStates.getValue(tab)

    private lateinit var list: MapListWidget
    private lateinit var search: EditBox
    private lateinit var refreshButton: Button
    private lateinit var filterButton: Button

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
        listTop = TAB_BAR_H + 28
        listBottom = height - 32
        rightLeft = leftRight + 12
        rightRight = width - 8
        buttonsY = listTop + 54
        readmeTop = buttonsY + 26

        tabBar = addRenderableWidget(
            MenuTabBar.builder(tabManager, width).addTabs(*tabPages.toTypedArray()).build()
        )
        tabBar.arrangeElements(width)

        val searchY = TAB_BAR_H + 6
        refreshButton = addRenderableWidget(
            Button.builder(Component.literal("Refresh")) { onRefresh() }
                .bounds(rightRight - 70, searchY - 2, 70, 20).build()
        )

        search = EditBox(font, leftLeft, searchY, leftWidth - 22, 16, Component.literal("Search"))
        search.setHint(Component.literal("Search maps…"))
        search.setResponder { applyFilter() }
        addRenderableWidget(search)

        // Icon-only filter toggle right of the search box; sprite swaps when a filter is active.
        filterButton = addRenderableWidget(
            Button.builder(Component.empty()) { openFilters() }
                .bounds(leftLeft + leftWidth - 20, searchY - 2, 20, 20).build()
        )

        list = MapListWidget(minecraft, leftWidth, listBottom - listTop, listTop, ::onSelect, ::onActivate)
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

        // Restores the header highlight after a re-init; no-ops (so no reload) if unchanged.
        tabBar.selectTab(tab.ordinal, false)

        if (allEntries.isEmpty()) loadCurrentTab() else applyFilter()
        refreshInstalledIds()
    }

    /**
     * A [TabManager] callback that tolerates a null page. `setCurrentTab` passes a null *previous*
     * tab on the very first selection; the parameter is declared non-null, so a plain Kotlin SAM
     * lambda would throw on its intrinsic null check and abort `init` before the first load runs.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tabConsumer(action: (GuiTab?) -> Unit): Consumer<GuiTab> =
        Consumer<GuiTab?> { action(it) } as Consumer<GuiTab>

    /** [TabManager] callback — maps the selected page back onto [tab]. */
    private fun onTabSelected(page: GuiTab) {
        if (!::list.isInitialized) return
        switchTab(Tab.entries[tabPages.indexOf(page).coerceAtLeast(0)])
    }

    /** Refresh the set of installed map ids (async) so Browse can disable already-installed maps. */
    private fun refreshInstalledIds() {
        Constants.SCOPE.launch {
            val ids = MapRepository.scanInstalled().mapNotNull { it.meta?.id }.toSet()
            Minecraft.getInstance().execute { installedIds = ids }
        }
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
                    val meta = installed.meta
                    MapEntry(
                        id = meta?.id ?: "local:${installed.saveFolder}",
                        source = meta?.source ?: MapSource.MANUAL,
                        slug = null,
                        title = installed.title,
                        description = meta?.description?.takeIf { it.isNotBlank() }
                            ?: if (meta != null) "Installed • ${installed.saveFolder}"
                            else "Local world • ${installed.saveFolder}",
                        iconUrl = installed.localIcon ?: meta?.icon,
                        // From level.dat, so the version filter works for unmanaged saves too.
                        mcVersions = listOfNotNull(installed.mcVersion),
                        // Worlds this mod didn't install carry no metadata — tag them as such.
                        categories = meta?.categories ?: listOf(InstalledMap.MANUAL_CATEGORY),
                        // Unmanaged worlds have no listing → 0 downloads, sorted last.
                        downloads = meta?.downloads ?: 0,
                        // "Date" on Installed means last played — available for every save.
                        dateEpoch = installed.lastPlayed,
                        website = meta?.website,
                        trailerUrl = meta?.trailer,
                    ).also {
                        it.installedFolder = installed.saveFolder
                        it.requiredMods = meta?.requiredMods ?: emptyList()
                        it.requiredPacks = meta?.requiredPacks ?: emptyList()
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
                pendingSelectId?.let { id ->
                    if (list.selectEntry { it.id == id }) pendingSelectId = null
                }
            }
        }
    }

    private fun applyFilter() {
        val query = if (::search.isInitialized) search.value.trim().lowercase() else ""
        var filtered = if (query.isEmpty()) allEntries else allEntries.filter {
            it.title.lowercase().contains(query) ||
                it.description.lowercase().contains(query) ||
                it.categories.any { c -> c.lowercase().contains(query) }
        }
        val state = filters
        state.category?.let { cat ->
            filtered = filtered.filter { e -> e.categories.any { it.equals(cat, ignoreCase = true) } }
        }
        if (state.version != VersionMode.ALL) {
            filtered = filtered.filter { versionMatches(it) }
        }
        val byKey = when (state.sort) {
            SortMode.AZ -> filtered.sortedBy { it.title.lowercase() }
            SortMode.DOWNLOADS -> filtered.sortedByDescending { it.downloads }
            SortMode.DATE -> filtered.sortedByDescending { it.dateEpoch }
        }
        filtered = if (state.reverse) byKey.reversed() else byKey
        list.setEntries(filtered)
        if (selected != null && selected !in filtered) selected = null
    }

    private fun openFilters() {
        val state = filters
        minecraft.gui.setScreen(
            FilterScreen(
                this, CategoryBadge.FILTER_CATEGORIES, state.category ?: ALL_CATEGORIES,
                state.version, state.sort, state.reverse,
            )
        )
    }

    /** Called back by [FilterScreen] when the popup closes — writes back to the current tab only. */
    fun applyFilters(category: String?, version: VersionMode, sort: SortMode, reverse: Boolean) {
        filters.category = category
        filters.version = version
        filters.sort = sort
        filters.reverse = reverse
        applyFilter()
    }

    /** Whether [entry]'s supported game versions satisfy the current [versionMode]. */
    private fun versionMatches(entry: MapEntry): Boolean {
        val current = Minecraft.getInstance().launchedVersion
        return when (filters.version) {
            VersionMode.ALL -> true
            VersionMode.EQUAL -> entry.mcVersions.any { it == current }
            VersionMode.EQUAL_HIGHER -> entry.mcVersions.any { compareMcVersions(it, current) >= 0 }
        }
    }

    private fun onSelect(entry: MapEntry) {
        selected = entry
        readmeScroll = 0.0
        actionMessage = null
        readmeBlocks = Markdown.parse(readmeFor(entry))
        if (!entry.detailLoaded) {
            Constants.SCOPE.launch {
                MapRepository.loadDetail(entry)
                Minecraft.getInstance().execute {
                    if (selected === entry) readmeBlocks = Markdown.parse(readmeFor(entry))
                }
            }
        }
    }

    /**
     * Double click to join
     */
    private fun onActivate(entry: MapEntry) {
        if (entry.installedFolder != null) onPrimary()
    }

    /** Readme markdown, with the bundled resource packs appended as a list for installed maps. */
    private fun readmeFor(entry: MapEntry): String {
        val base = entry.readmeMarkdown ?: entry.description
        val folder = entry.installedFolder ?: return base
        val packs = WorldResourcePacks.listPackNames(folder)
        if (packs.isEmpty()) return base
        return base + "\n\n## Resource packs\n" + packs.joinToString("\n") { "- $it" }
    }

    private fun onPrimary() {
        val entry = selected ?: return
        val folder = entry.installedFolder
        if (folder != null) {
            val missing = missingMods(entry)
            if (missing.isEmpty()) {
                playWorld(folder)
            } else {
                minecraft.gui.setScreen(MissingModsScreen(this, entry.title, missing) { playWorld(folder) })
            }
            return
        }
        actionMessage = "Installing…"
        Constants.SCOPE.launch {
            val result = MapInstaller.install(entry)
            Minecraft.getInstance().execute {
                when (result) {
                    is InstallResult.Success -> {
                        actionMessage = "Installed to saves/${result.saveFolder}"
                        refreshInstalledIds()
                        // Jump to the Installed tab and select the freshly-installed map.
                        pendingSelectId = entry.id
                        tabManager.setCurrentTab(tabPages[Tab.INSTALLED.ordinal], false)
                    }
                    is InstallResult.Failure -> actionMessage = result.message
                }
            }
        }
    }

    /** Open an installed world, returning to this screen when the flow hands control back. */
    private fun playWorld(folder: String) {
        minecraft.createWorldOpenFlows().openWorld(folder) {
            minecraft.gui.setScreen(this)
        }
    }

    /** Required mods of [entry] that are not currently loaded. modId-less reqs can't be checked → skipped. */
    private fun missingMods(entry: MapEntry): List<MapRequirement> =
        entry.requiredMods.filter { req ->
            val id = req.modId?.takeIf { it.isNotBlank() } ?: return@filter false
            !FabricLoader.getInstance().isModLoaded(id)
        }

    /** Preferred external link: the source page (Modrinth/GitHub) if set, else the website. */
    private fun MapEntry.linkUrl(): String? = sourceUrl?.takeIf { it.isNotBlank() } ?: website

    private fun openUrl(url: String?) {
        if (!url.isNullOrBlank()) Util.getPlatform().openUri(url)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        updateWidgets()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.fill(rightLeft - 6, listTop, rightLeft - 5, listBottom, 0x40FFFFFF)

        val sprite = if (filters.isActive) FILTER_ACTIVE else FILTER_INACTIVE
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, filterButton.x + 2, filterButton.y + 2, 16, 16)

        // Push the readme down when a status line sits under the buttons, so they don't clip.
        readmeTop = buttonsY + 26 + if (actionMessage != null) 10 else 0

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
            val isInstalledEntry = entry.installedFolder != null
            val alreadyInstalled = isInstalledEntry || entry.id in installedIds
            primaryButton.message = Component.literal(
                when {
                    isInstalledEntry -> "Play"
                    alreadyInstalled -> "Installed"
                    else -> "Install"
                }
            )
            // Play stays clickable; a Browse map already in saves/ is disabled.
            primaryButton.active = isInstalledEntry || !alreadyInstalled
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
        // Only a real category earns a pill — the source (modrinth/manual) is an implementation
        // detail and used to leak "manual" onto every curated map.
        entry.categories.firstOrNull()?.let { category ->
            CategoryBadge.draw(graphics, font, category, rightLeft, listTop + iconSize + 2)
        }
    }

    private fun drawReadme(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val bottom = listBottom
        linkRects.clear()
        // Soft black backing so the readme stays legible over bright title-screen backgrounds.
        // Text is inset from the left; top/bottom get bevel lines like the world list (below).
        graphics.fill(rightLeft, readmeTop, rightRight, bottom, 0x6D000000.toInt())
        graphics.enableScissor(rightLeft, readmeTop, rightRight, bottom)
        val textX = rightLeft + READ_PAD_X
        var y = readmeTop - readmeScroll.toInt()
        val innerWidth = rightRight - textX - (SCROLLBAR_W + 2)
        for (block in readmeBlocks) {
            y += renderBlock(graphics, block, textX, y, innerWidth)
        }
        readmeContentHeight = (y + readmeScroll.toInt()) - readmeTop
        graphics.disableScissor()

        // Top/bottom limiter lines (outside the content box): outer bright, inner dark. Symmetric
        // so neither clips the first/last text row.
        graphics.fill(rightLeft, readmeTop - 2, rightRight, readmeTop - 1, 0x33FFFFFF)
        graphics.fill(rightLeft, readmeTop - 1, rightRight, readmeTop, 0xFF000000.toInt())
        graphics.fill(rightLeft, bottom + 1, rightRight, bottom + 2, 0x33FFFFFF)
        graphics.fill(rightLeft, bottom, rightRight, bottom + 1, 0xFF000000.toInt())

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

    /** Ctrl+Tab / Ctrl+<digit> cycle the header tabs, as on the world-creation screen. */
    override fun keyPressed(event: KeyEvent): Boolean =
        tabBar.keyPressed(event) || super.keyPressed(event)

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        /** Height of [MenuTabBar] (its own private constant), needed to place the row below it. */
        const val TAB_BAR_H = 24
        const val REFRESH_COOLDOWN_MS = 10_000L
        const val SCROLLBAR_W = 4
        const val READ_PAD_X = 4
        val FILTER_INACTIVE: Identifier = Identifier.fromNamespaceAndPath("worlds", "filter/inactive")
        val FILTER_ACTIVE: Identifier = Identifier.fromNamespaceAndPath("worlds", "filter/active")
    }
}
