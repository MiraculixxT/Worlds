package de.miraculixx.common.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

private const val ICON = 16

/**
 * A button carrying a centered 16x16 sprite instead of a label
 */
class IconButton(
    x: Int, y: Int, width: Int, height: Int,
    message: Component,
    private val sprite: () -> Identifier,
    private val onPress: Runnable,
) : AbstractButton(x, y, width, height, message) {

    constructor(x: Int, y: Int, size: Int, message: Component, sprite: Identifier, onPress: Runnable) :
            this(x, y, size, size, message, { sprite }, onPress)

    override fun onPress(input: InputWithModifiers) = onPress.run()

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractDefaultSprite(graphics)
        graphics.blitSprite(
            RenderPipelines.GUI_TEXTURED, sprite(),
            x + (width - ICON) / 2, y + (height - ICON) / 2, ICON, ICON,
        )
    }
}
