package de.miraculixx.common.client.ui

import net.minecraft.client.gui.GuiGraphics

/** The panel frame every hand-drawn list in these mods sits in. */
fun drawBox(graphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int) {
    graphics.fill(left, top, right, bottom, PANEL_BACKGROUND_COLOR)
    graphics.fill(left, top, right, top + 1, PANEL_BORDER_COLOR)
    graphics.fill(left, bottom - 1, right, bottom, PANEL_BORDER_COLOR)
    graphics.fill(left, top, left + 1, bottom, PANEL_BORDER_COLOR)
    graphics.fill(right - 1, top, right, bottom, PANEL_BORDER_COLOR)
}

/**
 * 1.21 depth-tests the GUI and vanilla draws item icons at z 150, so anything meant to cover them -
 * an open popup - has to be lifted out of their way. Vanilla's own tooltips sit at 400.
 */
inline fun overlay(graphics: GuiGraphics, body: () -> Unit) {
    val pose = graphics.pose()
    pose.pushPose()
    pose.translate(0f, 0f, OVERLAY_Z)
    body()
    pose.popPose()
}

const val OVERLAY_Z = 200f
