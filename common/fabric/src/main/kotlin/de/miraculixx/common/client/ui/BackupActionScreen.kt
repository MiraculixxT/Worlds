package de.miraculixx.common.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.TextAlignment
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.MultiLineLabel
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Vanilla's `BackupConfirmScreen` but with act understandable buttons
 */
class BackupActionScreen(
    private val onCancel: Runnable,
    private val onProceed: (backup: Boolean, eraseCache: Boolean) -> Unit,
    title: Component,
    private val description: Component,
    private val backupLabel: Component,
    private val proceedLabel: Component,
    private val promptForCacheErase: Boolean = false,
) : Screen(title) {

    private var message: MultiLineLabel = MultiLineLabel.EMPTY
    private var eraseCache: Checkbox? = null

    override fun init() {
        message = MultiLineLabel.create(font, description, width - 50)
        val offset = (message.lineCount + 1) * LINE_H
        val left = width / 2 - 155

        eraseCache = if (promptForCacheErase) {
            addRenderableWidget(
                Checkbox.builder(Component.translatable("selectWorld.backupEraseCache"), font)
                    .pos(left + 80, 76 + offset).build()
            )
        } else null

        addRenderableWidget(
            Button.builder(backupLabel) { proceed(true) }.bounds(left, 100 + offset, 150, 20).build()
        )
        addRenderableWidget(
            Button.builder(proceedLabel) { proceed(false) }.bounds(left + 160, 100 + offset, 150, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(left + 80, 124 + offset, 150, 20).build()
        )
    }

    private fun proceed(backup: Boolean) = onProceed(backup, eraseCache?.selected() == true)

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, 50, -1)
        message.visitLines(TextAlignment.CENTER, width / 2, 70, LINE_H, graphics.textRenderer())
    }

    override fun onClose() = onCancel.run()

    private companion object {
        const val LINE_H = 9
    }
}
