package de.miraculixx.chunkeditor.client.ui

import com.mojang.blaze3d.platform.NativeImage
import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.BiomeTints
import de.miraculixx.chunkeditor.data.ChunkFacts
import de.miraculixx.chunkeditor.data.ChunkMapRenderer
import de.miraculixx.chunkeditor.data.ChunkClipExport
import de.miraculixx.chunkeditor.data.ChunkClipImport
import de.miraculixx.chunkeditor.data.ChunkRegions
import de.miraculixx.chunkeditor.data.ClipImportOptions
import de.miraculixx.chunkeditor.data.ExistingChunks
import de.miraculixx.chunkeditor.data.ClipInfo
import de.miraculixx.chunkeditor.data.ExportResult
import de.miraculixx.chunkeditor.data.SelectionCsv
import de.miraculixx.chunkeditor.data.SelectionFile
import de.miraculixx.chunkeditor.data.ImportResult
import de.miraculixx.chunkeditor.data.REGION_SIZE
import de.miraculixx.chunkeditor.data.RegionIndex
import de.miraculixx.chunkeditor.data.LevelFacts
import de.miraculixx.chunkeditor.data.WorldDimension
import de.miraculixx.common.client.ui.BackupActionScreen
import de.miraculixx.common.client.ui.Dropdown
import de.miraculixx.common.client.ui.MenuDropdown
import de.miraculixx.common.client.ui.MenuEntry
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.clickSound
import de.miraculixx.common.client.ui.drawBox
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.storage.LevelStorageSource
import org.lwjgl.glfw.GLFW
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val HEADER_H = 32
private const val MARGIN = 8
private const val DROPDOWN_W = 140
private const val MENU_W = 78

/** The two-row info bar under the map: coordinates + zoom, then the world summary */
private const val INFO_H = 30
private const val INFO_ROW_H = 13

/** Widest value each coordinate field can ever hold, the last step of [COORD_TIERS] */
private const val BLOCK_EXTREME = "-30000000"
private const val CHUNK_EXTREME = "-1875000"
private const val REGION_EXTREME = "-58594"

private val COORD_TIERS = listOf(-99 to 999, -9999 to 999999)

/** The same idea for the selected-chunk count, which is never negative */
private val COUNT_TIERS = listOf(999, 999999)

/** The zoom slider filling whatever the coordinate row leaves of the info bar */
private const val SLIDER_MIN_W = 48
private const val SLIDER_MAX_W = 160
private const val SLIDER_TRACK_H = 4
private const val SLIDER_KNOB_W = 5
private const val SLIDER_KNOB_H = 10
private const val TRACK_COLOR = 0x60FFFFFF
private const val KNOB_COLOR = -1
private const val KNOB_HOVER_COLOR = 0xFF33B5E5.toInt()

private const val YBAR_W = 15
private const val YBAR_PAD = 5
private const val YBAR_TRACK_W = 4
private const val YBAR_KNOB_W = 10
private const val YBAR_KNOB_H = 5
private const val YBAR_STEP = 8
private const val TRACK_DIM_COLOR = 0x30FFFFFF

/** Build height fallback for a dimension whose chunks could not be read */
private const val DEFAULT_Y_MIN = -64
private const val DEFAULT_Y_MAX = 319

private const val REGION_BLOCKS = REGION_SIZE * 16

/** Pixels per block. The clamps put a chunk between a quarter pixel and 64 pixels wide. */
private const val MIN_SCALE = 1.0 / 64.0
private const val MAX_SCALE = 4.0
private const val TERRAIN_SCALE = 0.25
private const val MAX_LIVE_REGIONS = 32

/** Blocks per pixel of the overview render */
private const val COARSE_STEP = 8
private const val COARSE_PIXELS = REGION_BLOCKS / COARSE_STEP
private const val MAX_COARSE_JOBS = 3

private const val TERRAIN_CACHE = 32
private const val COARSE_CACHE = 512
private const val OVERLAY_CACHE = 512

private const val PRESENT_COLOR = 0x70A8B4C8
private const val UNREADABLE_COLOR = 0xB0C05050.toInt()
private const val SELECTED_COLOR = 0x9033B5E5.toInt()
private const val GRID_COLOR = 0x30FFFFFF
private const val REGION_LINE_COLOR = 0x80FFFFFF.toInt()

private class Shortcut(private val ctrl: Boolean, private val glfwKey: Int, val label: String) {
    fun matches(event: KeyEvent) = event.key() == glfwKey && event.hasControlDownWithQuirk() == ctrl
}

private val SC_SELECT_ALL = Shortcut(true, GLFW.GLFW_KEY_A, "Ctrl+A")
private val SC_INVERT = Shortcut(true, GLFW.GLFW_KEY_I, "Ctrl+I")
private val SC_CLEAR = Shortcut(false, GLFW.GLFW_KEY_BACKSPACE, "Backspace")
private val SC_TRIM = Shortcut(false, GLFW.GLFW_KEY_T, "T")
private val SC_EXPORT = Shortcut(false, GLFW.GLFW_KEY_E, "E")
private val SC_IMPORT = Shortcut(false, GLFW.GLFW_KEY_I, "I")
private val SC_DELETE = Shortcut(false, GLFW.GLFW_KEY_DELETE, "Del")
private val SC_REFRESH = Shortcut(true, GLFW.GLFW_KEY_R, "Ctrl+R")

private val DELETE_ACTION: Component get() = Component.translatable("selectWorld.delete")
private val PASTE_ACTION: Component get() = Component.translatable("chunkeditor.clip.import")

/** `Backup & <action>` — the button that takes a backup first. */
private fun backupLabel(action: Component): Component =
    Component.translatable("chunkeditor.confirm.backup", action)

/** `<action> - NO UNDO`, the warning half in red. */
private fun noUndoLabel(action: Component): Component = Component.translatable(
    "chunkeditor.confirm.no_undo", action,
    Component.translatable("chunkeditor.confirm.no_undo_tag").withColor(TextColor.RED),
)

/** Past this many chunks a paste ghost draws only its hull — a quad each would swamp the frame. */
private const val PASTE_FILL_MAX = 4096
private const val PASTE_COLOR = 0x6033B5E5

private const val CLICK_SLOP = 3.0
private const val TICKS_PER_MINUTE = 1200L

/**
 * MCA-Selector like interface.
 * 3 layers, detailed, 8 sample & presence fallback (gray square)
 */
internal class ChunkMapScreen(
    private val parent: Screen,
    private val access: LevelStorageSource.LevelStorageAccess,
) : Screen(Component.translatable("chunkeditor.map.title")) {

    private val facts = LevelFacts.read(access)
    private val worldTime = facts.gameTime
    private val spawn: BlockPos = facts.spawn

    private val dimensions = ChunkRegions.dimensions(access)
    private var dimension = dimensions.firstOrNull()

    /** Region key (packed rx/rz) its chunk bitmap. Filled once per dimension. */
    private val indices = LinkedHashMap<Long, RegionIndex>()
    private var totalChunks = 0
    private var totalBytes = 0L
    private var loading = false
    private var loadGen = 0

    private val selected = LongOpenHashSet()
    private val unreadable = LongOpenHashSet()

    private val terrain = TextureCache(TERRAIN_CACHE)
    private val coarse = TextureCache(COARSE_CACHE)
    private val presence = TextureCache(OVERLAY_CACHE)
    private val selection = TextureCache(OVERLAY_CACHE)
    private val rendering = HashSet<Long>()
    private val coarseRendering = HashSet<Long>()
    private var coarseJobs = 0

    private var centerX = 0.0
    private var centerZ = 0.0
    private var scale = 1.0 / 4.0

    private var pressX = 0.0
    private var pressY = 0.0
    private var panning = false
    private var moved = false
    private var dragFrom: ChunkPos? = null
    private var dragTo: ChunkPos? = null
    private var dragRemoves = false

    // Info-bar slider geometry
    private var sliderX1 = 0
    private var sliderX2 = 0
    private var sliderY = 0
    private var sliderDragging = false

    // Max-height slider geometry + state
    private var yMin = DEFAULT_Y_MIN
    private var yMax = DEFAULT_Y_MAX
    private var yCut = DEFAULT_Y_MAX
    private var yKnown = false
    private var yDragging = false
    private var yBarTop = 0
    private var yBarBottom = 0

    private var scanJob: Job? = null
    private var scanProgress: Pair<Int, Int>? = null

    private var tints: BiomeTints? = null
    private var tintsLoading = false
    private var tintsDone = false

    private lateinit var dimensionPicker: Dropdown<WorldDimension>
    private var menus: List<MenuDropdown> = emptyList()

    /** Non-null while a clip is following the cursor, waiting to be placed. */
    private var pasteClip: ClipInfo? = null
    private var pasteOffsets: List<ChunkPos> = emptyList()

    /** minX, minZ, maxX, maxZ of [pasteOffsets] */
    private var pasteBounds = intArrayOf(0, 0, 0, 0)

    @Volatile
    private var clipMessage: String? = null
    private var clipBusy = false

    override fun init() {
        val dim = dimension
        if (dim != null && indices.isEmpty() && !loading) {
            centerX = spawn.x.toDouble()
            centerZ = spawn.z.toDouble()
            loadDimension(dim)
        }
        loadTints()

        dimensionPicker = Dropdown(MARGIN, 6, DROPDOWN_W, dimensions, dim, { it.label }, ::switchDimension)
        addRenderableWidget(dimensionPicker.button)

        var x = MARGIN + DROPDOWN_W + 6
        menus = listOf(
            menu("chunkeditor.menu.selection", x, selectionMenu()),
            menu("chunkeditor.menu.edit", x + MENU_W + 4, editMenu()),
            menu("chunkeditor.menu.view", x + 2 * (MENU_W + 4), viewMenu()),
        )
        // One menu at a time, or two overlays would fight over the same pixels.
        menus.forEach { dropdown ->
            dropdown.onOpen = { opened -> menus.forEach { if (it !== opened) it.close() } }
            addRenderableWidget(dropdown.button)
        }

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width - MARGIN - 76, 6, 76, 20).build()
        )
    }

    private fun menu(key: String, x: Int, entries: List<MenuEntry>) =
        MenuDropdown(Component.translatable(key), x, 6, MENU_W, entries)

    /** Things to select chunks */
    private fun selectionMenu(): List<MenuEntry> = listOf(
        MenuEntry.Item(Component.translatable("chunkeditor.map.select_all"), SC_SELECT_ALL.label) { selectAll() },
        MenuEntry.Item(Component.translatable("chunkeditor.map.invert"), SC_INVERT.label) { invertSelection() },
        MenuEntry.Item(Component.translatable("chunkeditor.map.clear"), SC_CLEAR.label) { clearSelection() },
        MenuEntry.Separator,
        MenuEntry.Item(Component.translatable("chunkeditor.map.trim"), SC_TRIM.label) { openTrim() },
    )

    /** Things to edit selected chunks */
    private fun editMenu(): List<MenuEntry> = listOf(
        MenuEntry.Item(
            Component.translatable("chunkeditor.clip.export"), SC_EXPORT.label,
            enabled = { selected.isNotEmpty() && !clipBusy },
        ) { openExport() },
        MenuEntry.Item(
            Component.translatable("chunkeditor.clip.import"), SC_IMPORT.label,
            enabled = { !clipBusy },
        ) { openLibrary() },
        MenuEntry.Separator,
        MenuEntry.Item(
            Component.translatable("selectWorld.delete"), SC_DELETE.label,
            enabled = { selected.isNotEmpty() },
        ) { confirmDelete() },
    )

    /** Things to edit how the map looks */
    private fun viewMenu(): List<MenuEntry> = listOf(
        MenuEntry.Item(Component.translatable("chunkeditor.map.reset_view")) { resetView() },
        MenuEntry.Item(Component.translatable("selectServer.refresh"), SC_REFRESH.label) {
            dimension?.let { loadDimension(it) }
        },
    )

    //
    // Clips
    //

    private fun openExport() {
        val dim = dimension ?: return
        if (selected.isEmpty() || clipBusy) return
        minecraft.gui.setScreen(ClipNameScreen(this, "${dim.label.lowercase()}-${selected.size}") { name, kinds ->
            minecraft.gui.setScreen(this)
            runExports(dim, name, kinds)
        })
    }

    /** Both kinds can be on, so this is one pass that reports whatever it wrote */
    private fun runExports(dim: WorldDimension, name: String, kinds: Set<ExportKind>) {
        if (kinds.isEmpty()) return
        val chunks = selected.toLongArray().map { ChunkPos.unpack(it) }
        clipBusy = true
        clipMessage = I18n.get("chunkeditor.clip.exporting", 0, chunks.size)
        Constants.SCOPE.launch {
            val messages = ArrayList<String>(2)
            if (ExportKind.CLIP in kinds) {
                val result = ChunkClipExport.export(dim, chunks, name) { done, total ->
                    clipMessage = I18n.get("chunkeditor.clip.exporting", done, total)
                }
                messages += when (result) {
                    is ExportResult.Success -> I18n.get("chunkeditor.clip.exported", result.chunks, result.dir.name)
                    is ExportResult.Failure -> I18n.get(result.message)
                }
            }
            if (ExportKind.SELECTION in kinds) {
                val file = runCatching { SelectionCsv.write(name, chunks) }
                    .onFailure { Constants.LOG.error("Selection export failed", it) }
                    .getOrNull()
                messages += if (file == null) I18n.get("chunkeditor.clip.error.write")
                else I18n.get("chunkeditor.clip.selection_exported", chunks.size, file.nameWithoutExtension)
            }
            minecraft.execute {
                clipBusy = false
                clipMessage = messages.joinToString(" · ")
            }
        }
    }

    private fun openLibrary() {
        if (clipBusy) return
        minecraft.gui.setScreen(
            ClipLibraryScreen(
                this,
                { clip ->
                    startPaste(clip)
                    minecraft.gui.setScreen(this)
                },
                { selection ->
                    minecraft.gui.setScreen(this)
                    applySelection(selection)
                },
            )
        )
    }

    /**
     * A CSV contains chunk names (unvailables are dropped)
     */
    private fun applySelection(file: SelectionFile) {
        clipBusy = true
        Constants.SCOPE.launch {
            val parsed = SelectionCsv.read(file.path)
            minecraft.execute {
                clipBusy = false
                if (parsed == null) {
                    clipMessage = I18n.get("chunkeditor.clip.error.read")
                    return@execute
                }
                selected.clear()
                indices.values.forEach { region ->
                    ChunkRegions.forEachChunk(region) { pos ->
                        val listed = parsed.chunks.contains(pos.pack())
                        if (listed != parsed.inverted) selected.add(pos.pack())
                    }
                }
                clipMessage = I18n.get("chunkeditor.clip.selection_applied", selected.size, file.name)
                onSelectionChanged()
            }
        }
    }

    /** Arms paste mode: the clip's footprint now follows the cursor until a click or Escape. */
    private fun startPaste(clip: ClipInfo) {
        pasteClip = clip
        val origin = clip.origin
        pasteOffsets = clip.chunks.map { ChunkPos(it.x - origin.x, it.z - origin.z) }
        pasteBounds = intArrayOf(
            pasteOffsets.minOfOrNull { it.x } ?: 0, pasteOffsets.minOfOrNull { it.z } ?: 0,
            pasteOffsets.maxOfOrNull { it.x } ?: 0, pasteOffsets.maxOfOrNull { it.z } ?: 0,
        )
        clipMessage = I18n.get("chunkeditor.clip.paste_hint", clip.name)
    }

    private fun cancelPaste() {
        pasteClip = null
        pasteOffsets = emptyList()
        clipMessage = null
    }

    /** Where the clip's own origin would land, given the cursor. */
    private fun pasteOrigin(mouseX: Double, mouseY: Double): ChunkPos = chunkAt(mouseX, mouseY)

    /** Placement done; the options screen decides *how* the clip lands before anything is written. */
    private fun confirmPaste(origin: ChunkPos) {
        val clip = pasteClip ?: return
        cancelPaste()
        minecraft.gui.setScreen(ClipImportScreen(this, clip, origin) { options ->
            minecraft.gui.setScreen(this)
            checkConflicts(clip, origin, options)
        })
    }

    private fun checkConflicts(clip: ClipInfo, origin: ChunkPos, options: ClipImportOptions) {
        val dim = dimension ?: return
        clipBusy = true
        clipMessage = I18n.get("chunkeditor.clip.checking")
        Constants.SCOPE.launch {
            val conflicts = ChunkClipImport.conflicts(clip, dim, origin)
            minecraft.execute {
                clipBusy = false
                clipMessage = null
                // Only prompt when data is lost/overriden
                if (conflicts == 0 || options.existing == ExistingChunks.SKIP) {
                    runPaste(clip, dim, origin, options)
                    return@execute
                }
                val warning = if (options.existing == ExistingChunks.MERGE) {
                    "chunkeditor.clip.merge_warning"
                } else "chunkeditor.clip.paste_warning"
                minecraft.gui.setScreen(
                    BackupActionScreen(
                        { minecraft.gui.setScreen(this@ChunkMapScreen) },
                        { backup, _ ->
                            EditWorldScreen.conditionallyMakeBackupAndShowToast(backup, access)
                                .thenAcceptAsync({ runPaste(clip, dim, origin, options) }, minecraft)
                        },
                        Component.translatable("chunkeditor.clip.paste_title", clip.name),
                        Component.translatable(warning, conflicts),
                        backupLabel(PASTE_ACTION),
                        noUndoLabel(PASTE_ACTION),
                    )
                )
            }
        }
    }

    private fun runPaste(clip: ClipInfo, dim: WorldDimension, origin: ChunkPos, options: ClipImportOptions) {
        val generation = loadGen
        minecraft.gui.setScreen(this)
        clipBusy = true
        clipMessage = I18n.get("chunkeditor.clip.importing", 0, clip.chunks.size)
        Constants.SCOPE.launch {
            val result = ChunkClipImport.import(clip, dim, origin, options) { done, total ->
                clipMessage = I18n.get("chunkeditor.clip.importing", done, total)
            }
            val touched = clip.chunks
                .map { ChunkPos(it.x + origin.x - clip.origin.x, it.z + origin.z - clip.origin.z) }
                .map { it.regionX to it.regionZ }.distinct()
            val refreshed = touched.map { (rx, rz) -> key(rx, rz) to ChunkRegions.readIndex(dim, rx, rz) }
            minecraft.execute {
                clipBusy = false
                clipMessage = when (result) {
                    is ImportResult.Success ->
                        I18n.get("chunkeditor.clip.imported", result.written, result.skipped + result.clipped)

                    is ImportResult.Failure -> I18n.get(result.message)
                }
                if (generation != loadGen) return@execute
                refreshed.forEach { (regionKey, index) ->
                    if (index == null) indices.remove(regionKey) else indices[regionKey] = index
                }
                totalChunks = indices.values.sumOf { it.count }
                totalBytes = indices.values.sumOf { it.bytes }
                selected.clear()
                if (result is ImportResult.Success) {
                    clip.chunks.forEach {
                        selected.add(ChunkPos(it.x + origin.x - clip.origin.x, it.z + origin.z - clip.origin.z).pack())
                    }
                }
                dropTextures()
                // A render started before the write would land with the old terrain on it.
                loadGen++
                onSelectionChanged()
            }
        }
    }

    //
    // Data
    //

    private fun switchDimension(value: WorldDimension) {
        dimension = value
        selected.clear()
        centerX = 0.0
        centerZ = 0.0
        loadDimension(value)
    }

    /** Region discovery + every region's header, off-thread; a stale result is dropped by [loadGen]. */
    private fun loadDimension(dim: WorldDimension) {
        val generation = ++loadGen
        loading = true
        yKnown = false
        yMin = DEFAULT_Y_MIN
        yMax = DEFAULT_Y_MAX
        yCut = DEFAULT_Y_MAX
        indices.clear()
        unreadable.clear()
        rendering.clear()
        coarseRendering.clear()
        dropTextures()
        Constants.SCOPE.launch {
            val read = ChunkRegions.listRegions(dim).mapNotNull { (rx, rz) -> ChunkRegions.readIndex(dim, rx, rz) }
            // One chunk carries the dimension's build height
            val bounds = read.firstOrNull { it.count > 0 }?.let { ChunkRegions.heightBounds(dim, firstChunk(it)) }
            minecraft.execute {
                if (generation != loadGen) return@execute
                read.forEach { indices[key(it.rx, it.rz)] = it }
                totalChunks = read.sumOf { it.count }
                totalBytes = read.sumOf { it.bytes }
                if (bounds != null) {
                    yMin = bounds.first
                    yMax = bounds.last
                    yKnown = true
                }
                yCut = yMax
                loading = false
            }
        }
    }

    private fun firstChunk(region: RegionIndex): ChunkPos {
        val i = region.present.nextSetBit(0)
        return ChunkPos(region.rx * REGION_SIZE + i % REGION_SIZE, region.rz * REGION_SIZE + i / REGION_SIZE)
    }

    private fun loadTints() {
        if (tintsLoading || tintsDone) return
        tintsLoading = true
        Constants.SCOPE.launch {
            val loaded = BiomeTints.load(access)
            minecraft.execute {
                tints = loaded
                tintsLoading = false
                tintsDone = true
            }
        }
    }

    private fun selectAll() {
        indices.values.forEach { ChunkRegions.forEachChunk(it) { pos -> selected.add(pos.pack()) } }
        onSelectionChanged()
    }

    private fun invertSelection() {
        val inverted = LongOpenHashSet()
        indices.values.forEach {
            ChunkRegions.forEachChunk(it) { pos ->
                if (!selected.contains(pos.pack())) inverted.add(pos.pack())
            }
        }
        selected.clear()
        selected.addAll(inverted)
        onSelectionChanged()
    }

    private fun clearSelection() {
        selected.clear()
        onSelectionChanged()
    }

    private fun openTrim() {
        val dim = dimension ?: return
        minecraft.gui.setScreen(ChunkTrimScreen(this) { criteria -> applyTrim(dim, criteria) })
    }

    /**
     * Turns the criteria into a selection. Scans all chunk data if needed.
     */
    private fun applyTrim(dim: WorldDimension, criteria: TrimCriteria) {
        minecraft.gui.setScreen(this)
        if (criteria.isEmpty) return
        if (!criteria.needsScan) {
            select(criteria, emptyMap())
            return
        }
        val generation = loadGen
        val regions = indices.values.toList()
        scanProgress = 0 to regions.size
        scanJob?.cancel()
        scanJob = Constants.SCOPE.launch {
            val facts = ChunkRegions.scanFields(dim, regions) { done, total ->
                minecraft.execute { if (generation == loadGen) scanProgress = done to total }
            }
            minecraft.execute {
                scanProgress = null
                if (generation == loadGen) select(criteria, facts)
            }
        }
    }

    private fun select(criteria: TrimCriteria, facts: Map<Long, ChunkFacts>) {
        val spawnChunk = ChunkPos(spawn.x shr 4, spawn.z shr 4)
        indices.values.forEach { region ->
            ChunkRegions.forEachChunk(region) { pos ->
                val packed = pos.pack()
                val fact = facts[packed]
                val hit = (criteria.minSpawnDistance == null ||
                    pos.getChessboardDistance(spawnChunk) > criteria.minSpawnDistance) &&
                    (criteria.maxInhabitedTicks == null ||
                        (fact?.inhabitedTime ?: 0L) < criteria.maxInhabitedTicks) &&
                    (criteria.olderThanTicks == null ||
                        worldTime - (fact?.lastUpdate ?: 0L) > criteria.olderThanTicks)
                if (hit) selected.add(packed)
            }
        }
        onSelectionChanged()
    }

    private fun onSelectionChanged() {
        selection.releaseAll()
    }

    /**
     * Vanilla's backup prompt doubles as the confirmation: its three buttons are backup-and-proceed,
     * proceed without one, and cancel.
     */
    private fun confirmDelete() {
        val dim = dimension ?: return
        val count = selected.size
        minecraft.gui.setScreen(
            BackupActionScreen(
                { minecraft.gui.setScreen(this) },
                { backup, _ ->
                    EditWorldScreen.conditionallyMakeBackupAndShowToast(backup, access)
                        .thenAcceptAsync({ runDelete(dim) }, minecraft)
                },
                Component.translatable("chunkeditor.map.delete_title", count),
                Component.translatable("chunkeditor.map.delete_warning", dim.label),
                backupLabel(DELETE_ACTION),
                noUndoLabel(DELETE_ACTION),
            )
        )
    }

    private fun runDelete(dim: WorldDimension) {
        val chunks = selected.toLongArray().map { ChunkPos.unpack(it) }
        val generation = loadGen
        minecraft.gui.setScreen(this)
        Constants.SCOPE.launch {
            ChunkRegions.deleteChunks(dim, chunks)
            val touched = chunks.map { it.regionX to it.regionZ }.distinct()
            val refreshed = touched.map { (rx, rz) -> key(rx, rz) to ChunkRegions.readIndex(dim, rx, rz) }
            minecraft.execute {
                if (generation != loadGen) return@execute
                refreshed.forEach { (regionKey, index) ->
                    if (index == null) indices.remove(regionKey) else indices[regionKey] = index
                }
                totalChunks = indices.values.sumOf { it.count }
                totalBytes = indices.values.sumOf { it.bytes }
                selected.clear()
                unreadable.clear()
                dropTextures()
                // A render started before the delete would land with the deleted chunks still on it.
                loadGen++
            }
        }
    }

    //
    // Viewport
    //

    private fun mapLeft() = MARGIN
    private fun mapTop() = HEADER_H
    private fun mapRight() = width - MARGIN - YBAR_W
    private fun mapBottom() = height - MARGIN - INFO_H

    private fun screenX(blockX: Double) = (mapLeft() + mapRight()) / 2.0 + (blockX - centerX) * scale
    private fun screenY(blockZ: Double) = (mapTop() + mapBottom()) / 2.0 + (blockZ - centerZ) * scale
    private fun blockX(screenX: Double) = centerX + (screenX - (mapLeft() + mapRight()) / 2.0) / scale
    private fun blockZ(screenY: Double) = centerZ + (screenY - (mapTop() + mapBottom()) / 2.0) / scale

    private fun inMap(x: Double, y: Double) =
        x >= mapLeft() && x < mapRight() && y >= mapTop() && y < mapBottom()

    private fun chunkAt(x: Double, y: Double) =
        ChunkPos(floor(blockX(x) / 16).toInt(), floor(blockZ(y) / 16).toInt())

    private fun resetView() {
        centerX = spawn.x.toDouble()
        centerZ = spawn.z.toDouble()
        scale = 1.0 / 4.0
        setCut(yMax)
    }

    //
    // Render
    //

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val left = mapLeft()
        val top = mapTop()
        val right = mapRight()
        val bottom = mapBottom()
        drawBox(graphics, left, top, right, bottom)

        graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1)
        val visible = visibleRegions()
        val withTerrain = scale >= TERRAIN_SCALE && visible.size <= MAX_LIVE_REGIONS
        // Past the overlay cache there would be more visible regions than textures to hold them, and
        // every frame would rebuild the evicted ones
        val perChunk = visible.size <= OVERLAY_CACHE
        visible.forEach { region -> drawRegion(graphics, region, withTerrain, perChunk) }
        pumpCoarse(visible)
        drawGrid(graphics, visible)
        drawDragRect(graphics)
        drawPasteGhost(graphics, mouseX, mouseY)
        graphics.disableScissor()

        drawHeightBar(graphics, mouseX, mouseY)
        drawInfo(graphics, mouseX, mouseY)

        dimensionPicker.renderOverlay(graphics, font, mouseX, mouseY)
        menus.forEach { it.renderOverlay(graphics, font, mouseX, mouseY) }
    }

    private fun visibleRegions(): List<RegionIndex> {
        if (indices.isEmpty()) return emptyList()
        val minRx = floor(blockX(mapLeft().toDouble()) / REGION_BLOCKS).toInt()
        val maxRx = floor(blockX(mapRight().toDouble()) / REGION_BLOCKS).toInt()
        val minRz = floor(blockZ(mapTop().toDouble()) / REGION_BLOCKS).toInt()
        val maxRz = floor(blockZ(mapBottom().toDouble()) / REGION_BLOCKS).toInt()
        val found = ArrayList<RegionIndex>()
        for (rz in minRz..maxRz) for (rx in minRx..maxRx) indices[key(rx, rz)]?.let { found.add(it) }
        return found
    }

    private fun drawRegion(
        graphics: GuiGraphicsExtractor, region: RegionIndex, withTerrain: Boolean, perChunk: Boolean,
    ) {
        val regionKey = key(region.rx, region.rz)
        val x = screenX(region.rx.toDouble() * REGION_BLOCKS).roundToInt()
        val y = screenY(region.rz.toDouble() * REGION_BLOCKS).roundToInt()
        val w = screenX((region.rx + 1).toDouble() * REGION_BLOCKS).roundToInt() - x
        val h = screenY((region.rz + 1).toDouble() * REGION_BLOCKS).roundToInt() - y
        if (w <= 0 || h <= 0) return

        var painted = false
        if (withTerrain) {
            val id = terrain[regionKey]
            if (id != null) {
                blit(graphics, id, x, y, w, h, REGION_BLOCKS)
                painted = true
            } else {
                requestTerrain(region)
            }
        }
        // The overview render stands in wherever the detail one is off or not there yet, so zooming
        // out keeps showing terrain instead of dropping to a flat wash.
        if (!painted) coarse[regionKey]?.let {
            blit(graphics, it, x, y, w, h, COARSE_PIXELS)
            painted = true
        }
        if (!painted) {
            if (perChunk) blit(graphics, presenceTexture(region), x, y, w, h, REGION_SIZE)
            else graphics.fill(x, y, x + w, y + h, density(region))
        }
        if (perChunk) selectionTexture(region)?.let { blit(graphics, it, x, y, w, h, REGION_SIZE) }
    }

    /**
     * Queues overview renders for the visible regions that have none, a few at a time
     */
    private fun pumpCoarse(visible: List<RegionIndex>) {
        if (!tintsDone || visible.size > OVERLAY_CACHE) return
        for (region in visible) {
            if (coarseJobs >= MAX_COARSE_JOBS) return
            val regionKey = key(region.rx, region.rz)
            if (coarse.containsKey(regionKey) || regionKey in coarseRendering) continue
            requestCoarse(region)
        }
    }

    /** How full a region is, as an alpha over the presence colour */
    private fun density(region: RegionIndex): Int {
        val alpha = (0x20 + region.count * 0x60 / (REGION_SIZE * REGION_SIZE)).coerceAtMost(0xFF)
        return (alpha shl 24) or (PRESENT_COLOR and 0xFFFFFF)
    }

    private fun blit(graphics: GuiGraphicsExtractor, id: Identifier, x: Int, y: Int, w: Int, h: Int, size: Int) =
        graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, size, size, size, size)

    private fun drawGrid(graphics: GuiGraphicsExtractor, visible: List<RegionIndex>) {
        val left = mapLeft() + 1
        val top = mapTop() + 1
        val right = mapRight() - 1
        val bottom = mapBottom() - 1
        val pxPerChunk = scale * 16
        if (pxPerChunk >= 12) {
            var chunkX = floor(blockX(left.toDouble()) / 16).toInt()
            while (true) {
                val sx = screenX(chunkX * 16.0).roundToInt()
                if (sx > right) break
                if (sx >= left) graphics.fill(sx, top, sx + 1, bottom, GRID_COLOR)
                chunkX++
            }
            var chunkZ = floor(blockZ(top.toDouble()) / 16).toInt()
            while (true) {
                val sy = screenY(chunkZ * 16.0).roundToInt()
                if (sy > bottom) break
                if (sy >= top) graphics.fill(left, sy, right, sy + 1, GRID_COLOR)
                chunkZ++
            }
        }
        //if (pxPerChunk < 2) return
        visible.forEach { region ->
            val x = screenX(region.rx.toDouble() * REGION_BLOCKS).roundToInt()
            val y = screenY(region.rz.toDouble() * REGION_BLOCKS).roundToInt()
            val x2 = screenX((region.rx + 1).toDouble() * REGION_BLOCKS).roundToInt()
            val y2 = screenY((region.rz + 1).toDouble() * REGION_BLOCKS).roundToInt()
            graphics.fill(max(x, left), max(y, top), min(x2, right), min(y + 1, bottom), REGION_LINE_COLOR)
            graphics.fill(max(x, left), max(y, top), min(x + 1, right), min(y2, bottom), REGION_LINE_COLOR)
            if (key(region.rx + 1, region.rz) !in indices) { // right edges
                graphics.fill(max(x2, left), max(y, top), min(x2 + 1, right), min(y2, bottom), REGION_LINE_COLOR)
            }
            if (key(region.rx, region.rz + 1) !in indices) { // bottom edges
                graphics.fill(max(x, left), max(y2, top), min(x2, right), min(y2 + 1, bottom), REGION_LINE_COLOR)
            }
        }
    }

    private fun drawDragRect(graphics: GuiGraphicsExtractor) {
        val from = dragFrom ?: return
        val to = dragTo ?: return
        val x = screenX(min(from.x, to.x) * 16.0).roundToInt()
        val y = screenY(min(from.z, to.z) * 16.0).roundToInt()
        val x2 = screenX((max(from.x, to.x) + 1) * 16.0).roundToInt()
        val y2 = screenY((max(from.z, to.z) + 1) * 16.0).roundToInt()
        graphics.fill(x, y, x2, y2, if (dragRemoves) 0x40FF5555 else 0x4033B5E5)
        graphics.fill(x, y, x2, y + 1, REGION_LINE_COLOR)
        graphics.fill(x, y2 - 1, x2, y2, REGION_LINE_COLOR)
        graphics.fill(x, y, x + 1, y2, REGION_LINE_COLOR)
        graphics.fill(x2 - 1, y, x2, y2, REGION_LINE_COLOR)
    }

    /** The armed clip's footprint under the cursor, chunk-snapped. */
    private fun drawPasteGhost(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (pasteClip == null) return
        if (!inMap(mouseX.toDouble(), mouseY.toDouble())) return
        val origin = pasteOrigin(mouseX.toDouble(), mouseY.toDouble())
        if (pasteOffsets.size <= PASTE_FILL_MAX) {
            pasteOffsets.forEach { offset ->
                val cx = origin.x + offset.x
                val cz = origin.z + offset.z
                val x = screenX(cx * 16.0).roundToInt()
                val y = screenY(cz * 16.0).roundToInt()
                val x2 = screenX((cx + 1) * 16.0).roundToInt()
                val y2 = screenY((cz + 1) * 16.0).roundToInt()
                if (x2 > x && y2 > y) graphics.fill(x, y, x2, y2, PASTE_COLOR)
            }
        }
        // The hull is drawn whatever the count, so a clip too big to fill still shows where it lands.
        val x = screenX((origin.x + pasteBounds[0]) * 16.0).roundToInt()
        val y = screenY((origin.z + pasteBounds[1]) * 16.0).roundToInt()
        val x2 = screenX((origin.x + pasteBounds[2] + 1) * 16.0).roundToInt()
        val y2 = screenY((origin.z + pasteBounds[3] + 1) * 16.0).roundToInt()
        graphics.fill(x, y, x2, y + 1, REGION_LINE_COLOR)
        graphics.fill(x, y2 - 1, x2, y2, REGION_LINE_COLOR)
        graphics.fill(x, y, x + 1, y2, REGION_LINE_COLOR)
        graphics.fill(x2 - 1, y, x2, y2, REGION_LINE_COLOR)
    }

    private fun drawHeightBar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Starts on the map's own right border
        val left = mapRight() - 1
        val right = width - MARGIN
        drawBox(graphics, left, mapTop(), right, mapBottom())
        val center = (left + right) / 2
        val label = I18n.get("chunkeditor.map.height")
        graphics.text(font, label, center - font.width(label) / 2, mapTop() + YBAR_PAD + 1, SUBTEXT_COLOR)

        // Both ends of the track keep half a knob inside the box
        yBarTop = mapTop() + YBAR_PAD + font.lineHeight + 4 + YBAR_KNOB_H / 2
        yBarBottom = mapBottom() - YBAR_PAD - YBAR_KNOB_H / 2
        if (!yKnown || yBarBottom - yBarTop < YBAR_KNOB_H * 2) return

        val trackX = center - YBAR_TRACK_W / 2
        val knobY = yScreen(yCut)
        graphics.fill(trackX, yBarTop, trackX + YBAR_TRACK_W, yBarBottom, TRACK_DIM_COLOR)
        graphics.fill(trackX, knobY, trackX + YBAR_TRACK_W, yBarBottom, TRACK_COLOR)

        val hovered = yDragging || inHeightBar(mouseX.toDouble(), mouseY.toDouble())
        val knobX = center - YBAR_KNOB_W / 2
        val knobTop = knobY - YBAR_KNOB_H / 2
        graphics.fill(
            knobX, knobTop, knobX + YBAR_KNOB_W, knobTop + YBAR_KNOB_H,
            if (hovered) KNOB_HOVER_COLOR else KNOB_COLOR,
        )

        if (hovered) {
            graphics.setTooltipForNextFrame(
                font, Component.translatable("chunkeditor.map.height_value", yCut), mouseX, mouseY,
            )
        }
    }

    /** Highest Y at the top of the track, lowest at the bottom */
    private fun yScreen(value: Int): Int {
        val span = (yMax - yMin).coerceAtLeast(1)
        val t = (value - yMin).toDouble() / span
        return (yBarBottom - t * (yBarBottom - yBarTop)).roundToInt()
    }

    private fun yFromScreen(screenY: Double): Int {
        val span = (yBarBottom - yBarTop).coerceAtLeast(1)
        val t = ((yBarBottom - screenY) / span).coerceIn(0.0, 1.0)
        return (yMin + t * (yMax - yMin)).roundToInt()
    }

    private fun inHeightBar(x: Double, y: Double) = yKnown && yBarBottom > yBarTop &&
            x >= mapRight() && x < width - MARGIN &&
            y >= yBarTop - YBAR_KNOB_H / 2 && y <= yBarBottom + YBAR_KNOB_H / 2

    private fun heightCut(): Int? = if (!yKnown || yCut >= yMax) null else yCut

    private fun setCut(value: Int) {
        val next = value.coerceIn(yMin, yMax)
        if (next == yCut) return
        yCut = next
        applyHeightCut()
    }

    /**
     * A new cut invalidates every rendered pixel
     */
    private fun applyHeightCut() {
        terrain.releaseAll()
        coarse.releaseAll()
        rendering.clear()
        coarseRendering.clear()
        loadGen++
    }

    private fun drawInfo(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Starts on the map's own bottom border rather than below it, or the two boxes would stack
        // their 1 px edges into a 2 px rule.
        val top = mapBottom() - 1
        val bottom = height - MARGIN
        drawBox(graphics, MARGIN, top, width - MARGIN, bottom)
        val textY = top + (INFO_ROW_H - font.lineHeight) / 2 + 2
        val secondY = textY + INFO_ROW_H

        val hovering = inMap(mouseX.toDouble(), mouseY.toDouble())
        val blockPosX = if (hovering) floor(blockX(mouseX.toDouble())).toInt() else null
        val blockPosZ = if (hovering) floor(blockZ(mouseY.toDouble())).toInt() else null
        var x = MARGIN + 6
        // Chunk and region are the block coords shifted, so they hide with it rather than freezing.
        x = coordGroup(graphics, "chunkeditor.map.region", blockPosX?.shr(9), blockPosZ?.shr(9), REGION_EXTREME, x, textY)
        x = coordGroup(graphics, "chunkeditor.map.chunk", blockPosX?.shr(4), blockPosZ?.shr(4), CHUNK_EXTREME, x, textY)
        x = coordGroup(graphics, "chunkeditor.map.block", blockPosX, blockPosZ, BLOCK_EXTREME, x, textY)

        val barRight = width - MARGIN - 6
        sliderY = textY + font.lineHeight / 2
        sliderX2 = barRight
        sliderX1 = max(x + 8, barRight - SLIDER_MAX_W)
        if (sliderX2 - sliderX1 < SLIDER_MIN_W) {
            sliderX1 = 0
            sliderX2 = 0
        } else drawZoomSlider(graphics, mouseX, mouseY)

        val selectedLabel = "${I18n.get("chunkeditor.map.selected")}:"
        graphics.text(font, selectedLabel, MARGIN + 6, secondY, SUBTEXT_COLOR)
        val countX = MARGIN + 6 + font.width(selectedLabel) + 4
        graphics.text(font, selected.size.toString(), countX, secondY, -1)
        val summaryX = countX + font.width(reservedCount(selected.size)) + 14

        val summary = when {
            dimension == null -> I18n.get("chunkeditor.map.no_regions")
            loading -> I18n.get("chunkeditor.map.reading")
            else -> I18n.get("chunkeditor.map.summary", indices.size, totalChunks, bytes(totalBytes))
        }
        graphics.text(font, summary, summaryX, secondY, -1)

        val progress = scanProgress
        val status = when {
            clipMessage != null -> clipMessage!!
            progress != null -> I18n.get("chunkeditor.map.scanning", progress.first, progress.second)
            tintsLoading -> I18n.get("chunkeditor.map.biomes")
            else -> I18n.get("chunkeditor.map.hint")
        }
        val statusX = barRight - font.width(status)
        if (statusX > summaryX + font.width(summary) + 8) {
            graphics.text(font, status, statusX, secondY, SUBTEXT_COLOR)
        }
    }

    private fun drawZoomSlider(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val trackY = sliderY - SLIDER_TRACK_H / 2
        graphics.fill(sliderX1, trackY, sliderX2, trackY + SLIDER_TRACK_H, TRACK_COLOR)
        val knobX = sliderX1 + (zoomFraction(scale) * (sliderX2 - sliderX1 - SLIDER_KNOB_W)).roundToInt()
        val knobY = sliderY - SLIDER_KNOB_H / 2
        val hovered = sliderDragging || inSlider(mouseX.toDouble(), mouseY.toDouble())
        graphics.fill(knobX, knobY, knobX + SLIDER_KNOB_W, knobY + SLIDER_KNOB_H, if (hovered) KNOB_HOVER_COLOR else KNOB_COLOR)
    }

    private fun inSlider(x: Double, y: Double) = sliderX2 > sliderX1 &&
            x >= sliderX1 - 2 && x <= sliderX2 + 2 && abs(y - sliderY) <= SLIDER_KNOB_H / 2.0 + 2

    /** Zoom around the map's centre, which [screenX] keeps put on its own. */
    private fun zoomFromSlider(x: Double) {
        val span = (sliderX2 - sliderX1 - SLIDER_KNOB_W).coerceAtLeast(1)
        val t = ((x - sliderX1 - SLIDER_KNOB_W / 2.0) / span).coerceIn(0.0, 1.0)
        scale = snapScale(MIN_SCALE * (MAX_SCALE / MIN_SCALE).pow(t))
    }

    /**
     * One `<label>: <x>,<z>` group
     */
    private fun coordGroup(
        graphics: GuiGraphicsExtractor, labelKey: String, vx: Int?, vz: Int?, extreme: String, x: Int, y: Int,
    ): Int {
        val label = "${I18n.get(labelKey)}:"
        graphics.text(font, label, x, y, SUBTEXT_COLOR)
        val valueX = x + font.width(label) + 4
        graphics.text(font, "${vx ?: "–"},${vz ?: "–"}", valueX, y, -1)
        return valueX + font.width(reserved(vx, vz, extreme)) + 14
    }

    /** The same stepped reservation as [reserved], for a single count */
    private fun reservedCount(value: Int): String =
        COUNT_TIERS.firstOrNull { value <= it }?.toString() ?: value.toString()

    /** The widest value of the first [COORD_TIERS] step both coordinates fit in */
    private fun reserved(vx: Int?, vz: Int?, extreme: String): String {
        val tier = COORD_TIERS.firstOrNull { (lo, hi) -> fits(vx, lo, hi) && fits(vz, lo, hi) }
            ?: return "$extreme,$extreme"
        val widest = listOf(tier.first.toString(), tier.second.toString()).maxByOrNull { font.width(it) }!!
        return "$widest,$widest"
    }

    private fun fits(v: Int?, lo: Int, hi: Int) = v == null || v in lo..hi

    //
    // Textures
    //

    /** The 32x32 chunk bitmap of a region, a pixel per chunk */
    private fun presenceTexture(region: RegionIndex): Identifier {
        val regionKey = key(region.rx, region.rz)
        presence[regionKey]?.let { return it }
        val image = NativeImage(NativeImage.Format.RGBA, REGION_SIZE, REGION_SIZE, false)
        for (z in 0 until REGION_SIZE) {
            for (x in 0 until REGION_SIZE) {
                val present = region.present[z * REGION_SIZE + x]
                val pos = ChunkPos(region.rx * REGION_SIZE + x, region.rz * REGION_SIZE + z)
                val color = when {
                    unreadable.contains(pos.pack()) -> UNREADABLE_COLOR
                    present -> PRESENT_COLOR
                    else -> 0
                }
                image.setPixelABGR(x, z, abgr(color))
            }
        }
        return register("chunkmap/presence/${region.rx}_${region.rz}", image).also { presence[regionKey] = it }
    }

    /** Null when nothing in the region is selected, so the common case costs no blit at all. */
    private fun selectionTexture(region: RegionIndex): Identifier? {
        if (selected.isEmpty()) return null
        val regionKey = key(region.rx, region.rz)
        selection[regionKey]?.let { return it }
        var any = false
        val image = NativeImage(NativeImage.Format.RGBA, REGION_SIZE, REGION_SIZE, false)
        for (z in 0 until REGION_SIZE) {
            for (x in 0 until REGION_SIZE) {
                val pos = ChunkPos(region.rx * REGION_SIZE + x, region.rz * REGION_SIZE + z)
                val on = selected.contains(pos.pack())
                if (on) any = true
                image.setPixelABGR(x, z, if (on) abgr(SELECTED_COLOR) else 0)
            }
        }
        if (!any) {
            image.close()
            return null
        }
        return register("chunkmap/selection/${region.rx}_${region.rz}", image).also { selection[regionKey] = it }
    }

    private fun requestTerrain(region: RegionIndex) {
        if (!tintsDone) return
        val dim = dimension ?: return
        val regionKey = key(region.rx, region.rz)
        if (!rendering.add(regionKey)) return
        val generation = loadGen
        Constants.SCOPE.launch {
            val rendered = ChunkMapRenderer.renderRegion(dim, region.rx, region.rz, tints = tints, maxY = heightCut())
            minecraft.execute {
                // A region that cannot be rendered at all keeps its in-flight marker
                if (rendered != null) rendering.remove(regionKey)
                if (generation != loadGen || rendered == null) {
                    rendered?.image?.close()
                    return@execute
                }
                terrain[regionKey] = register("chunkmap/terrain/${region.rx}_${region.rz}", rendered.image)
                if (rendered.unreadable.isNotEmpty()) {
                    unreadable.addAll(rendered.unreadable)
                    presence.remove(regionKey)?.let { minecraft.textureManager.release(it) }
                }
            }
        }
    }

    private fun requestCoarse(region: RegionIndex) {
        val dim = dimension ?: return
        val regionKey = key(region.rx, region.rz)
        if (!coarseRendering.add(regionKey)) return
        coarseJobs++
        val generation = loadGen
        Constants.SCOPE.launch {
            val rendered = ChunkMapRenderer.renderRegion(dim, region.rx, region.rz, COARSE_STEP, tints, heightCut())
            minecraft.execute {
                coarseJobs--
                if (rendered != null) coarseRendering.remove(regionKey)
                if (generation != loadGen || rendered == null) {
                    rendered?.image?.close()
                    return@execute
                }
                if (coarse.containsKey(regionKey)) {
                    rendered.image.close()
                    return@execute
                }
                coarse[regionKey] = register("chunkmap/coarse/${region.rx}_${region.rz}", rendered.image)
                if (rendered.unreadable.isNotEmpty()) {
                    unreadable.addAll(rendered.unreadable)
                    presence.remove(regionKey)?.let { minecraft.textureManager.release(it) }
                }
            }
        }
    }

    private fun register(path: String, image: NativeImage): Identifier {
        val id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, path)
        minecraft.textureManager.register(id, DynamicTexture({ path }, image))
        return id
    }

    private fun dropTextures() {
        terrain.releaseAll()
        coarse.releaseAll()
        presence.releaseAll()
        selection.releaseAll()
    }

    /**
     * Access-ordered LRU. Registering the same [Identifier] twice would leak the previous texture, so
     * eviction has to hand it back to the texture manager.
     */
    private inner class TextureCache(private val cap: Int) : LinkedHashMap<Long, Identifier>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Identifier>): Boolean {
            if (size <= cap) return false
            minecraft.textureManager.release(eldest.value)
            return true
        }

        fun releaseAll() {
            values.forEach { minecraft.textureManager.release(it) }
            clear()
        }
    }

    //
    // Input
    //

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (dimensionPicker.mouseClicked(x, y)) return true
        if (menus.any { it.mouseClicked(x, y) }) return true
        if (pasteClip != null) {
            clickSound()
            if (event.button() == 0 && inMap(x, y)) confirmPaste(pasteOrigin(x, y)) else cancelPaste()
            return true
        }
        if (event.button() == 0 && inHeightBar(x, y)) {
            yDragging = true
            clickSound()
            yCut = yFromScreen(y)
            return true
        }
        if (event.button() == 0 && inSlider(x, y)) {
            sliderDragging = true
            clickSound()
            zoomFromSlider(x)
            return true
        }
        if (inMap(x, y)) {
            pressX = x
            pressY = y
            moved = false
            when (event.button()) {
                0 -> {
                    panning = true
                    return true
                }

                1 -> {
                    dragRemoves = event.modifiers() and GLFW.GLFW_MOD_SHIFT != 0
                    dragFrom = chunkAt(x, y)
                    dragTo = dragFrom
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        // Only rerender on release
        if (yDragging) {
            yCut = yFromScreen(event.y())
            return true
        }
        if (sliderDragging) {
            zoomFromSlider(event.x())
            return true
        }
        if (abs(event.x() - pressX) > CLICK_SLOP || abs(event.y() - pressY) > CLICK_SLOP) moved = true
        if (panning) {
            centerX -= dragX / scale
            centerZ -= dragY / scale
            return true
        }
        if (dragFrom != null) {
            dragTo = chunkAt(event.x(), event.y())
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (yDragging) {
            yDragging = false
            applyHeightCut()
            return true
        }
        if (sliderDragging) {
            sliderDragging = false
            return true
        }
        if (panning) {
            panning = false
            // A press that never moved is a click on one chunk, not a pan.
            if (!moved && inMap(event.x(), event.y())) toggle(chunkAt(event.x(), event.y()))
            return true
        }
        val from = dragFrom
        val to = dragTo
        if (from != null && to != null) {
            dragFrom = null
            dragTo = null
            if (moved) applyRect(from, to) else toggle(to)
            return true
        }
        return super.mouseReleased(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (dimensionPicker.keyPressed(event)) return true
        if (menus.any { it.keyPressed(event) }) return true
        if (pasteClip != null && event.isEscape) {
            cancelPaste()
            return true
        }
        // Same guards the menu items carry
        val handled = when {
            SC_SELECT_ALL.matches(event) -> run { selectAll(); true }
            SC_INVERT.matches(event) -> run { invertSelection(); true }
            SC_CLEAR.matches(event) -> run { clearSelection(); true }
            SC_TRIM.matches(event) -> run { openTrim(); true }
            SC_EXPORT.matches(event) -> selected.isNotEmpty() && !clipBusy && run { openExport(); true }
            SC_IMPORT.matches(event) -> !clipBusy && run { openLibrary(); true }
            SC_DELETE.matches(event) -> selected.isNotEmpty() && run { confirmDelete(); true }
            SC_REFRESH.matches(event) -> dimension?.let { loadDimension(it); true } ?: false
            else -> false
        }
        if (handled) {
            menus.forEach { it.close() }
            clickSound()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        // Half a section a notch
        if (inHeightBar(mouseX, mouseY)) {
            setCut(yCut + (scrollY.roundToInt() * YBAR_STEP))
            return true
        }
        if (!inMap(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val anchorX = blockX(mouseX)
        val anchorZ = blockZ(mouseY)
        scale = zoom(scale, scrollY)
        // Keep whatever was under the cursor there, so zooming feels like it targets the cursor.
        centerX = anchorX - (mouseX - (mapLeft() + mapRight()) / 2.0) / scale
        centerZ = anchorZ - (mouseY - (mapTop() + mapBottom()) / 2.0) / scale
        return true
    }

    private fun toggle(pos: ChunkPos) {
        if (!exists(pos)) return
        if (!selected.remove(pos.pack())) selected.add(pos.pack())
        onSelectionChanged()
    }

    private fun applyRect(from: ChunkPos, to: ChunkPos) {
        for (z in min(from.z, to.z)..max(from.z, to.z)) {
            for (x in min(from.x, to.x)..max(from.x, to.x)) {
                val pos = ChunkPos(x, z)
                if (!exists(pos)) continue
                if (dragRemoves) selected.remove(pos.pack()) else selected.add(pos.pack())
            }
        }
        onSelectionChanged()
    }

    private fun exists(pos: ChunkPos) = indices[key(pos.regionX, pos.regionZ)]?.contains(pos) == true

    override fun onClose() {
        scanJob?.cancel()
        // Bumping the generation is what makes an in-flight render close its image on arrival instead
        // of registering it into a cache nothing will release again.
        loadGen++
        dropTextures()
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        fun key(rx: Int, rz: Int): Long = rx.toLong() shl 32 or (rz.toLong() and 0xFFFFFFFFL)

        /**
         * A zoom step, snapped so a chunk is always a **whole** number of pixels wide.
         */
        fun zoom(scale: Double, scrollY: Double): Double {
            val pxPerChunk = scale * 16
            // Below a pixel a chunk the integer ladder has no room left
            if (pxPerChunk < 1.0 || (pxPerChunk <= 1.0 && scrollY < 0)) {
                val next = if (scrollY > 0) pxPerChunk * 2 else pxPerChunk / 2
                return (next / 16.0).coerceIn(MIN_SCALE, 1.0 / 16.0)
            }
            val current = pxPerChunk.roundToInt()
            var next = (current * 2.0.pow(scrollY * 0.5)).roundToInt()
            if (scrollY > 0 && next <= current) next = current + 1
            if (scrollY < 0 && next >= current) next = current - 1
            if (next < 1) return 1.0 / 32.0
            return (next / 16.0).coerceAtMost(MAX_SCALE)
        }

        /** Onto the zoom ladder: whole pixels per chunk, halves below one. */
        fun snapScale(raw: Double): Double {
            val px = raw * 16
            val snapped = when {
                px < 0.3536 -> 0.25
                px < 0.7071 -> 0.5
                px < 1.0 -> 1.0
                else -> px.roundToInt().toDouble()
            }
            return (snapped / 16.0).coerceIn(MIN_SCALE, MAX_SCALE)
        }

        fun zoomFraction(scale: Double): Double =
            ln(scale / MIN_SCALE) / ln(MAX_SCALE / MIN_SCALE)

        fun abgr(argb: Int): Int =
            (argb and -0x1000000) or (argb and 0xFF shl 16) or (argb and 0xFF00) or (argb ushr 16 and 0xFF)

        fun bytes(value: Long): String = when {
            value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
            value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
            else -> "%.0f KB".format(value / 1024.0)
        }
    }
}

data class TrimCriteria(
    val maxInhabitedTicks: Long?,
    val olderThanTicks: Long?,
    val minSpawnDistance: Int?,
) {
    val isEmpty get() = maxInhabitedTicks == null && olderThanTicks == null && minSpawnDistance == null
    val needsScan get() = maxInhabitedTicks != null || olderThanTicks != null
}

/**
 * The criteria popup. Each row is a checkbox plus a number
 */
internal class ChunkTrimScreen(
    private val parent: ChunkMapScreen,
    private val onApply: (TrimCriteria) -> Unit,
) : Screen(Component.translatable("chunkeditor.trim.title")) {

    private val panelW = 300
    private var panelTop = 0
    private var panelBottom = 0

    private lateinit var inhabitedBox: Checkbox
    private lateinit var inhabitedValue: EditBox
    private lateinit var staleBox: Checkbox
    private lateinit var staleValue: EditBox
    private lateinit var distanceBox: Checkbox
    private lateinit var distanceValue: EditBox

    override fun init() {
        val left = width / 2 - panelW / 2 + 10
        val fieldX = width / 2 + panelW / 2 - 70
        var y = height / 2 - 60
        panelTop = y - 26

        fun row(key: String, value: String, onCheck: (Boolean) -> Unit): Pair<Checkbox, EditBox> {
            val label = Component.translatable(key)
            val check = addRenderableWidget(
                Checkbox.builder(label, font).pos(left, y).onValueChange { _, v -> onCheck(v) }.build()
            )
            val field = addRenderableWidget(EditBox(font, fieldX, y, 60, 20, label))
            field.value = value
            y += 26
            return check to field
        }

        val inhabited = row("chunkeditor.trim.inhabited", "5") {}
        inhabitedBox = inhabited.first
        inhabitedValue = inhabited.second
        val stale = row("chunkeditor.trim.stale", "60") {}
        staleBox = stale.first
        staleValue = stale.second
        val distance = row("chunkeditor.trim.distance", "32") {}
        distanceBox = distance.first
        distanceValue = distance.second

        panelBottom = y + 40
        addRenderableWidget(
            Button.builder(Component.translatable("mco.template.button.select")) { apply() }
                .bounds(width / 2 - panelW / 2 + 10, y + 6, 130, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + panelW / 2 - 140, y + 6, 130, 20).build()
        )
    }

    private fun apply() {
        onApply(
            TrimCriteria(
                if (inhabitedBox.selected()) minutes(inhabitedValue) else null,
                if (staleBox.selected()) minutes(staleValue) else null,
                if (distanceBox.selected()) distanceValue.value.trim().toIntOrNull() else null,
            )
        )
    }

    private fun minutes(field: EditBox): Long? = field.value.trim().toLongOrNull()?.times(TICKS_PER_MINUTE)

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        drawBox(graphics, width / 2 - panelW / 2, panelTop, width / 2 + panelW / 2, panelBottom)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.text(
            font, Component.translatable("chunkeditor.trim.title").withStyle { it.withBold(true) },
            width / 2 - panelW / 2 + 10, panelTop + 9, -1,
        )
    }

    override fun onClose() = minecraft.gui.setScreen(parent)
}
