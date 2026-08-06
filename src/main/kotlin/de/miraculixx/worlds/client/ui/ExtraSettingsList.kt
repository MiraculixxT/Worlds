package de.miraculixx.worlds.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

private const val ROW_H = 24
private const val CATEGORY_H = 20
private const val INDENT = 10
private const val WIDGET_H = 20
private const val FIELD_GAP = 3

internal const val WIDGET_W = 63
internal const val FIELD_W = (WIDGET_W - FIELD_GAP) / 2

private const val SUBTEXT_COLOR = -8355712
private const val VALID_COLOR = -2039584
private const val INVALID_COLOR = -65536

/** A collapsible block of settings, or a link to a screen */
class ExtraCategory(val label: String, val action: (() -> Unit)? = null) {
    var expanded = false
}


class ExtraSettingsList(
    minecraft: Minecraft,
    private val categories: List<ExtraCategory>,
    private val rowsFor: (ExtraCategory) -> List<Row>,
) : ContainerObjectSelectionList<ExtraSettingsList.Row>(minecraft, 0, 0, 0, ROW_H) {

    private var pendingRebuild = false

    /** Collapsing from inside a click would drop the entry the click is still being dispatched to. */
    fun requestRebuild() {
        pendingRebuild = true
    }

    fun rebuild() {
        pendingRebuild = false
        val scroll = scrollAmount()
        commitEdits()
        clearEntries()
        categories.forEach { category ->
            addEntry(CategoryRow(category), CATEGORY_H)
            if (category.action == null && category.expanded) rowsFor(category).forEach { addEntry(it, ROW_H) }
        }
        setScrollAmount(scroll)
    }

    /** Write whatever is typed into the rows' edit boxes */
    fun commitEdits() = children().forEach { it.commit() }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float,
    ) {
        if (pendingRebuild) rebuild()
        super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun getRowWidth(): Int = width - 12

    override fun scrollBarX(): Int = x + width - 8

    override fun extractListBackground(graphics: GuiGraphicsExtractor) =
        drawBox(graphics, x, y, x + width, y + height)

    override fun extractListSeparators(graphics: GuiGraphicsExtractor) = Unit

    private fun clickSound() =
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))

    abstract inner class Row(private val label: String, private val indent: Int = 0) : Entry<Row>() {
        protected open fun widgets(): List<AbstractWidget> = emptyList()

        override fun children(): List<GuiEventListener> = widgets()

        override fun narratables(): List<NarratableEntry> = widgets()

        open fun commit() = Unit

        protected fun labelY() = contentY + (contentHeight - minecraft.font.lineHeight) / 2

        protected fun widgetY() = contentY + (contentHeight - WIDGET_H) / 2

        protected fun drawLabel(graphics: GuiGraphicsExtractor, color: Int = -1) =
            graphics.text(minecraft.font, label, contentX + indent, labelY(), color)

        protected fun extractWidgets(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) =
            widgets().forEach { it.extractRenderState(graphics, mouseX, mouseY, partialTick) }
    }

    inner class CategoryRow(private val category: ExtraCategory) : Row(category.label, INDENT) {
        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            clickSound()
            val action = category.action
            if (action != null) action() else {
                category.expanded = !category.expanded
                requestRebuild()
            }
            return true
        }

        override fun extractContent(
            graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            if (hovered) graphics.fill(contentX - 2, contentY, contentRight + 2, contentBottom, 0x30FFFFFF)
            val arrow = when {
                category.action != null -> "›"
                category.expanded -> "▼"
                else -> "▶"
            }
            graphics.text(minecraft.font, arrow, contentX, labelY(), SUBTEXT_COLOR)
            drawLabel(graphics)
            graphics.fill(contentX, contentBottom - 1, contentRight, contentBottom, 0xFF505050.toInt())
        }
    }

    /** A read-only value with an optional button beside it. */
    inner class TextRow(
        label: String,
        private val value: String,
        buttonLabel: String = "Copy",
        onPress: (() -> Unit)?,
    ) : Row(label, INDENT) {
        private val button = onPress?.let {
            Button.builder(Component.literal(buttonLabel)) { _ -> it() }.bounds(0, 0, WIDGET_W, WIDGET_H).build()
        }

        override fun widgets(): List<AbstractWidget> = listOfNotNull(button)

        override fun extractContent(
            graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            button?.setX(contentRight - button.width)
            button?.setY(widgetY())
            val valueRight = contentRight - (button?.let { it.width + 4 } ?: 0)
            val font = minecraft.font
            graphics.text(font, value, valueRight - font.width(value), labelY(), SUBTEXT_COLOR)
            extractWidgets(graphics, mouseX, mouseY, partialTick)
        }
    }

    inner class ToggleRow(
        label: String,
        initial: Boolean,
        onSet: (Boolean) -> Unit,
    ) : Row(label, INDENT) {
        private val button = CycleButton.onOffBuilder(initial).displayOnlyValue()
            .create(0, 0, WIDGET_W, WIDGET_H, Component.literal(label)) { _, value -> onSet(value) }

        override fun widgets(): List<AbstractWidget> = listOf(button)

        override fun extractContent(
            graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            button.setX(contentRight - button.width)
            button.setY(widgetY())
            extractWidgets(graphics, mouseX, mouseY, partialTick)
        }
    }

    inner class CycleRow<T : Any>(
        label: String,
        values: List<T>,
        initial: T,
        name: (T) -> Component,
        onSet: (T) -> Unit,
    ) : Row(label, INDENT) {
        private val button = CycleButton.builder(name, initial).withValues(values).displayOnlyValue()
            .create(0, 0, WIDGET_W, WIDGET_H, Component.literal(label)) { _, value -> onSet(value) }

        override fun widgets(): List<AbstractWidget> = listOf(button)

        override fun extractContent(
            graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            button.x = contentRight - button.width
            button.y = widgetY()
            extractWidgets(graphics, mouseX, mouseY, partialTick)
        }
    }

    /** One or more numeric fields, written on Enter, on focus loss and whenever the list is rebuilt. */
    inner class NumberRow(label: String, private val fields: List<NumberField>) : Row(label, INDENT) {
        override fun widgets(): List<AbstractWidget> = fields.map { it.box }

        override fun commit() = fields.forEach { it.commit() }

        override fun setFocused(listener: GuiEventListener?) {
            if (focused != null && focused !== listener) commit()
            super.setFocused(listener)
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (event.isConfirmation && focused != null) {
                commit()
                return true
            }
            return super.keyPressed(event)
        }

        override fun extractContent(
            graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            var x = contentRight
            fields.asReversed().forEach { field ->
                x -= field.box.width
                field.box.setX(x)
                field.box.setY(widgetY())
                x -= FIELD_GAP
            }
            extractWidgets(graphics, mouseX, mouseY, partialTick)
        }
    }
}

/**
 * A single numeric edit box.
 */
class NumberField(
    font: Font,
    width: Int,
    initial: Long,
    private val range: LongRange,
    private val apply: (Long) -> Unit,
) {
    val box = EditBox(font, 0, 0, width, WIDGET_H, Component.empty())
    private var value = initial

    init {
        box.setMaxLength(12)
        box.value = initial.toString()
        box.setResponder { text -> box.setTextColor(if (parse(text) == null) INVALID_COLOR else VALID_COLOR) }
    }

    private fun parse(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it in range }

    fun commit() {
        val parsed = parse(box.value)
        if (parsed == null) {
            box.value = value.toString()
            return
        }
        if (parsed == value) return
        value = parsed
        apply(parsed)
    }
}
