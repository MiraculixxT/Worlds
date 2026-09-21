package de.miraculixx.common.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ComponentPath
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.navigation.CommonInputs
import net.minecraft.client.gui.navigation.FocusNavigationEvent
import net.minecraft.network.chat.Component

/** 1.21 lists carry one item height for every entry, so categories are as tall as rows */
private const val ROW_H = 24
private const val INDENT = 10
private const val WIDGET_H = 20
private const val FIELD_GAP = 3

const val WIDGET_W = 63
const val FIELD_W = (WIDGET_W - FIELD_GAP) / 2

private const val VALID_COLOR = -2039584
private const val INVALID_COLOR = -65536

/** A collapsible block of settings or redirect */
class SettingsCategory(val label: String, val action: (() -> Unit)? = null) {
    var expanded = false
}

/**
 * A list of collapsible setting categories, drawn in [drawBox]'s frame
 */
class SettingsList(
    minecraft: Minecraft,
    private val categories: List<SettingsCategory>,
    private val rowsFor: (SettingsCategory) -> List<Row>,
) : ContainerObjectSelectionList<SettingsList.Row>(minecraft, 0, 0, 0, ROW_H) {

    private var pendingRebuild = false

    fun requestRebuild() {
        pendingRebuild = true
    }

    fun rebuild() {
        pendingRebuild = false
        val scroll = scrollAmount
        // Expanding is a rebuild, so the row that was just activated is thrown away
        val refocus = (focused as? CategoryRow)?.category
        commitEdits()
        clearEntries()
        categories.forEach { category ->
            addEntry(CategoryRow(category))
            if (category.action == null && category.expanded) rowsFor(category).forEach { addEntry(it) }
        }
        if (refocus != null) {
            children().filterIsInstance<CategoryRow>().firstOrNull { it.category === refocus }
                ?.let { row -> row.focusPath()?.let { ComponentPath.path(this, it)?.applyFocus(true) } }
        }
        setScrollAmount(scroll)
    }

    /** Write whatever is typed into the rows' edit boxes */
    fun commitEdits() = children().forEach { it.commit() }

    override fun renderWidget(
        graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float,
    ) {
        if (pendingRebuild) rebuild()
        super.renderWidget(graphics, mouseX, mouseY, partialTick)
    }

    override fun nextFocusPath(event: FocusNavigationEvent): ComponentPath? =
        if (isActive) super.nextFocusPath(event) else null

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean =
        isActive && super.mouseClicked(mouseX, mouseY, button)

    /** 1.21's own setter takes no x, and the list is placed by the screen, not centered */
    fun place(width: Int, height: Int, x: Int, y: Int) {
        updateSizeAndPosition(width, height, y)
        setX(x)
    }

    override fun getRowWidth(): Int = width - 12

    override fun getScrollbarPosition(): Int = x + width - 8

    override fun renderListBackground(graphics: GuiGraphics) =
        drawBox(graphics, x, y, x + width, y + height)

    override fun renderListSeparators(graphics: GuiGraphics) = Unit

    abstract inner class Row(private val label: String, private val indent: Int = 0) : Entry<Row>() {
        protected var contentX = 0
        protected var contentY = 0
        protected var contentWidth = 0
        protected var contentHeight = 0
        protected val contentRight get() = contentX + contentWidth
        protected val contentBottom get() = contentY + contentHeight

        /** 1.21 hands the row its geometry per frame, every row below is written against that rectangle */
        final override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            contentX = left
            contentY = top
            contentWidth = width
            contentHeight = height
            renderContent(graphics, mouseX, mouseY, hovered, partialTick)
        }

        protected abstract fun renderContent(
            graphics: GuiGraphics, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        )

        protected open fun widgets(): List<AbstractWidget> = emptyList()

        override fun children(): List<GuiEventListener> = widgets()

        override fun narratables(): List<NarratableEntry> = widgets()

        open fun commit() = Unit

        protected fun labelY() = contentY + (contentHeight - minecraft.font.lineHeight) / 2

        protected fun widgetY() = contentY + (contentHeight - WIDGET_H) / 2

        protected fun drawLabel(graphics: GuiGraphics, color: Int = -1) =
            graphics.drawString(minecraft.font, label, contentX + indent, labelY(), color)

        protected fun renderWidgets(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) =
            widgets().forEach { it.render(graphics, mouseX, mouseY, partialTick) }
    }

    inner class CategoryRow(val category: SettingsCategory) : Row(category.label, INDENT) {
        private val hit = object : AbstractWidget(0, 0, 0, 0, Component.literal(category.label)) {
            override fun renderWidget(
                graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float,
            ) = Unit

            override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

            override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
                if (!CommonInputs.selected(keyCode)) return false
                activate()
                return true
            }
        }

        override fun widgets(): List<AbstractWidget> = listOf(hit)

        internal fun focusPath(): ComponentPath? = ComponentPath.path(this, ComponentPath.leaf(hit))

        private fun activate() {
            clickSound()
            val action = category.action
            if (action != null) action() else {
                category.expanded = !category.expanded
                requestRebuild()
            }
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            setFocused(hit)
            activate()
            return true
        }

        override fun renderContent(
            graphics: GuiGraphics, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            hit.setRectangle(contentWidth, contentHeight, contentX, contentY)
            if (hovered || hit.isFocused) {
                graphics.fill(contentX - 2, contentY, contentRight + 2, contentBottom, HOVER_COLOR)
            }
            val arrow = when {
                category.action != null -> "›"
                category.expanded -> "▼"
                else -> "▶"
            }
            graphics.drawString(minecraft.font, arrow, contentX, labelY(), SUBTEXT_COLOR)
            drawLabel(graphics)
            graphics.fill(contentX, contentBottom - 1, contentRight, contentBottom, 0xFF505050.toInt())
        }
    }

    /** A read-only value with an optional button beside it. */
    inner class TextRow(
        label: String,
        private val value: String,
        buttonLabel: Component = Component.empty(),
        onPress: (() -> Unit)?,
    ) : Row(label, INDENT) {
        private val button = onPress?.let {
            Button.builder(buttonLabel) { _ -> it() }.bounds(0, 0, WIDGET_W, WIDGET_H).build()
        }

        override fun widgets(): List<AbstractWidget> = listOfNotNull(button)

        override fun renderContent(
            graphics: GuiGraphics, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            button?.x = contentRight - button.width
            button?.y = widgetY()
            val valueRight = contentRight - (button?.let { it.width + 4 } ?: 0)
            val font = minecraft.font
            graphics.drawString(font, value, valueRight - font.width(value), labelY(), SUBTEXT_COLOR)
            renderWidgets(graphics, mouseX, mouseY, partialTick)
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

        override fun renderContent(
            graphics: GuiGraphics, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            button.x = contentRight - button.width
            button.y = widgetY()
            renderWidgets(graphics, mouseX, mouseY, partialTick)
        }
    }

    inner class CycleRow<T : Any>(
        label: String,
        values: List<T>,
        initial: T,
        name: (T) -> Component,
        onSet: (T) -> Unit,
    ) : Row(label, INDENT) {
        private val button = CycleButton.builder(name).withValues(values).withInitialValue(initial).displayOnlyValue()
            .create(0, 0, WIDGET_W, WIDGET_H, Component.literal(label)) { _, value -> onSet(value) }

        override fun widgets(): List<AbstractWidget> = listOf(button)

        override fun renderContent(
            graphics: GuiGraphics, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            button.x = contentRight - button.width
            button.y = widgetY()
            renderWidgets(graphics, mouseX, mouseY, partialTick)
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

        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
            if (isEnter(keyCode) && focused != null) {
                commit()
                return true
            }
            return super.keyPressed(keyCode, scanCode, modifiers)
        }

        override fun renderContent(
            graphics: GuiGraphics, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            drawLabel(graphics)
            var x = contentRight
            fields.asReversed().forEach { field ->
                x -= field.box.width
                field.box.setX(x)
                field.box.setY(widgetY())
                x -= FIELD_GAP
            }
            renderWidgets(graphics, mouseX, mouseY, partialTick)
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
