package de.miraculixx.common.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/** The panel frame every hand-drawn list in these mods sits in. */
fun drawBox(graphics: GuiGraphicsExtractor, left: Int, top: Int, right: Int, bottom: Int) {
    graphics.fill(left, top, right, bottom, PANEL_BACKGROUND_COLOR)
    graphics.fill(left, top, right, top + 1, PANEL_BORDER_COLOR)
    graphics.fill(left, bottom - 1, right, bottom, PANEL_BORDER_COLOR)
    graphics.fill(left, top, left + 1, bottom, PANEL_BORDER_COLOR)
    graphics.fill(right - 1, top, right, bottom, PANEL_BORDER_COLOR)
}
