package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.client.net.RemoteBackend
import de.miraculixx.chunkeditor.client.net.RemoteException
import de.miraculixx.chunkeditor.net.JobQueue
import de.miraculixx.chunkeditor.net.JobSummary
import de.miraculixx.chunkeditor.server.JobKind
import de.miraculixx.common.client.ui.HOVER_COLOR
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.common.client.ui.drawBox
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

private const val MARGIN = 16
private const val LIST_TOP = 40
private const val ROW_H = 26
private const val SELECTED_COLOR = 0x5033B5E5
private const val ERROR_COLOR = 0xFFFF5555.toInt()

/**
 * Allow reviewing & editing queued [de.miraculixx.chunkeditor.server.Job]s
 */
internal class JobQueueScreen(
    private val parent: Screen,
    private val backend: RemoteBackend,
) : Screen(Component.translatable("chunkeditor.jobs.title")) {

    private var queue = JobQueue(emptyList(), false)
    private var loading = true
    private var needsLoad = true
    private var busy = false
    private var error: String? = null
    private var selectedId: String? = null

    private lateinit var list: JobList
    private lateinit var removeButton: Button
    private lateinit var backupToggle: Checkbox

    override fun init() {
        list = addRenderableWidget(JobList(minecraft, width - 2 * MARGIN, listBottom() - LIST_TOP, LIST_TOP))
        list.updateSizeAndPosition(width - 2 * MARGIN, listBottom() - LIST_TOP, MARGIN, LIST_TOP)
        list.fill()

        val y = height - 32
        removeButton = addRenderableWidget(
            Button.builder(Component.translatable("chunkeditor.jobs.remove")) { confirmRemove() }
                .bounds(MARGIN, y, 100, 20).build()
        )
        backupToggle = addRenderableWidget(
            Checkbox.builder(Component.translatable("chunkeditor.jobs.backup"), font)
                .pos(MARGIN + 110, y + 2).selected(queue.backup)
                .onValueChange { _, value -> request { backend.setJobsBackup(value) } }
                .build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width - MARGIN - 80, y, 80, 20).build()
        )
        syncButtons()

        if (needsLoad) {
            needsLoad = false
            request { backend.jobs() }
        }
    }

    private fun listBottom() = height - 40

    /** Every call sends the whole queue */
    private fun request(call: suspend () -> JobQueue) {
        busy = true
        syncButtons()
        Constants.SCOPE.launch {
            val result = runCatching { call() }
            minecraft.execute {
                busy = false
                loading = false
                result.onSuccess {
                    queue = it
                    error = null
                }.onFailure {
                    error = I18n.get((it as? RemoteException)?.key ?: "chunkeditor.remote.error.failed")
                }
                // The checkbox has no setter, a rebuild takes the servers word for it
                rebuildWidgets()
            }
        }
    }

    private fun syncButtons() {
        if (!::backupToggle.isInitialized) return
        removeButton.active = !busy && list.selected != null
        backupToggle.active = !busy && queue.jobs.isNotEmpty()
    }

    private fun confirmRemove() {
        val job = list.selected?.job ?: return
        minecraft.setScreen(
            ConfirmScreen(
                { confirmed ->
                    minecraft.setScreen(this)
                    if (confirmed) request { backend.cancelJob(job.id) }
                },
                Component.translatable("chunkeditor.jobs.remove_title"),
                Component.translatable("chunkeditor.jobs.remove_warning"),
            )
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.text(font, title.copy().withStyle { it.withBold(true) }, MARGIN, 12, -1)
        graphics.text(font, I18n.get("chunkeditor.jobs.hint"), MARGIN, 24, SUBTEXT_COLOR)
        val message = when {
            error != null -> error
            loading -> I18n.get("chunkeditor.map.reading")
            queue.jobs.isEmpty() -> I18n.get("chunkeditor.jobs.empty")
            else -> null
        }
        if (message != null) {
            graphics.text(font, message, MARGIN + 8, LIST_TOP + 10, if (error != null) ERROR_COLOR else SUBTEXT_COLOR)
        }
    }

    override fun onClose() = minecraft.setScreen(parent)

    private fun describe(job: JobSummary): String = when (job.kind) {
        JobKind.DELETE -> I18n.get("chunkeditor.jobs.delete", job.chunks)
        JobKind.PASTE -> I18n.get("chunkeditor.jobs.paste", job.clip, job.originX, job.originZ)
    }

    private fun dimensionLabel(id: String): String =
        backend.dimensions.firstOrNull { it.key.identifier().toString() == id }?.let { I18n.get(it.labelKey) } ?: id

    inner class JobList(minecraft: Minecraft, width: Int, height: Int, y: Int) :
        ObjectSelectionList<JobList.Row>(minecraft, width, height, y, ROW_H) {

        fun fill() {
            val previous = selectedId
            replaceEntries(queue.jobs.mapIndexed { index, job -> Row(index + 1, job) })
            setSelected(children().firstOrNull { it.job.id == previous })
        }

        override fun setSelected(entry: Row?) {
            super.setSelected(entry)
            selectedId = entry?.job?.id
            syncButtons()
        }

        override fun getRowWidth(): Int = width - 12

        override fun scrollBarX(): Int = x + width - 8

        override fun extractListBackground(graphics: GuiGraphicsExtractor) =
            drawBox(graphics, x, y, x + width, y + height)

        override fun extractListSeparators(graphics: GuiGraphicsExtractor) = Unit

        inner class Row(private val position: Int, val job: JobSummary) : Entry<Row>() {

            override fun getNarration(): Component = Component.literal(describe(job))

            override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                this@JobList.setSelected(this)
                return true
            }

            override fun extractContent(
                graphics: GuiGraphicsExtractor,
                mouseX: Int,
                mouseY: Int,
                hovered: Boolean,
                partialTick: Float,
            ) {
                if (this@JobList.selected === this) {
                    graphics.fill(contentX - 2, contentY - 2, contentRight + 2, contentBottom + 2, SELECTED_COLOR)
                } else if (hovered) {
                    graphics.fill(contentX - 2, contentY - 2, contentRight + 2, contentBottom + 2, HOVER_COLOR)
                }
                graphics.text(minecraft.font, "#$position  ${describe(job)}", contentX + 2, contentY + 2, -1)
                val info = I18n.get("chunkeditor.jobs.info", dimensionLabel(job.dimension), job.by, formatDate(job.at))
                graphics.text(minecraft.font, info, contentX + 2, contentY + 13, SUBTEXT_COLOR)
            }
        }
    }
}
