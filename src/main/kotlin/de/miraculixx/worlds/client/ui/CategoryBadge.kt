package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.data.InstalledMap
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Renders a map's main category as a small colored pill (ModMenu-style tag next to the title). */
object CategoryBadge {

    /**
     * The documented category taxonomy — kept in sync with the `categories` field described in
     * README.md. These are the values a map may declare for itself.
     */
    val CATEGORIES: List<String> =
        listOf("adventure", "parkour", "survival", "puzzle", "horror", "minigames", "build")

    /**
     * Values offered by the filter popup: the taxonomy plus [InstalledMap.MANUAL_CATEGORY], which no
     * map declares but every unmanaged save is tagged with — so Installed can be narrowed to them.
     */
    val FILTER_CATEGORIES: List<String> = CATEGORIES + InstalledMap.MANUAL_CATEGORY

    /** Distinct color per known theme; anything else falls back to a neutral gray. */
    private val COLORS: Map<String, Int> = mapOf(
        "adventure" to 0xFF4CAF50.toInt(), // green
        "parkour" to 0xFFFF9800.toInt(),   // orange
        "puzzle" to 0xFF9C27B0.toInt(),    // purple
        "horror" to 0xFFB71C1C.toInt(),    // dark red
        "survival" to 0xFF00897B.toInt(),  // teal
        "minigames" to 0xFF2196F3.toInt(), // blue
        "build" to 0xFF795548.toInt(),     // brown
        // Not a Modrinth category: marks a save this mod doesn't manage (no worlds.meta.json).
        "manual" to 0xFF455A64.toInt(),    // slate
    )

    private const val PAD_X = 3

    fun color(category: String): Int = COLORS[category.lowercase()] ?: 0xFF5A5A5A.toInt()

    /** Pixel width a badge for [category] would occupy (so callers can reserve space / trim). */
    fun width(font: Font, category: String): Int = font.width(label(category)) + PAD_X * 2

    /** Draw the pill at (x, y); returns the width consumed. */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, category: String, x: Int, y: Int): Int {
        val text = label(category)
        val w = font.width(text)
        graphics.fill(x, y - 1, x + w + PAD_X * 2, y + font.lineHeight, color(category))
        graphics.text(font, text, x + PAD_X, y, 0xFFFFFFFF.toInt())
        return w + PAD_X * 2
    }

    private fun label(category: String): String = category.replaceFirstChar { it.uppercase() }
}
