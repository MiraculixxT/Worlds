package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.data.ChunkyTask
import de.miraculixx.chunkeditor.data.MAX_PREVIEW_CHUNKS
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.drawBox
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

internal enum class PregenMode {
    FIT, EXACT;

    val label: Component get() = Component.translatable("chunkeditor.pregen.mode.${name.lowercase()}")
}

/**
 * What the map worked out before the screen opened
 */
internal class PregenPlan(
    val dimensionName: String,
    val selection: Int,
    val exactChunks: Long,
    val exactTask: ChunkyTask,
    val fitTask: ChunkyTask,
    val fitChunks: Long,
    val fitAdded: Long,
    /** Translation key naming why nothing can run, `null` when it can */
    val unavailable: String?,
)

/**
 * Hands the selection to Chunky, as the shape it nearly is or as the list it really is. Preview pours
 * the fit back into the selection, so opening this again finds a shape that adds nothing.
 */
internal class PregenScreen(
    private val parent: Screen,
    private val plan: PregenPlan,
    private val onPreview: (ChunkyTask) -> Unit,
    private val onGenerate: (ChunkyTask, Boolean) -> Unit,
) : Screen(Component.translatable("chunkeditor.pregen.title")) {

    private val panelW = 300
    private var panelTop = 0
    private var panelBottom = 0

    private var mode = PregenMode.FIT
    private lateinit var previewButton: Button
    private lateinit var generateButton: Button

    private val chunks: Long get() = if (mode == PregenMode.FIT) plan.fitChunks else plan.exactChunks

    override fun init() {
        val left = width / 2 - panelW / 2 + 10
        var y = height / 2 - 50
        panelTop = y - 26

        addRenderableWidget(
            CycleButton.builder({ it.label }, mode)
                .withValues(PregenMode.entries)
                .create(left, y, panelW - 20, 20, Component.translatable("chunkeditor.pregen.mode")) { _, value ->
                    mode = value
                    refresh()
                },
        )
        y += 26 + 3 * font.lineHeight + 8
        panelBottom = y + 32

        previewButton = addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.pregen.preview")) { preview() }
                .bounds(left, y, 90, 20).build(),
        )
        generateButton = addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.pregen.generate")) { generate() }
                .bounds(left + 94, y, 90, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + panelW / 2 - 100, y, 90, 20).build(),
        )
        refresh()
    }

    /** A fit that adds nothing has nothing to show */
    private fun refresh() {
        previewButton.active = mode == PregenMode.FIT &&
            plan.fitAdded > 0 && plan.fitTask.covered <= MAX_PREVIEW_CHUNKS
        generateButton.active = plan.unavailable == null && chunks > 0
    }

    private fun preview() {
        onPreview(plan.fitTask)
        onClose()
    }

    private fun generate() {
        if (mode == PregenMode.FIT) onGenerate(plan.fitTask, false) else onGenerate(plan.exactTask, true)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        drawBox(graphics, width / 2 - panelW / 2, panelTop, width / 2 + panelW / 2, panelBottom)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val left = width / 2 - panelW / 2 + 10
        graphics.text(
            font, Component.translatable("chunkeditor.pregen.title").withStyle { it.withBold(true) },
            left, panelTop + 9, -1,
        )
        graphics.text(
            font, Component.literal(plan.dimensionName),
            width / 2 + panelW / 2 - 10 - font.width(plan.dimensionName), panelTop + 9, SUBTEXT_COLOR,
        )

        var y = panelTop + 26 + 26
        graphics.text(font, Component.translatable("chunkeditor.pregen.selection", plan.selection), left, y, SUBTEXT_COLOR)
        y += font.lineHeight + 2
        graphics.text(font, Component.translatable("chunkeditor.pregen.chunks", chunks), left, y, -1)
        y += font.lineHeight + 2
        val note = plan.unavailable?.let { Component.translatable(it).withColor(TextColor.RED) }
            ?: when {
                mode == PregenMode.EXACT -> Component.translatable("chunkeditor.pregen.exact_note").withColor(SUBTEXT_COLOR)
                plan.fitAdded == 0L -> Component.translatable(
                    "chunkeditor.pregen.fit_exact", Component.translatable(plan.fitTask.shape.labelKey),
                ).withColor(SUBTEXT_COLOR)

                else -> Component.translatable(
                    "chunkeditor.pregen.fit_added",
                    Component.translatable(plan.fitTask.shape.labelKey), plan.fitAdded,
                ).withColor(SUBTEXT_COLOR)
            }
        graphics.text(font, note, left, y, -1)
    }

    override fun onClose() = minecraft.gui.setScreen(parent)
}
