package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.data.MapEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component

private const val ROW_HEIGHT = 36

/** Left-hand scrollable list of maps (ModMenu-style rows: icon + title + short description). */
class MapListWidget(
    minecraft: Minecraft,
    width: Int,
    height: Int,
    y: Int,
    private val onSelect: (MapEntry) -> Unit,
) : ObjectSelectionList<MapListWidget.MapRow>(minecraft, width, height, y, ROW_HEIGHT) {

    fun setEntries(entries: List<MapEntry>) {
        replaceEntries(entries.map { MapRow(it) })
    }

    override fun getRowWidth(): Int = width - 12

    override fun scrollBarX(): Int = x + width - 8

    inner class MapRow(val entry: MapEntry) : ObjectSelectionList.Entry<MapRow>() {
        override fun getNarration(): Component = Component.literal(entry.title)

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            this@MapListWidget.setSelected(this)
            onSelect(entry)
            return true
        }

        override fun extractContent(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            partialTick: Float,
        ) {
            val x = getContentX()
            val y = getContentY()
            val right = getContentRight()
            val selected = this@MapListWidget.selected === this
            if (selected) {
                graphics.fill(x - 2, y - 2, right + 2, y + ROW_HEIGHT - 6, 0xA0FFFFFF.toInt())
                graphics.fill(x - 1, y - 1, right + 1, y + ROW_HEIGHT - 7, 0xFF101010.toInt())
            } else if (hovered) {
                graphics.fill(x - 2, y - 2, right + 2, y + ROW_HEIGHT - 6, 0x40FFFFFF)
            }

            val iconSize = ROW_HEIGHT - 12
            val icon = MapTextures.get(entry.iconUrl)
            if (icon != null) {
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED, icon.id, x, y, 0f, 0f,
                    iconSize, iconSize, icon.width, icon.height, icon.width, icon.height,
                )
            } else {
                graphics.fill(x, y, x + iconSize, y + iconSize, 0xFF2A2A2A.toInt())
            }

            val font = minecraft.font
            val textX = x + iconSize + 6
            graphics.text(font, trim(entry.title, right - textX, font), textX, y + 1, -1)
            graphics.text(font, trim(entry.description, right - textX, font), textX, y + 13, 0xFFA0A0A0.toInt())
            val theme = entry.theme
            if (theme != null) {
                graphics.text(font, trim(theme, right - textX, font), textX, y + 24, 0xFF6699FF.toInt())
            }
        }

        private fun trim(text: String, maxWidth: Int, font: net.minecraft.client.gui.Font): String {
            if (font.width(text) <= maxWidth) return text
            var s = text
            while (s.isNotEmpty() && font.width("$s…") > maxWidth) s = s.dropLast(1)
            return "$s…"
        }
    }
}
