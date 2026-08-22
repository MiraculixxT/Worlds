package de.miraculixx.worlds.client.ui

import de.miraculixx.chunkeditor.ChunkEditor
import de.miraculixx.common.client.ui.FIELD_W
import de.miraculixx.common.client.ui.HOVER_COLOR
import de.miraculixx.common.client.ui.NumberField
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.SettingsCategory
import de.miraculixx.common.client.ui.SettingsList
import de.miraculixx.common.client.ui.WIDGET_W
import de.miraculixx.common.client.ui.clickSound
import de.miraculixx.common.client.ui.drawBox
import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.data.InstalledMap
import de.miraculixx.worlds.data.ItemComponents
import de.miraculixx.worlds.data.PackRow
import de.miraculixx.worlds.data.WorldDataPacks
import de.miraculixx.worlds.data.WorldEditor
import de.miraculixx.worlds.data.WorldPlayers
import de.miraculixx.worlds.data.WorldResourcePacks
import de.miraculixx.worlds.data.WorldRules
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.tabs.GridLayoutTab
import net.minecraft.client.gui.components.tabs.MenuTabBar
import net.minecraft.client.gui.components.tabs.TabManager
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.achievement.StatsScreen
import net.minecraft.client.gui.screens.packs.PackSelectionScreen
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen
import net.minecraft.client.gui.screens.worldselection.OptimizeWorldScreen
import net.minecraft.client.gui.screens.worldselection.WorldCreationGameRulesScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.gui.components.tabs.Tab as GuiTab
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.FileUtil
import net.minecraft.util.Util
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer

/**
 * Replacement for vanilla's [EditWorldScreen].
 * Allows direct edits of common world info, bound packs & extras like gamerules.
 *
 * While screen is open, the world is locked ([access])
 */
class WorldEditScreen(
    private val access: LevelStorageSource.LevelStorageAccess,
    private val onDone: () -> Unit,
) : Screen(Component.translatable("worlds.edit.title")) {

    private enum class Tab(val key: String) {
        GENERAL("stat.generalButton"),
        RESOURCE_PACKS("worlds.resource_packs"),
        DATA_PACKS("selectWorld.dataPacks"),
        EXTRA("worlds.edit.tab.extra"),
    }

    private var tab = Tab.GENERAL

    private val tabPages = Tab.entries.map { GridLayoutTab(Component.translatable(it.key)) }
    private val tabManager = TabManager(
        { addRenderableWidget(it) },
        { removeWidget(it) },
        tabConsumer { page -> if (page != null) onTabSelected(page) },
        tabConsumer { },
    )
    private lateinit var tabBar: MenuTabBar

    private val saveDir: Path = access.getLevelPath(LevelResource.ROOT)
    private val iconFile: Path = access.iconFile.orElseGet { saveDir.resolve("icon.png") }

    private var levelName = ""
    private var description = ""
    private var category = InstalledMap.MANUAL_CATEGORY
    private var difficulty = Difficulty.NORMAL
    private var hardcore = false
    private var allowCommands = false
    private var gameType = GameType.SURVIVAL
    private var spawn = BlockPos.ZERO
    private var gameTime = 0L
    private var seed: Long? = null
    private var players = emptyList<WorldPlayers.PlayerData>()
    private var extraLoaded = false

    private lateinit var titleBox: EditBox
    private lateinit var descBox: EditBox
    private lateinit var hardcoreButton: Button
    private val generalWidgets = ArrayList<AbstractWidget>()
    private val dataPackWidgets = ArrayList<AbstractWidget>()
    private val resourcePackWidgets = ArrayList<AbstractWidget>()
    private val extraWidgets = ArrayList<AbstractWidget>()
    private lateinit var extraList: SettingsList

    /** Not rebuilt in [init] to avoid auto-collapse */
    private val categories = listOf(
        SettingsCategory(I18n.get("worlds.extra.world_settings")),
        SettingsCategory(I18n.get("mco.configure.world.buttons.players")),
        SettingsCategory(I18n.get("selectWorld.gameRules")) { openGameRules() },
        SettingsCategory(I18n.get("worlds.extra.chunk_editor")) { openChunkMap() },
    )

    /** The two pack tabs' lists, re-read whenever they or a picker writes to the save. */
    private var packRows = emptyList<PackRow>()
    private var resourceRows = emptyList<PackRow>()

    /** Which field the in-place editor is currently bound to, or null when nothing is being edited. */
    private var editing: Field? = null

    private enum class Field { TITLE, DESCRIPTION }

    private var cardX = 0
    private var cardY = 0
    private var cardW = 0
    private var cardH = 0
    private var iconSize = 0
    // Card-relative y of the first description line and of the category pill
    private var descTop = 0
    private var pillTop = 0
    // Button rows sit narrower than the card and centered on the screen.
    private var rowX = 0
    private var rowW = 0

    init {
        val facts = WorldEditor.readFacts(access)
        levelName = facts.name
        difficulty = facts.difficulty
        hardcore = facts.hardcore
        allowCommands = facts.allowCommands
        gameType = facts.gameType
        spawn = facts.spawn.pos()
        gameTime = facts.gameTime
        val meta = WorldEditor.readMeta(saveDir)
        description = meta?.description.orEmpty()
        category = meta?.categories?.firstOrNull() ?: InstalledMap.MANUAL_CATEGORY
        packRows = WorldDataPacks.listRows(access)
        resourceRows = WorldResourcePacks.listRows(access)
    }

    override fun init() {
        // A resize re-runs init
        commitEdit()
        if (::extraList.isInitialized) extraList.commitEdits()
        generalWidgets.clear()
        dataPackWidgets.clear()
        resourcePackWidgets.clear()
        extraWidgets.clear()

        tabBar = addRenderableWidget(
            MenuTabBar.builder(tabManager, width).addTabs(*tabPages.toTypedArray()).build()
        )
        tabBar.arrangeElements(width)

        cardW = (width - 40).coerceAtMost(CARD_MAX_W)
        cardX = (width - cardW) / 2
        cardY = TAB_BAR_H + 20
        descTop = CARD_PAD + TITLE_H + 2
        pillTop = descTop + DESC_LINES * font.lineHeight + 4
        cardH = pillTop + PILL_H + CARD_PAD
        iconSize = cardH - 2 * CARD_PAD
        rowW = cardW * 3 / 4
        rowX = (width - rowW) / 2

        titleBox = general(
            EditBox(
                font, textX(), cardY + CARD_PAD, textWidth(), TITLE_H,
                Component.translatable("selectWorld.enterName"),
            )
        )
        descBox = general(
            EditBox(
                font, textX(), cardY + descTop, textWidth(), TITLE_H,
                Component.translatable("mco.backup.entry.description"),
            )
        )
        titleBox.setMaxLength(64)
        descBox.setMaxLength(160)
        titleBox.visible = false
        descBox.visible = false

        var y = cardY + cardH + 14
        val wideW = rowW - ICON_BTN - BTN_GAP
        general(
            CycleButton.builder({ d: Difficulty -> d.displayName }, difficulty)
                .withValues(Difficulty.entries)
                .create(rowX, y, wideW, 20, Component.translatable("options.difficulty")) { _, v ->
                    difficulty = v
                    WorldEditor.setDifficulty(access, v)
                }
        )
        hardcoreButton = general(
            Button.builder(hardcoreLabel()) { onHardcorePressed() }
                .bounds(rowX + rowW - ICON_BTN, y, ICON_BTN, 20).build()
        )

        y += 24
        general(
            Button.builder(Component.translatable("selectWorld.edit.optimize")) { optimize() }
                .bounds(rowX, y, wideW, 20).build()
        )
        general(
            Button.builder(Component.literal(ICON_FOLDER)) { openWorldFolder() }
                .tooltip(Tooltip.create(Component.translatable("worlds.tooltip.open_world_folder")))
                .bounds(rowX + rowW - ICON_BTN, y, ICON_BTN, 20).build()
        )

        y += 24
        general(
            Button.builder(Component.translatable("selectWorld.edit.backup")) { backup() }
                .bounds(rowX, y, wideW, 20).build()
        )
        general(
            Button.builder(Component.literal(ICON_BACKUP)) { openBackupFolder() }
                .tooltip(Tooltip.create(Component.translatable("selectWorld.edit.backupFolder")))
                .bounds(rowX + rowW - ICON_BTN, y, ICON_BTN, 20).build()
        )
        syncHardcoreTooltip()

        dataPackWidgets.add(
            addRenderableWidget(
                Button.builder(Component.translatable("dataPack.title")) { openDataPackSelection() }
                    .bounds(rowX, listBottom() + 10, rowW, 20).build()
            )
        )
        resourcePackWidgets.add(
            addRenderableWidget(
                Button.builder(Component.translatable("resourcePack.title")) { openResourcePackSelection() }
                    .bounds(rowX, listBottom() + 10, rowW, 20).build()
            )
        )

        extraList = SettingsList(minecraft, categories, ::extraRowsFor)
        extraList.updateSizeAndPosition(cardW, listBottom() - cardY, cardX, cardY)
        extraList.rebuild()
        extraWidgets.add(addRenderableWidget(extraList))

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20).build()
        )

        tabBar.selectTab(tab.ordinal, false)
        applyTabVisibility()
    }

    private fun <T : AbstractWidget> general(widget: T): T {
        generalWidgets.add(widget)
        return addRenderableWidget(widget)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tabConsumer(action: (GuiTab?) -> Unit): Consumer<GuiTab> =
        Consumer<GuiTab?> { action(it) } as Consumer<GuiTab>

    private fun onTabSelected(page: GuiTab) {
        if (generalWidgets.isEmpty()) return
        val newTab = Tab.entries[tabPages.indexOf(page).coerceAtLeast(0)]
        if (newTab == tab) return
        commitEdit()
        extraList.commitEdits()
        tab = newTab
        if (tab == Tab.EXTRA && !extraLoaded) {
            extraLoaded = true
            seed = WorldRules.readSeed(access)
            players = WorldPlayers.list(access)
            WorldPlayers.resolveNames(players.map { it.id }) { extraList.requestRebuild() }
            extraList.requestRebuild()
        }
        applyTabVisibility()
    }

    private fun applyTabVisibility() {
        val general = tab == Tab.GENERAL
        generalWidgets.forEach { it.visible = general }
        dataPackWidgets.forEach { it.visible = tab == Tab.DATA_PACKS }
        resourcePackWidgets.forEach { it.visible = tab == Tab.RESOURCE_PACKS }
        extraWidgets.forEach { it.visible = tab == Tab.EXTRA }
        if (tab != Tab.EXTRA && focused === extraList) focused = null
        // The two in-place editors only exist while a field is being edited.
        titleBox.visible = general && editing == Field.TITLE
        descBox.visible = general && editing == Field.DESCRIPTION
    }

    //
    // Geometry
    //

    /** Bottom of a listing box (data/resource packs) */
    private fun listBottom() = height - 68

    private fun packRowH() = font.lineHeight + 5
    private fun packRowsTop() = cardY + CARD_PAD + font.lineHeight + 5

    private fun rows() = if (tab == Tab.RESOURCE_PACKS) resourceRows else packRows

    /** Size of the first [PackRow.group] */
    private fun firstGroup() = rows().count { it.group == 0 }
    private fun separated() = firstGroup() > 0 && firstGroup() < rows().size

    /**
     * How many rows are drawn as rows. When they do not all fit, the last slot is spent on a
     * non-interactive "…and N more" line instead of a row.
     */
    private fun shownPackRows(): Int {
        val space = listBottom() - CARD_PAD - packRowsTop() - if (separated()) SEPARATOR_H else 0
        val fits = (space / packRowH()).coerceAtLeast(0)
        return if (rows().size <= fits) rows().size else (fits - 1).coerceAtLeast(0)
    }

    private fun packRowRect(index: Int): Rect {
        val gap = if (separated() && index >= firstGroup()) SEPARATOR_H else 0
        val top = packRowsTop() + index * packRowH() + gap
        return Rect(cardX + CARD_PAD, top, cardX + cardW - CARD_PAD, top + packRowH())
    }

    private fun packToggleRect(index: Int): Rect {
        val row = packRowRect(index)
        val y = row.y1 + (packRowH() - PACK_BOX) / 2
        return Rect(row.x1 + 2, y, row.x1 + 2 + PACK_BOX, y + PACK_BOX)
    }

    private fun packDeleteRect(index: Int): Rect {
        val row = packRowRect(index)
        val y = row.y1 + (packRowH() - PACK_BOX) / 2
        return Rect(row.x2 - 2 - PACK_BOX, y, row.x2 - 2, y + PACK_BOX)
    }

    /** Index of the interactive row under the cursor, or -1. */
    private fun packRowAt(mx: Double, my: Double): Int =
        (0 until shownPackRows()).firstOrNull { (mx to my) in packRowRect(it) } ?: -1

    private fun packHeader(): String = when (tab) {
        // Count ignores bundled/feature packs
        Tab.DATA_PACKS -> headerText(Tab.DATA_PACKS.key, packRows.count { it.group == WorldDataPacks.GROUP_FILE })
        else -> headerText(Tab.RESOURCE_PACKS.key, resourceRows.size)
    }

    private fun headerText(nameKey: String, own: Int): String {
        val name = I18n.get(nameKey)
        return if (own == 0) I18n.get("worlds.packs.none", name) else "$name ($own)"
    }

    private fun packHeaderRect(): Rect {
        val half = font.width(packHeader()) / 2
        return Rect(width / 2 - half, cardY + CARD_PAD, width / 2 + half, cardY + CARD_PAD + font.lineHeight)
    }

    private fun textX() = cardX + CARD_PAD + iconSize + 10
    private fun textWidth() = cardX + cardW - CARD_PAD - textX()
    private fun iconX() = cardX + CARD_PAD
    private fun iconY() = cardY + CARD_PAD

    private fun overIcon(mx: Double, my: Double) =
        mx >= iconX() && mx < iconX() + iconSize && my >= iconY() && my < iconY() + iconSize

    /** Left half of the icon resets it to none, right half opens the file picker. */
    private fun overResetIcon(mx: Double, my: Double) = overIcon(mx, my) && mx < iconX() + iconSize / 2

    private fun titleRect() = Rect(textX(), cardY + CARD_PAD, textX() + textWidth(), cardY + CARD_PAD + TITLE_H)

    private fun descRect() = Rect(
        textX(), cardY + descTop, textX() + textWidth(), cardY + descTop + DESC_LINES * font.lineHeight + 2,
    )

    private fun pillRect() = Rect(
        textX(), cardY + pillTop, textX() + CategoryBadge.width(font, category), cardY + pillTop + PILL_H,
    )

    private data class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
        operator fun contains(p: Pair<Double, Double>) =
            p.first >= x1 && p.first < x2 && p.second >= y1 && p.second < y2
    }

    //
    // Card editing
    //

    private fun beginEdit(field: Field) {
        commitEdit()
        editing = field
        val box = boxOf(field)
        box.setValue(if (field == Field.TITLE) levelName else description)
        box.visible = true
        box.moveCursorToEnd(false)
        focused = box
        box.isFocused = true
    }

    private fun boxOf(field: Field) = if (field == Field.TITLE) titleBox else descBox

    /** Close the open editor without writing anything. */
    private fun cancelEdit() {
        val box = boxOf(editing ?: return)
        editing = null
        box.visible = false
        box.isFocused = false
    }

    /** Write whatever the open editor holds and close it. No-op when nothing is being edited. */
    private fun commitEdit() {
        val field = editing ?: return
        val box = boxOf(field)
        editing = null
        box.visible = false
        box.isFocused = false
        when (field) {
            Field.TITLE -> {
                val value = box.value.trim()
                if (value.isNotEmpty() && value != levelName) {
                    levelName = value
                    WorldEditor.rename(access, value)
                    WorldEditor.updateMeta(saveDir, levelName, title = value)
                }
            }
            Field.DESCRIPTION -> {
                val value = box.value.trim()
                if (value != description) {
                    description = value
                    WorldEditor.updateMeta(saveDir, levelName, description = value)
                }
            }
        }
    }

    private fun cycleCategory() {
        val values = CategoryBadge.FILTER_CATEGORIES
        val next = values[(values.indexOf(category) + 1) % values.size]
        category = next
        WorldEditor.updateMeta(saveDir, levelName, categories = listOf(next))
    }

    //
    // Card Icon
    //

    private fun iconKey(): String? = iconFile.toString().takeIf { Files.isRegularFile(iconFile) }

    private fun resetIcon() {
        val key = iconFile.toString()
        if (WorldEditor.resetIcon(iconFile)) MapTextures.invalidate(key)
    }

    /**
     * Native file picker, edit block before picker closes
     */
    private fun pickIcon() {
        Constants.SCOPE.launch {
            val picked = MemoryStack.stackPush().use { stack ->
                val filters = stack.mallocPointer(1)
                filters.put(stack.UTF8("*.png"))
                filters.flip()
                TinyFileDialogs.tinyfd_openFileDialog(
                    I18n.get("worlds.edit.icon_dialog"), null, filters, I18n.get("worlds.edit.icon_filter"), false,
                )
            } ?: return@launch
            val bytes = try {
                Files.readAllBytes(Path.of(picked))
            } catch (e: Exception) {
                Constants.LOG.warn("Could not read {}: {}", picked, e.message)
                return@launch
            }
            val key = iconFile.toString()
            if (WorldEditor.writeIcon(iconFile, bytes)) {
                Minecraft.getInstance().execute { MapTextures.invalidate(key) }
            }
        }
    }

    //
    // World Buttons
    //

    private fun backup() {
        EditWorldScreen.makeBackupAndShowToast(access)
            .thenAcceptAsync({ minecraft.gui.setScreen(this) }, minecraft)
    }

    /** World pack folders are generated if not yet present (vanilla omits them sometimes?) */
    private fun openFolder(path: Path) {
        try {
            FileUtil.createDirectoriesSafe(path)
        } catch (e: Exception) {
            Constants.LOG.error("Could not create folder {}", path, e)
            return
        }
        Util.getPlatform().openPath(path)
    }

    private fun openBackupFolder() = openFolder(minecraft.levelSource.backupPath)

    private fun openWorldFolder() = Util.getPlatform().openPath(saveDir)

    /**
     * Vanilla's world "optimizing".
     * It does weird things, so we just exit the screen and force the user to reload
     */
    private fun optimize() {
        minecraft.gui.setScreen(
            BackupConfirmScreen(
                { minecraft.gui.setScreen(this) },
                { backup, eraseCache ->
                    EditWorldScreen.conditionallyMakeBackupAndShowToast(backup, access).thenAcceptAsync({
                        minecraft.gui.setScreen(
                            OptimizeWorldScreen.create(
                                minecraft, { onDone() }, minecraft.fixerUpper, access, eraseCache,
                            )
                        )
                    }, minecraft)
                },
                Component.translatable("optimizeWorld.confirm.title"),
                Component.translatable("optimizeWorld.confirm.description"),
                Component.translatable("optimizeWorld.confirm.proceed"),
                true,
            )
        )
    }

    //
    // Extra
    //

    private fun extraRowsFor(category: SettingsCategory): List<SettingsList.Row> =
        when (categories.indexOf(category)) {
            0 -> worldSettingRows()
            1 -> playerRows()
            else -> emptyList()
        }

    private fun worldSettingRows(): List<SettingsList.Row> {
        val seedValue = seed
        return listOf(
            extraList.TextRow(
                I18n.get("mco.backup.entry.seed"),
                seedValue?.toString() ?: I18n.get("selectWorld.versionUnknown"),
                Component.translatable("worlds.copy"),
                seedValue?.let { { minecraft.keyboardHandler.setClipboard(it.toString()) } },
            ),
            extraList.ToggleRow(I18n.get("selectWorld.allowCommands"), allowCommands) {
                allowCommands = it
                WorldEditor.setAllowCommands(access, it)
            },
            extraList.CycleRow(
                I18n.get("selectWorld.gameMode"), GameType.entries, gameType, GameType::getShortDisplayName,
            ) {
                gameType = it
                WorldEditor.setGameType(access, it)
            },
            extraList.NumberRow(
                I18n.get("worlds.setting.world_spawn"),
                listOf(
                    numberField(spawn.x.toLong(), HORIZONTAL_RANGE) { moveSpawn(x = it.toInt()) },
                    numberField(spawn.y.toLong(), VERTICAL_RANGE) { moveSpawn(y = it.toInt()) },
                    numberField(spawn.z.toLong(), HORIZONTAL_RANGE) { moveSpawn(z = it.toInt()) },
                ),
            ),
            extraList.NumberRow(
                I18n.get("worlds.setting.world_ticks"),
                listOf(numberField(gameTime, 0..Long.MAX_VALUE, WIDGET_W) {
                    gameTime = it
                    WorldEditor.setGameTime(access, it)
                }),
            ),
        )
    }

    private fun numberField(value: Long, range: LongRange, width: Int = FIELD_W, apply: (Long) -> Unit) =
        NumberField(font, width, value, range, apply)

    private fun moveSpawn(x: Int = spawn.x, y: Int = spawn.y, z: Int = spawn.z) {
        spawn = BlockPos(x, y, z)
        WorldEditor.setSpawn(access, spawn)
    }

    /** Every `playerdata/<uuid>.dat` the save holds */
    private fun playerRows(): List<SettingsList.Row> {
        if (players.isEmpty()) {
            return listOf(extraList.TextRow(I18n.get("worlds.players.none"), "", onPress = null))
        }
        return players.map { player ->
            val onStats: (() -> Unit)? = if (player.hasStats) ({ openPlayerStats(player) }) else null
            extraList.TextRow(
                WorldPlayers.displayName(player.id), player.summary(), Component.translatable("gui.stats"), onStats,
            )
        }
    }

    /**
     * Vanilla's stats screen, rebuild to support server-less. Item-Cmps have to be bound for this
     */
    private fun openPlayerStats(player: WorldPlayers.PlayerData) {
        val stats = WorldPlayers.readStats(access, player.id)
        if (!ItemComponents.bound()) {
            minecraft.gui.setScreen(GenericMessageScreen(Component.translatable("worlds.edit.reading_stats")))
        }
        ItemComponents.prepare { ok ->
            if (!ok) {
                minecraft.gui.setScreen(this)
                return@prepare
            }
            val screen = StatsScreen(this, stats)
            (screen as OfflineStatsScreen).worlds_useLocalStats()
            minecraft.gui.setScreen(screen)
        }
    }

    /**
     * Vanilla's world creation rules screen over the save's own `game_rules.dat`.
     */
    private fun openGameRules() {
        val features = WorldEditor.readFeatures(access)
        val rules = WorldRules.readGameRules(access, features)
        minecraft.gui.setScreen(
            WorldCreationGameRulesScreen(rules) { result ->
                result.ifPresent { WorldRules.writeGameRules(access, features, it) }
                minecraft.gui.setScreen(this)
            }
        )
    }

    /**
     * The editor reads the spawn and the world time off disk itself, so pending edits have to be
     * flushed first.
     */
    private fun openChunkMap() {
        extraList.commitEdits()
        ChunkEditor.open(this, access)
    }

    //
    // Data packs
    //

    /**
     * Vanilla's own picker over [WorldDataPacks.createRepository]
     */
    private fun openDataPackSelection() {
        val repository = WorldDataPacks.createRepository(access)
        repository.reload()
        repository.setSelected(
            WorldEditor.readEnabledPacks(access).ifEmpty { listOf(WorldDataPacks.VANILLA) }
        )
        minecraft.gui.setScreen(
            PackSelectionScreen(
                repository,
                { applied ->
                    WorldDataPacks.apply(access, applied)
                    packRows = WorldDataPacks.listRows(access)
                    minecraft.gui.setScreen(this)
                },
                WorldDataPacks.libraryDir(),
                Component.translatable("dataPack.title"),
            )
        )
    }

    /**
     * The same picker over [WorldResourcePacks.createRepository].
     * Due to missing vanilla, empty leaving requires esc.
     */
    private fun openResourcePackSelection() {
        val repository = WorldResourcePacks.createRepository(access)
        repository.reload()
        repository.setSelected(WorldResourcePacks.selectedIds(access))
        minecraft.gui.setScreen(
            PackSelectionScreen(
                repository,
                { applied ->
                    WorldResourcePacks.apply(access, applied)
                    resourceRows = WorldResourcePacks.listRows(access)
                    minecraft.gui.setScreen(this)
                },
                WorldResourcePacks.libraryDir(),
                Component.translatable("resourcePack.title"),
            )
        )
    }

    private fun togglePack(index: Int) {
        val row = rows()[index]
        if (tab == Tab.RESOURCE_PACKS) {
            if (WorldResourcePacks.setEnabled(access, row, !row.enabled)) {
                resourceRows = WorldResourcePacks.listRows(access)
            }
            return
        }
        packRows = packRows.toMutableList().apply { this[index] = row.copy(enabled = !row.enabled) }
        WorldDataPacks.writeRows(access, packRows)
    }

    private fun confirmDeletePack(row: PackRow) {
        val resource = tab == Tab.RESOURCE_PACKS
        minecraft.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) deletePack(resource, row)
                    minecraft.gui.setScreen(this)
                },
                Component.translatable("worlds.edit.delete_pack"),
                Component.translatable("worlds.edit.delete_pack_question", row.name, levelName),
                CommonComponents.GUI_PROCEED,
                CommonComponents.GUI_CANCEL,
            )
        )
    }

    private fun deletePack(resource: Boolean, row: PackRow) {
        if (resource) {
            if (WorldResourcePacks.deletePack(access, row)) resourceRows = WorldResourcePacks.listRows(access)
        } else if (WorldDataPacks.deletePack(access, row)) {
            packRows = WorldDataPacks.listRows(access)
            WorldDataPacks.writeRows(access, packRows)
        }
    }

    private fun hardcoreLabel(): Component =
        Component.literal(ICON_HARDCORE).withColor(if (hardcore) 0xFF5555 else 0x9BD698)

    private fun syncHardcoreTooltip() {
        hardcoreButton.setTooltip(
            Tooltip.create(
                Component.translatable(if (hardcore) "worlds.edit.hardcore_on" else "worlds.edit.hardcore_off")
            )
        )
    }

    /** Turning hardcore on locks the world on death, so it is confirmed; turning it off is not. */
    private fun onHardcorePressed() {
        if (hardcore) {
            setHardcore(false)
            return
        }
        minecraft.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) setHardcore(true)
                    minecraft.gui.setScreen(this)
                },
                Component.translatable("selectWorld.gameMode.hardcore"),
                Component.translatable("worlds.edit.hardcore_question", levelName),
                CommonComponents.GUI_PROCEED,
                CommonComponents.GUI_CANCEL,
            )
        )
    }

    private fun setHardcore(value: Boolean) {
        hardcore = value
        WorldEditor.setHardcore(access, value)
        hardcoreButton.message = hardcoreLabel()
        syncHardcoreTooltip()
    }

    //
    // Render Stuff
    //

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        when (tab) {
            Tab.GENERAL -> drawCard(graphics, mouseX, mouseY)
            Tab.DATA_PACKS, Tab.RESOURCE_PACKS -> drawPackList(graphics, mouseX, mouseY)
            Tab.EXTRA -> Unit
        }
    }

    /**
     * A list of all available packs. RPs are a simple folder walk, DPs also contain featured packs
     */
    private fun drawPackList(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        drawBox(graphics, cardX, cardY, cardX + cardW, listBottom())
        val rows = rows()
        val point = mouseX.toDouble() to mouseY.toDouble()
        // Header links to folder (underline on hover)
        val hoverHeader = point in packHeaderRect()
        val header = Component.literal(packHeader())
            .withStyle { if (hoverHeader) it.withUnderlined(true) else it }
        graphics.centeredText(font, header, width / 2, cardY + CARD_PAD, 0xFFFFFFFF.toInt())

        val shown = shownPackRows()
        if (separated() && shown > firstGroup()) {
            val y = packRowsTop() + firstGroup() * packRowH() + SEPARATOR_H / 2
            graphics.fill(cardX + CARD_PAD, y, cardX + cardW - CARD_PAD, y + 1, 0xFF505050.toInt())
        }
        for (index in 0 until shown) {
            val row = rows[index]
            val rect = packRowRect(index)
            val toggle = packToggleRect(index)
            val delete = packDeleteRect(index)
            if (point in rect) graphics.fill(rect.x1, rect.y1, rect.x2, rect.y2, HOVER_COLOR)

            drawToggle(graphics, toggle, row.enabled, point in toggle)
            val textY = rect.y1 + (packRowH() - font.lineHeight) / 2 + 1
            val nameX = toggle.x2 + 6
            val nameEnd = if (row.deletable) delete.x1 - 4 else rect.x2 - 2
            graphics.text(
                font, trim(row.name, nameEnd - nameX), nameX, textY,
                if (row.enabled) 0xFFFFFFFF.toInt() else SUBTEXT_COLOR,
            )
            // A feature pack lives in the jar, so it gets no delete glyph at all rather than a dead one.
            if (row.deletable) {
                graphics.centeredText(
                    font, Component.literal(ICON_RESET), (delete.x1 + delete.x2) / 2, textY,
                    if (point in delete) 0xFFFF6060.toInt() else SUBTEXT_COLOR,
                )
            }
        }
        if (rows.size > shown) {
            val rect = packRowRect(shown)
            graphics.text(
                font, I18n.get("worlds.packs.more", rows.size - shown), rect.x1 + 2,
                rect.y1 + (packRowH() - font.lineHeight) / 2, SUBTEXT_COLOR,
            )
        }
    }

    private fun drawToggle(graphics: GuiGraphicsExtractor, rect: Rect, on: Boolean, hover: Boolean) {
        graphics.fill(rect.x1, rect.y1, rect.x2, rect.y2, 0xFF1A1A1A.toInt())
        val border = if (hover) -1 else 0xFF808080.toInt()
        graphics.fill(rect.x1, rect.y1, rect.x2, rect.y1 + 1, border)
        graphics.fill(rect.x1, rect.y2 - 1, rect.x2, rect.y2, border)
        graphics.fill(rect.x1, rect.y1, rect.x1 + 1, rect.y2, border)
        graphics.fill(rect.x2 - 1, rect.y1, rect.x2, rect.y2, border)
        if (on) graphics.fill(rect.x1 + 3, rect.y1 + 3, rect.x2 - 3, rect.y2 - 3, 0xFF55DD55.toInt())
    }

    private fun drawCard(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        drawBox(graphics, cardX, cardY, cardX + cardW, cardY + cardH)

        drawIcon(graphics, mouseX, mouseY)

        val tx = textX()
        val tw = textWidth()
        val hoverTitle = !titleBox.visible && (mouseX.toDouble() to mouseY.toDouble()) in titleRect()
        val hoverDesc = !descBox.visible && (mouseX.toDouble() to mouseY.toDouble()) in descRect()
        if (hoverTitle) graphics.fill(tx - 2, cardY + CARD_PAD - 2, tx + tw + 2, cardY + CARD_PAD + TITLE_H, HOVER_COLOR)
        if (hoverDesc) {
            val r = descRect()
            graphics.fill(r.x1 - 2, r.y1 - 2, r.x2 + 2, r.y2, HOVER_COLOR)
        }

        if (!titleBox.visible) {
            graphics.text(
                font, trim(levelName, tw), tx, cardY + CARD_PAD + 2,
                if (hoverTitle) -1 else 0xFFFFFFFF.toInt(),
            )
        }
        if (!descBox.visible) {
            // Both detail lines carry the description; an empty one invites the click instead.
            val lines = if (description.isBlank()) listOf(I18n.get("worlds.edit.empty_description"))
            else clampLines(description, tw, DESC_LINES)
            val color = if (description.isBlank()) 0xFF707070.toInt() else SUBTEXT_COLOR
            lines.forEachIndexed { i, line ->
                graphics.text(font, line, tx, cardY + descTop + i * font.lineHeight, color)
            }
        }

        // Category pill: a click cycles it, so it gets the same hover wash as the text rows.
        val pill = pillRect()
        if ((mouseX.toDouble() to mouseY.toDouble()) in pill) {
            graphics.fill(pill.x1 - 1, pill.y1 - 1, pill.x2 + 1, pill.y2 + 1, HOVER_COLOR)
        }
        CategoryBadge.draw(graphics, font, category, tx, cardY + pillTop + 2)
    }

    private fun drawIcon(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val x = iconX()
        val y = iconY()
        val icon = MapTextures.get(iconKey())
        if (icon != null) {
            graphics.blit(
                RenderPipelines.GUI_TEXTURED, icon.id, x, y, 0f, 0f,
                iconSize, iconSize, icon.width, icon.height, icon.width, icon.height,
            )
        } else {
            graphics.fill(x, y, x + iconSize, y + iconSize, 0xFF2A2A2A.toInt())
        }
        if (!overIcon(mouseX.toDouble(), mouseY.toDouble())) return

        // Same wash the list row uses for its play overlay, but split into reset | replace.
        graphics.fill(x, y, x + iconSize, y + iconSize, ICON_HOVER_OVERLAY)
        val half = iconSize / 2
        val overReset = overResetIcon(mouseX.toDouble(), mouseY.toDouble())
        if (overReset) graphics.fill(x, y, x + half, y + iconSize, 0x40FFFFFF)
        else graphics.fill(x + half, y, x + iconSize, y + iconSize, 0x40FFFFFF)
        val glyphY = y + (iconSize - font.lineHeight) / 2
        graphics.centeredText(font, Component.literal(ICON_RESET), x + half / 2, glyphY, -1)
        graphics.centeredText(font, Component.literal(ICON_CHANGE), x + half + half / 2, glyphY, -1)
    }

    /** Word-wrap [text] to [width] and keep at most [maxLines], ending the last one with an ellipsis. */
    private fun clampLines(text: String, width: Int, maxLines: Int): List<String> {
        val lines = font.splitIgnoringLanguage(Component.literal(text), width).map { it.string }
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        var last = kept.last().trimEnd()
        while (last.isNotEmpty() && font.width("$last…") > width) last = last.dropLast(1)
        kept[kept.lastIndex] = "$last…"
        return kept
    }

    private fun trim(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && font.width("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }

    //
    // GUI inputs
    //

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if ((tab == Tab.DATA_PACKS || tab == Tab.RESOURCE_PACKS) && event.button() == 0) {
            if ((event.x() to event.y()) in packHeaderRect()) {
                clickSound()
                openFolder(
                    if (tab == Tab.DATA_PACKS) WorldDataPacks.worldDir(access)
                    else WorldResourcePacks.worldDir(access),
                )
                return true
            }
            val index = packRowAt(event.x(), event.y())
            val row = rows().getOrNull(index)
            if (row != null) {
                val point = event.x() to event.y()
                if (point in packToggleRect(index)) {
                    clickSound()
                    togglePack(index)
                    return true
                }
                if (row.deletable && point in packDeleteRect(index)) {
                    clickSound()
                    confirmDeletePack(row)
                    return true
                }
            }
        }
        if (tab == Tab.GENERAL && event.button() == 0) {
            val point = event.x() to event.y()
            if (overIcon(event.x(), event.y())) {
                commitEdit()
                clickSound()
                if (overResetIcon(event.x(), event.y())) resetIcon() else pickIcon()
                return true
            }
            if (!titleBox.visible && point in titleRect()) {
                beginEdit(Field.TITLE)
                return true
            }
            if (!descBox.visible && point in descRect()) {
                beginEdit(Field.DESCRIPTION)
                return true
            }
            if (point in pillRect()) {
                commitEdit()
                clickSound()
                cycleCategory()
                return true
            }
            // A click anywhere outside the open editor closes it, writing what was typed.
            val box = editing?.let { boxOf(it) }
            if (box != null && !box.isMouseOver(event.x(), event.y())) commitEdit()
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (editing != null) {
            // Enter writes the field, Escape drops it; neither may reach the screen's close handling.
            if (event.isConfirmation) {
                commitEdit()
                return true
            }
            if (event.isEscape) {
                cancelEdit()
                return true
            }
        }
        if (super.keyPressed(event)) return true
        return tabBar.keyPressed(event)
    }

    override fun onClose() {
        commitEdit()
        extraList.commitEdits()
        onDone()
    }

    private companion object {
        val HORIZONTAL_RANGE = -30_000_000L..30_000_000L
        val VERTICAL_RANGE = -2048L..2047L

        /** Height of [MenuTabBar] (its own private constant). */
        const val TAB_BAR_H = 24
        const val CARD_MAX_W = 360
        const val CARD_PAD = 6
        /** Title row height; also the height of the in-place editors. */
        const val TITLE_H = 12
        /** What [CategoryBadge.draw] occupies vertically (`lineHeight + 3`). */
        const val PILL_H = 12
        const val ICON_BTN = 20
        const val BTN_GAP = 4
        const val DESC_LINES = 2
        const val PACK_BOX = 10
        /** Vertical space the rule between the feature packs and the save's own occupies. */
        const val SEPARATOR_H = 7
        const val ICON_HOVER_OVERLAY = -1601138544
        const val ICON_FOLDER = "📂"
        const val ICON_BACKUP = "\uD83D\uDDD0"
        const val ICON_RESET = "🗑"
        const val ICON_CHANGE = "✎"
        const val ICON_HARDCORE = "❤"
    }
}
