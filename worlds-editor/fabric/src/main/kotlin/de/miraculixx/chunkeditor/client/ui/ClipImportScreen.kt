package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.ClipImportOptions
import de.miraculixx.chunkeditor.data.ExistingChunks
import de.miraculixx.chunkeditor.data.ClipInfo
import de.miraculixx.common.client.ui.IconButton
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.drawBox
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import java.net.URI

private const val PANEL_W = 320
private const val ROW_H = 24
private const val FIELD_W = 90
private const val HELP_SIZE = 16

private val HELP_SPRITE = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "questionmark")

private const val GUIDE_URL = "https://modrinth.com/mod/mca-selector#import"
private const val GUIDE_LABEL = "Open Guide"
private const val GUIDE_TIP = "Tip: 1 section = 16 blocks"
private const val INVALID_COLOR = 0xFFFF5555.toInt()

/**
 * What to do with the clip that was just placed: which sections to take, how far up, and what happens
 * where the target already holds a chunk.
 */
internal class ClipImportScreen(
    private val parent: Screen,
    private val clip: ClipInfo,
    private val origin: ChunkPos,
    private val onAccept: (ClipImportOptions) -> Unit,
) : Screen(Component.translatable("chunkeditor.clip.options_title")) {

    private var panelTop = 0
    private var panelBottom = 0
    private var rangesValid = true

    private lateinit var yOffsetField: EditBox
    private lateinit var rangesField: EditBox
    private lateinit var existingButton: CycleButton<ExistingChunks>
    private lateinit var acceptButton: Button

    override fun init() {
        val left = width / 2 - PANEL_W / 2 + 10
        val fieldX = width / 2 + PANEL_W / 2 - FIELD_W - 10
        var y = height / 2 - 60
        panelTop = y - 40

        rangesField = addRenderableWidget(EditBox(font, fieldX, y, FIELD_W, 20, Component.empty()))
        rangesField.setResponder { syncValid() }
        y += ROW_H
        yOffsetField = addRenderableWidget(EditBox(font, fieldX, y, FIELD_W, 20, Component.empty()))
        yOffsetField.value = "0"
        y += ROW_H + 4

        existingButton = addRenderableWidget(
            CycleButton.builder<ExistingChunks>(
                { Component.translatable("chunkeditor.clip.existing.${it.name.lowercase()}") },
                ExistingChunks.REPLACE,
            ).withValues(ExistingChunks.entries)
                .displayOnlyValue()
                .create(fieldX, y, FIELD_W, 20, Component.translatable("chunkeditor.clip.existing")) { _, _ -> }
        )
        y += ROW_H + 8

        val help = addRenderableWidget(
            IconButton(
                width / 2 + PANEL_W / 2 - 8 - HELP_SIZE, panelTop + 6, HELP_SIZE,
                Component.literal(GUIDE_LABEL), HELP_SPRITE,
            ) { Util.getPlatform().openUri(URI(GUIDE_URL)) }
        )
        help.drawBackground = false
        help.setTooltip(
            Tooltip.create(
                Component.literal(GUIDE_LABEL)
                    .append("\n")
                    .append(Component.literal(GUIDE_TIP).withStyle(ChatFormatting.GRAY))
            )
        )

        panelBottom = y + 26
        acceptButton = addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.clip.import")) { accept() }
                .bounds(left, y, PANEL_W / 2 - 12, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + 2, y, PANEL_W / 2 - 12, 20).build()
        )
        syncValid()
    }

    private fun options(): ClipImportOptions? {
        val ranges = ClipImportOptions.parseRanges(rangesField.value) ?: return null
        val yOffset = yOffsetField.value.trim().ifEmpty { "0" }.toIntOrNull() ?: return null
        return ClipImportOptions(yOffset, ranges, existingButton.value)
    }

    private fun syncValid() {
        val parsed = options()
        rangesValid = parsed != null
        acceptButton.active = parsed != null
    }

    private fun accept() {
        onAccept(options() ?: return)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        drawBox(graphics, width / 2 - PANEL_W / 2, panelTop, width / 2 + PANEL_W / 2, panelBottom)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val left = width / 2 - PANEL_W / 2 + 10
        graphics.text(font, title.copy().withStyle { it.withBold(true) }, left, panelTop + 9, -1)
        graphics.text(
            font,
            Component.translatable("chunkeditor.clip.options_subtitle", clip.name, origin.x, origin.z),
            left, panelTop + 22, SUBTEXT_COLOR,
        )
        graphics.text(
            font, Component.translatable("chunkeditor.clip.sections"),
            left, rangesField.y + 6, if (rangesValid) -1 else INVALID_COLOR,
        )
        graphics.text(
            font, Component.translatable("chunkeditor.clip.y_offset"),
            left, yOffsetField.y + 6, -1,
        )
        graphics.text(
            font, Component.translatable("chunkeditor.clip.existing"),
            left, existingButton.y + 6, -1,
        )
    }

    override fun onClose() = minecraft.gui.setScreen(parent)
}
