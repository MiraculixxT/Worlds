package de.miraculixx.common.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

private const val ICON = 16

/**
 * A button carrying a centered 16x16 sprite instead of a label
 */
class IconButton(
    x: Int, y: Int, width: Int, height: Int,
    message: Component,
    private val sprite: () -> ResourceLocation,
    private val onPress: Runnable,
) : AbstractButton(x, y, width, height, message) {

    constructor(x: Int, y: Int, size: Int, message: Component, sprite: ResourceLocation, onPress: Runnable) :
            this(x, y, size, size, message, { sprite }, onPress)

    /** Only the sprite icon */
    var drawBackground = true

    override fun onPress() = onPress.run()

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    /** The message is narration only, so the label vanilla would draw is suppressed */
    override fun renderString(graphics: GuiGraphics, font: Font, color: Int) = Unit

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (drawBackground) super.renderWidget(graphics, mouseX, mouseY, partialTick)
        graphics.blitSprite(sprite(), x + (width - ICON) / 2, y + (height - ICON) / 2, ICON, ICON)
    }
}
