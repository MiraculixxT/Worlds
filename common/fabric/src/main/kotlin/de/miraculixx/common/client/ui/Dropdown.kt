package de.miraculixx.common.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

private const val BUTTON_H = 20
private const val ROW_H = 14
private const val ARROW = "  ▾"

/**
 * A pick-one list.
 *
 * The open list has to draw over everything and be hit-tested before everything.
 *
 * ```
 * override fun init() { addRenderableWidget(dropdown.button) }
 * override fun extractRenderState(…) { …; dropdown.renderOverlay(graphics, font, mouseX, mouseY) }
 * override fun mouseClicked(event, doubleClick) { if (dropdown.mouseClicked(x, y)) return true; … }
 * override fun keyPressed(event) = dropdown.keyPressed(event) || super.keyPressed(event)
 * ```
 */
class Dropdown<T>(
    private val x: Int,
    private val y: Int,
    private val width: Int,
    entries: List<T>,
    selected: T?,
    private val label: (T) -> String,
    private val onSelect: (T) -> Unit,
) {
    var entries: List<T> = entries
        set(value) {
            field = value
            open = false
            syncButton()
        }

    var selected: T? = selected
        private set

    var open = false
        private set

    val button: Button = Button.builder(Component.empty()) { open = !open }
        .bounds(x, y, width, BUTTON_H).build()

    init {
        syncButton()
    }

    /** Picks [value], closes the list, and notifies only when it actually changed. */
    fun select(value: T) {
        open = false
        if (value == selected) return
        selected = value
        syncButton()
        onSelect(value)
    }

    fun close() {
        open = false
    }

    /** Call **last** in the screen's render pass, so the list covers whatever it overlaps. */
    fun renderOverlay(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int) {
        if (!open) return
        val top = y + BUTTON_H
        drawBox(graphics, x, top, x + width, top + height())
        entries.forEachIndexed { index, entry ->
            val rowTop = top + 1 + index * ROW_H
            if (mouseX >= x && mouseX < x + width && mouseY >= rowTop && mouseY < rowTop + ROW_H) {
                graphics.fill(x + 1, rowTop, x + width - 1, rowTop + ROW_H, HOVER_COLOR)
            }
            val color = if (entry == selected) -1 else SUBTEXT_COLOR
            graphics.text(font, label(entry), x + 6, rowTop + (ROW_H - font.lineHeight) / 2 + 1, color)
        }
    }

    /**
     * Call **first** in the screen's click handler. True means the click belonged to the list and the
     * screen must not act on it.
     */
    fun mouseClicked(mouseX: Double, mouseY: Double): Boolean {
        if (!open) return false
        val top = y + BUTTON_H
        if (mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + height()) {
            entries.getOrNull(((mouseY - top - 1) / ROW_H).toInt())?.let { select(it) }
            return true
        }
        if (button.isMouseOver(mouseX, mouseY)) return false
        open = false
        return true
    }

    /** Escape belongs to the open list before it belongs to the screen. */
    fun keyPressed(event: KeyEvent): Boolean {
        if (!open || !event.isEscape) return false
        open = false
        return true
    }

    private fun height() = entries.size * ROW_H + 2

    private fun syncButton() {
        val current = selected
        button.message = Component.literal(
            if (current == null) "—" else label(current) + if (entries.size > 1) ARROW else ""
        )
        button.active = entries.size > 1
    }
}
