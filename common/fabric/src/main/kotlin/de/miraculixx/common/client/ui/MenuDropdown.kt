package de.miraculixx.common.client.ui

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

private const val BUTTON_H = 20
private const val ROW_H = 14
private const val SEPARATOR_H = 5
private const val PADDING = 6
private const val SHORTCUT_GAP = 20
private const val CHECK_W = 10
private const val CHECK_ON = "☒"
private const val CHECK_OFF = "☐"
private const val ARROW = " ▾"

sealed interface MenuEntry {
    class Item(
        val label: Component,
        val shortcut: String? = null,
        val enabled: () -> Boolean = { true },
        val checked: (() -> Boolean)? = null,
        val action: () -> Unit,
    ) : MenuEntry

    data object Separator : MenuEntry
}

/**
 * Like [Dropdown], but with no state
 */
class MenuDropdown(
    title: Component,
    private val x: Int,
    private val y: Int,
    width: Int,
    private val entries: List<MenuEntry>,
) {
    var open = false
        private set

    /** Set by a screen holding several menus, to close the others when this one opens */
    var onOpen: ((MenuDropdown) -> Unit)? = null

    val button: Button = Button.builder(title.copy().append(ARROW)) { toggle() }
        .bounds(x, y, width, BUTTON_H).build()

    fun close() {
        open = false
    }

    private fun toggle() {
        open = !open
        if (open) onOpen?.invoke(this)
    }

    /** Call last in render-pass */
    fun renderOverlay(graphics: GuiGraphics, font: Font, mouseX: Int, mouseY: Int) {
        if (!open) return
        val top = y + BUTTON_H
        val width = popupWidth(font)
        overlay(graphics) {
            drawBox(graphics, x, top, x + width, top + height())
            var rowTop = top + 1
            entries.forEach { entry ->
                when (entry) {
                    is MenuEntry.Separator -> {
                        graphics.fill(x + 4, rowTop + SEPARATOR_H / 2, x + width - 4, rowTop + SEPARATOR_H / 2 + 1, SUBTEXT_COLOR)
                        rowTop += SEPARATOR_H
                    }

                    is MenuEntry.Item -> {
                        val enabled = entry.enabled()
                        val hovered = enabled && mouseX >= x && mouseX < x + width &&
                            mouseY >= rowTop && mouseY < rowTop + ROW_H
                        if (hovered) graphics.fill(x + 1, rowTop, x + width - 1, rowTop + ROW_H, HOVER_COLOR)
                        val textY = rowTop + (ROW_H - font.lineHeight) / 2 + 1
                        val entryW = when (entry.checked?.invoke()) {
                            true -> { graphics.drawString(font, CHECK_ON, x + 4, textY, -1); CHECK_W }
                            false -> { graphics.drawString(font, CHECK_OFF, x + 4, textY, -1); CHECK_W }
                            null -> 0
                        }
                        graphics.drawString(font, entry.label, x + PADDING + entryW, textY, if (enabled) -1 else SUBTEXT_COLOR)
                        entry.shortcut?.let {
                            graphics.drawString(font, it, x + width - PADDING - font.width(it), textY, SUBTEXT_COLOR)
                        }
                        rowTop += ROW_H
                    }
                }
            }
        }
    }

    /**
     * Call first in the click handler
     */
    fun mouseClicked(mouseX: Double, mouseY: Double): Boolean {
        if (!open) return false
        val font = Minecraft.getInstance().font
        val top = y + BUTTON_H
        if (mouseX >= x && mouseX < x + popupWidth(font) && mouseY >= top && mouseY < top + height()) {
            itemAt(mouseY - top - 1)?.let { item ->
                if (!item.enabled()) return true
                if (item.checked == null) open = false
                clickSound()
                item.action()
            }
            return true
        }
        if (button.isMouseOver(mouseX, mouseY)) return false
        open = false
        return true
    }

    /** Escape belongs to the open menu before it belongs to the screen */
    fun keyPressed(keyCode: Int): Boolean {
        if (!open || keyCode != InputConstants.KEY_ESCAPE) return false
        open = false
        return true
    }

    private fun itemAt(offsetY: Double): MenuEntry.Item? {
        var rowTop = 0
        entries.forEach { entry ->
            val rowHeight = if (entry is MenuEntry.Separator) SEPARATOR_H else ROW_H
            if (offsetY >= rowTop && offsetY < rowTop + rowHeight) return entry as? MenuEntry.Item
            rowTop += rowHeight
        }
        return null
    }

    private fun height() = entries.sumOf { if (it is MenuEntry.Separator) SEPARATOR_H else ROW_H } + 2

    /** Wide enough for the longest label plus its shortcut, never narrower than the button */
    private fun popupWidth(font: Font): Int {
        val widest = entries.filterIsInstance<MenuEntry.Item>().maxOfOrNull { item ->
            font.width(item.label) + (item.shortcut?.let { SHORTCUT_GAP + font.width(it) } ?: 0)
        } ?: 0
        val extraWidth = entries.firstOrNull { it is MenuEntry.Item && it.checked != null }?.let { CHECK_W } ?: 0
        return maxOf(widest + extraWidth + 2 * PADDING, button.width)
    }
}
