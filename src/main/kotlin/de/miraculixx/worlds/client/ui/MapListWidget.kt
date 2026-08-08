package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.data.MapEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private const val ROW_HEIGHT = 36
private const val ICON_SIZE = ROW_HEIGHT - 4

private val JOIN_SPRITE = Identifier.withDefaultNamespace("world_list/join")
private val JOIN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("world_list/join_highlighted")

private const val ICON_HOVER_OVERLAY = -1601138544
private const val SUBTEXT_COLOR = -8355712

/** Left-hand scrollable list of maps (ModMenu-style rows: icon + title + short description). */
class MapListWidget(
    minecraft: Minecraft,
    width: Int,
    height: Int,
    y: Int,
    private val onSelect: (MapEntry) -> Unit,
    /** Double-click, or a click straight on the play overlay */
    private val onActivate: (MapEntry) -> Unit = {},
) : ObjectSelectionList<MapListWidget.MapRow>(minecraft, width, height, y, ROW_HEIGHT) {

    fun setEntries(entries: List<MapEntry>) {
        replaceEntries(entries.map { MapRow(it) })
    }

    /** Select and scroll to the first row whose entry matches [predicate]; fires onSelect. */
    fun selectEntry(predicate: (MapEntry) -> Boolean): Boolean {
        val row = children().firstOrNull { predicate(it.entry) } ?: return false
        selected = row
        scrollToEntry(row)
        onSelect(row.entry)
        return true
    }

    override fun getRowWidth(): Int = width - 12

    override fun scrollBarX(): Int = x + width - 8

    /**
     * Manually flat calc the height to avoid nextEntry rescanning each entry every time
     */
    override fun getNextY(): Int = y + 2 - scrollAmount().toInt() + children().size * ROW_HEIGHT

    override fun contentHeight(): Int = children().size * ROW_HEIGHT + 4

    /**
     * Detects end of list and requests more entries (infinity scroll)
     */
    fun nearBottom(px: Int = ROW_HEIGHT * 3): Boolean =
        maxScrollAmount() <= 0 || scrollAmount() >= maxScrollAmount() - px

    inner class MapRow(val entry: MapEntry) : Entry<MapRow>() {
        override fun getNarration(): Component = Component.literal(entry.title)

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            this@MapListWidget.setSelected(this)
            onSelect(entry)
            if (canPlay() && (doubleClick || overIcon(event.x().toInt(), event.y().toInt()))) {
                minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                onActivate(entry)
            }
            return true
        }

        /** Only installed worlds can be entered */
        private fun canPlay(): Boolean = entry.installedFolder != null

        private fun overIcon(mouseX: Int, mouseY: Int): Boolean =
            mouseX >= contentX && mouseX < contentX + ICON_SIZE &&
                mouseY >= contentY && mouseY < contentY + ICON_SIZE

        override fun extractContent(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            partialTick: Float,
        ) {
            val x = contentX
            val y = contentY
            val right = contentRight
            val bottom = contentBottom // row height minus the inter-entry margin
            val selected = this@MapListWidget.selected === this
            if (selected) {
                graphics.fill(x - 2, y - 2, right + 2, bottom + 2, 0xA0FFFFFF.toInt())
                graphics.fill(x - 1, y - 1, right + 1, bottom + 1, 0xFF101010.toInt())
            } else if (hovered) {
                graphics.fill(x - 2, y - 2, right + 2, bottom + 2, 0x40FFFFFF)
            }

            val iconSize = ICON_SIZE
            val icon = MapTextures.get(entry.iconUrl)
            if (icon != null) {
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED, icon.id, x, y, 0f, 0f,
                    iconSize, iconSize, icon.width, icon.height, icon.width, icon.height,
                )
            } else {
                graphics.fill(x, y, x + iconSize, y + iconSize, 0xFF2A2A2A.toInt())
            }

            if (hovered && canPlay()) {
                graphics.fill(x, y, x + iconSize, y + iconSize, ICON_HOVER_OVERLAY)
                val sprite = if (overIcon(mouseX, mouseY)) JOIN_HIGHLIGHTED_SPRITE else JOIN_SPRITE
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, iconSize, iconSize)
            }

            val font = minecraft.font
            val textX = x + iconSize + 6
            // Main category as a colored pill right of the title (ModMenu-style tag)
            val category = entry.categories.firstOrNull()
            val categoryW = if (category != null) CategoryBadge.width(font, category) + 4 else 0
            val updateW = if (entry.updateAvailable) CategoryBadge.updateWidth(font) + 4 else 0
            val title = trim(entry.title, right - textX - categoryW - updateW, font)
            graphics.text(font, title, textX, y + 1, -1)
            var pillX = textX + font.width(title) + 4
            if (category != null && pillX + categoryW - 4 <= right) {
                pillX += CategoryBadge.draw(graphics, font, category, pillX, y + 1) + 4
            }
            if (entry.updateAvailable && pillX + updateW - 4 <= right) {
                CategoryBadge.drawUpdate(graphics, font, pillX, y + 1)
            }
            // Same three-row rhythm as vanilla's world list: name, then two gray detail rows.
            graphics.text(font, trim(entry.description, right - textX, font), textX, y + 12, SUBTEXT_COLOR)
            graphics.text(font, trim(infoLine(), right - textX, font), textX, y + 21, SUBTEXT_COLOR)
        }

        private fun infoLine(): String {
            val version = entry.displayVersion ?: "?"
            val tail = if (canPlay()) "${I18n.get("worlds.last_played")}: ${lastPlayed(entry.dateEpoch)}"
            else "${I18n.get("worlds.downloads")}: ${downloads(entry.downloads)}"
            return "${I18n.get("worlds.version")}: $version | $tail"
        }

        /** Localized short date, matching the vanilla world list. 0 means the world was never opened. */
        private fun lastPlayed(epochMillis: Long): String {
            if (epochMillis <= 0L) return I18n.get("worlds.never")
            return WorldSelectionList.DATE_FORMAT.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            )
        }

        /** Compact count, the way the numbers are shown on the listing sites themselves. */
        private fun downloads(count: Long): String = when {
            count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
            count >= 1_000 -> "%.1fK".format(count / 1_000.0)
            else -> count.toString()
        }

        private fun trim(text: String, maxWidth: Int, font: net.minecraft.client.gui.Font): String {
            if (font.width(text) <= maxWidth) return text
            var s = text
            while (s.isNotEmpty() && font.width("$s…") > maxWidth) s = s.dropLast(1)
            return "$s…"
        }
    }
}
