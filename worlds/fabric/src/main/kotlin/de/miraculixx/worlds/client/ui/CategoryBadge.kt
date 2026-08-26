package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.data.InstalledMap
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.language.I18n
import net.minecraft.locale.Language

/** Renders a map's main category as a small colored pill (ModMenu-style tag next to the title). */
object CategoryBadge {

    val CATEGORIES: List<String> = listOf("adventure", "parkour", "survival", "puzzle", "horror", "minigames", "build")
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

        "manual" to 0xFF455A64.toInt(),    // slate
    )

    private const val PAD_X = 4

    /** Amber pill on an Installed row whose map has a newer version in the cached index. */
    private val updateLabel: String get() = I18n.get("worlds.update.label")
    private const val UPDATE_COLOR = 0xFFFFC107.toInt()

    fun color(category: String): Int = COLORS[category.lowercase()] ?: 0xFF5A5A5A.toInt()

    /** Pixel width a badge for [category] would occupy (so callers can reserve space / trim). */
    fun width(font: Font, category: String): Int = font.width(label(category)) + PAD_X * 2

    fun updateWidth(font: Font): Int = font.width(updateLabel) + PAD_X * 2

    fun draw(graphics: GuiGraphicsExtractor, font: Font, category: String, x: Int, y: Int): Int =
        pill(graphics, font, label(category), color(category), x, y)

    fun drawUpdate(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int): Int =
        pill(graphics, font, updateLabel, UPDATE_COLOR, x, y)

    /**
     * Draw the pill
     */
    private fun pill(graphics: GuiGraphicsExtractor, font: Font, text: String, border: Int, x: Int, y: Int): Int {
        val w = font.width(text)
        val fill = darken(border, 0.35f)
        val left = x
        val right = x + w + PAD_X * 2
        val top = y - 2
        val bottom = y + font.lineHeight + 1

        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, fill)
        graphics.fill(left + 1, top, right - 1, top + 1, border)
        graphics.fill(left + 1, bottom - 1, right - 1, bottom, border)
        graphics.fill(left, top + 1, left + 1, bottom - 1, border)
        graphics.fill(right - 1, top + 1, right, bottom - 1, border)

        graphics.text(font, text, x + PAD_X, y, 0xFFFFFFFF.toInt())
        return w + PAD_X * 2
    }

    private fun darken(argb: Int, factor: Float): Int {
        val r = ((argb shr 16 and 0xFF) * factor).toInt()
        val g = ((argb shr 8 and 0xFF) * factor).toInt()
        val b = ((argb and 0xFF) * factor).toInt()
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    /** Translated name, falling back to the raw slug for a category we do not ship a key for. */
    fun label(category: String): String = Language.getInstance()
        .getOrDefault("worlds.category.${category.lowercase()}", category.replaceFirstChar { it.uppercase() })
}
