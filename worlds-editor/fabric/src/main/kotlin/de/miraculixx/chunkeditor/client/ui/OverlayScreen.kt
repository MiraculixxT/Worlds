package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.data.ChunkOverlay
import de.miraculixx.chunkeditor.data.OverlaySettings
import de.miraculixx.common.client.ui.HOVER_COLOR
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.clickSound
import de.miraculixx.common.client.ui.drawBox
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

private const val PANEL_W = 320
private const val ROW_H = 16
private const val PAD = 10
private const val MARK_W = 12
private const val FIELD_H = 20
private const val FIELD_GAP = 6
private const val SELECTED_COLOR = 0x4033B5E5

/**
 * Picks & modifies the overlay the map draws
 */
internal class OverlayScreen(
    private val parent: Screen,
    initial: OverlaySettings,
    private val onApply: (OverlaySettings) -> Unit,
) : Screen(Component.translatable("chunkeditor.overlay.title")) {

    private val options = ChunkOverlay.entries
    private var selected = initial.overlay
    private var path = initial.path
    private var block = initial.block
    private var min = initial.min
    private var max = initial.max

    private var panelTop = 0
    private var panelBottom = 0
    private var rowsTop = 0

    override fun init() {
        val left = width / 2 - PANEL_W / 2
        val fieldW = PANEL_W - 2 * PAD
        val half = (fieldW - FIELD_GAP) / 2

        val wantsPath = selected?.needsPath == true
        val wantsBlock = selected?.needsBlock == true
        val wantsRange = selected?.categorical == false
        val rows = (if (wantsPath || wantsBlock) 1 else 0) + (if (wantsRange) 1 else 0) + 1
        val body = 26 + options.size * ROW_H + 8 + rows * (FIELD_H + FIELD_GAP) - FIELD_GAP + PAD

        panelTop = (height - body) / 2
        rowsTop = panelTop + 26
        var y = rowsTop + options.size * ROW_H + 8

        addRenderableWidget(
            guideButton(left + PANEL_W - PAD - GUIDE_SIZE, panelTop + 6, "overlays", "Tip: Categories only scans once")
        )

        if (wantsPath) {
            y += field(left + PAD, y, fieldW, "chunkeditor.overlay.path", "chunkeditor.overlay.path_hint", path) {
                path = it
            }
        }
        if (wantsBlock) {
            y += field(left + PAD, y, fieldW, "chunkeditor.overlay.block", "chunkeditor.overlay.block_hint", block) {
                block = it
            }
        }
        if (wantsRange) {
            field(left + PAD, y, half, "chunkeditor.overlay.min", "chunkeditor.overlay.min", min?.toString() ?: "") {
                min = it.trim().toLongOrNull()
            }
            y += field(
                left + PAD + half + FIELD_GAP, y, half,
                "chunkeditor.overlay.max", "chunkeditor.overlay.max", max?.toString() ?: "",
            ) { max = it.trim().toLongOrNull() }
        }

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { apply() }.bounds(left + PAD, y, half, FIELD_H).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(left + PAD + half + FIELD_GAP, y, half, FIELD_H).build()
        )
        panelBottom = y + FIELD_H + PAD
    }

    /** @return how far down the next row starts */
    private fun field(
        x: Int, y: Int, boxWidth: Int, labelKey: String, hintKey: String, value: String,
        onChange: (String) -> Unit,
    ): Int {
        val box = addRenderableWidget(EditBox(font, x, y, boxWidth, FIELD_H, Component.translatable(labelKey)))
        box.setHint(Component.translatable(hintKey).withColor(SUBTEXT_COLOR))
        box.value = value
        box.setResponder(onChange)
        return FIELD_H + FIELD_GAP
    }

    private fun apply() {
        onApply(OverlaySettings(selected, path.trim(), block.trim(), min, max))
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        drawBox(graphics, width / 2 - PANEL_W / 2, panelTop, width / 2 + PANEL_W / 2, panelBottom)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val left = width / 2 - PANEL_W / 2
        val right = width / 2 + PANEL_W / 2
        graphics.text(font, title.copy().withStyle { it.withBold(true) }, left + PAD, panelTop + 9, -1)

        options.forEachIndexed { index, option ->
            val top = rowsTop + index * ROW_H
            val active = option == selected
            if (active) graphics.fill(left + 1, top, right - 1, top + ROW_H, SELECTED_COLOR)
            else if (mouseY >= top && mouseY < top + ROW_H && mouseX >= left && mouseX < right) {
                graphics.fill(left + 1, top, right - 1, top + ROW_H, HOVER_COLOR)
            }
            val textY = top + (ROW_H - font.lineHeight) / 2 + 1
            graphics.text(font, if (active) "☒" else "☐", left + PAD, textY, -1)
            graphics.text(font, option.label, left + PAD + MARK_W, textY, -1)
            val cost = I18n.get(option.metric.source.labelKey)
            graphics.text(font, cost, right - PAD - font.width(cost), textY, SUBTEXT_COLOR)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val left = width / 2 - PANEL_W / 2
        val right = width / 2 + PANEL_W / 2
        if (event.x() >= left && event.x() < right) {
            val index = ((event.y() - rowsTop) / ROW_H).toInt()
            if (event.y() >= rowsTop && index in options.indices) {
                val picked = options[index]
                selected = if (selected == picked) null else picked
                clickSound()
                // Which fields exist changes with the pick
                rebuildWidgets()
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun onClose() = minecraft.gui.setScreen(parent)
}
