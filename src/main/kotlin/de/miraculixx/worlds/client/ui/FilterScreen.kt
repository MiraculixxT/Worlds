package de.miraculixx.worlds.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** How a map's supported versions are matched against the running MC version. */
enum class VersionMode(val label: String) { EQUAL("Equal"), EQUAL_HIGHER("Equal & higher"), ALL("All") }

/**
 * Sort key. Downloads/Date are descending by nature; [SortMode] pairs with a reverse toggle.
 * On the Installed tab, Date means *last played* and unmanaged worlds count as 0 downloads.
 */
enum class SortMode(val label: String) { AZ("A → Z"), DOWNLOADS("Downloads"), DATE("Date") }

/** Sentinel category meaning "no category filter". */
const val ALL_CATEGORIES = "All"

/**
 * Filter popup: category (cycle), version match (cycle), sort key (cycle) and a reverse toggle.
 * Stateless w.r.t. tabs — the caller hands in the active tab's selection and gets it back on close.
 */
class FilterScreen(
    private val parent: WorldsScreen,
    private val categories: List<String>,
    private var category: String,
    private var version: VersionMode,
    private var sort: SortMode,
    private var reverse: Boolean,
) : Screen(Component.literal("Filters")) {

    private val panelW = 280
    private val ctrlW = 150
    private val labels = ArrayList<Pair<String, Int>>()
    private var panelTop = 0
    private var panelBottom = 0
    private var dividerY = 0
    private lateinit var reverseButton: Button

    override fun init() {
        labels.clear()
        val ctrlX = width / 2 + panelW / 2 - ctrlW - 8
        var y = height / 2 - 66
        panelTop = y - 30

        fun row(label: String) = (y).also { labels.add(label to it); y += 24 }

        val catValues = listOf(ALL_CATEGORIES) + categories
        if (category !in catValues) category = ALL_CATEGORIES
        addRenderableWidget(
            CycleButton.builder({ Component.literal(it) }, category)
                .withValues(catValues)
                .create(ctrlX, row("Category"), ctrlW, 20, Component.literal("Category")) { _, v -> category = v }
        )
        addRenderableWidget(
            CycleButton.builder({ Component.literal(it.label) }, version)
                .withValues(VersionMode.entries)
                .create(ctrlX, row("MC version"), ctrlW, 20, Component.literal("Version")) { _, v -> version = v }
        )

        // Divider between the filters (above) and the ordering controls (below).
        dividerY = y
        y += 5

        // Sort key + a small square reverse toggle (↑ ascending / ↓ descending) to its right.
        val sortW = ctrlW - 24
        val sortY = row("Sort by")
        addRenderableWidget(
            CycleButton.builder({ Component.literal(it.label) }, sort)
                .withValues(SortMode.entries)
                .create(ctrlX, sortY, sortW, 20, Component.literal("Sort")) { _, v -> sort = v }
        )
        reverseButton = addRenderableWidget(
            Button.builder(reverseArrow()) { toggleReverse() }
                .bounds(ctrlX + ctrlW - 20, sortY, 20, 20).build()
        )

        val by = y + 8
        panelBottom = by + 28
        val px = width / 2 - panelW / 2
        addRenderableWidget(
            Button.builder(Component.literal("Reset")) { resetAll() }
                .bounds(px + 8, by, 80, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(px + panelW - 108, by, 100, 20).build()
        )
    }

    /** ↓ when reverse is on (descending), ↑ when off (ascending). */
    private fun reverseArrow(): Component = Component.literal(if (reverse) "↓" else "↑")

    private fun toggleReverse() {
        reverse = !reverse
        reverseButton.message = reverseArrow()
    }

    private fun resetAll() {
        category = ALL_CATEGORIES
        version = VersionMode.ALL
        sort = SortMode.AZ
        reverse = false
        rebuildWidgets()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Soft black panel behind the controls (background pass → sits under the widgets).
        val px = width / 2 - panelW / 2
        graphics.fill(px, panelTop, px + panelW, panelBottom, 0x8D000000.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val px = width / 2 - panelW / 2
        val pr = px + panelW

        val border = 0xFF505050.toInt()
        graphics.fill(px, panelTop, pr, panelTop + 1, border)
        graphics.fill(px, panelBottom - 1, pr, panelBottom, border)
        graphics.fill(px, panelTop, px + 1, panelBottom, border)
        graphics.fill(pr - 1, panelTop, pr, panelBottom, border)

        graphics.text(font, Component.literal("Filters").withStyle { it.withBold(true) }, px + 8, panelTop + 10, -1)
        for ((label, y) in labels) {
            graphics.text(font, label, px + 8, y + 6, 0xFFC0C0C0.toInt())
        }
        // Separator between filters and ordering controls.
        graphics.fill(px + 8, dividerY, pr - 8, dividerY + 1, 0xFF505050.toInt())
    }

    override fun onClose() {
        parent.applyFilters(if (category == ALL_CATEGORIES) null else category, version, sort, reverse)
        minecraft.gui.setScreen(parent)
    }
}
