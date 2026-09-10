package de.miraculixx.worlds.client.ui

import com.mojang.blaze3d.systems.RenderSystem
import de.miraculixx.common.client.ui.SUBTEXT_COLOR
import de.miraculixx.worlds.data.MapEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.navigation.CommonInputs
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.Util
import net.minecraft.sounds.SoundEvents
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private const val ROW_HEIGHT = 36
private const val ICON_SIZE = ROW_HEIGHT - 4

private val JOIN_SPRITE = ResourceLocation.withDefaultNamespace("world_list/join")
private val JOIN_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace("world_list/join_highlighted")

private const val ICON_HOVER_OVERLAY = -1601138544

/** What vanilla's own world list treats as a double click */
private const val DOUBLE_CLICK_MS = 250L

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
        setSelected(row)
        centerScrollOn(row)
        return true
    }

    /**
     * Set selection also by direct movement (tab, controller) for better interaction
     */
    override fun setSelected(entry: MapRow?) {
        val changed = selected !== entry
        super.setSelected(entry)
        if (changed && entry != null) onSelect(entry.entry)
    }

    override fun getRowWidth(): Int = width - 12

    /** 1.21's own setter takes no x, and the list is placed by the screen, not centered */
    fun place(width: Int, height: Int, x: Int, y: Int) {
        updateSizeAndPosition(width, height, y)
        setX(x)
    }

    override fun getScrollbarPosition(): Int = x + width - 8

    /**
     * Detects end of list and requests more entries (infinity scroll).
     *
     * 1.21 positions rows arithmetically off the index, so the `getNextY` / `contentHeight`
     * overrides the later versions need against their O(n²) `addEntry` (nothing i can do?)
     */
    fun nearBottom(px: Int = ROW_HEIGHT * 3): Boolean =
        maxScroll <= 0 || scrollAmount >= maxScroll - px

    inner class MapRow(val entry: MapEntry) : Entry<MapRow>() {
        private var contentX = 0
        private var contentY = 0
        private var contentWidth = 0
        private var contentHeight = 0
        private val contentRight get() = contentX + contentWidth
        private val contentBottom get() = contentY + contentHeight

        /** 1.21 detects no double click for a list entry, so the row times its own */
        private var lastClickMs = 0L

        override fun getNarration(): Component = Component.literal(entry.title)

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            this@MapListWidget.setSelected(this)
            val now = Util.getMillis()
            val doubleClick = now - lastClickMs < DOUBLE_CLICK_MS
            lastClickMs = now
            if (canPlay() && (doubleClick || overIcon(mouseX.toInt(), mouseY.toInt()))) activate()
            return true
        }

        /**
         * Enter on the focused row joins it. Navigating to a row already selects it
         */
        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
            if (!CommonInputs.selected(keyCode) || !canPlay()) return false
            activate()
            return true
        }

        private fun activate() {
            minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
            onActivate(entry)
        }

        /** Only installed worlds can be entered */
        private fun canPlay(): Boolean = entry.installedFolder != null

        private fun overIcon(mouseX: Int, mouseY: Int): Boolean =
            mouseX >= contentX && mouseX < contentX + ICON_SIZE &&
                mouseY >= contentY && mouseY < contentY + ICON_SIZE

        /** 1.21 hands the row its geometry per frame instead of exposing a content rectangle */
        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float,
        ) {
            contentX = left
            contentY = top
            contentWidth = width
            contentHeight = height
            renderContent(graphics, mouseX, mouseY, hovered, partialTick)
        }

        private fun renderContent(
            graphics: GuiGraphics,
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
                // 1.21's blit does not enable blending itself, as vanilla's own world list shows
                RenderSystem.enableBlend()
                graphics.blit(
                    icon.id, x, y, iconSize, iconSize,
                    0f, 0f, icon.width, icon.height, icon.width, icon.height,
                )
                RenderSystem.disableBlend()
            } else {
                graphics.fill(x, y, x + iconSize, y + iconSize, 0xFF2A2A2A.toInt())
            }

            if (hovered && canPlay()) {
                graphics.fill(x, y, x + iconSize, y + iconSize, ICON_HOVER_OVERLAY)
                val sprite = if (overIcon(mouseX, mouseY)) JOIN_HIGHLIGHTED_SPRITE else JOIN_SPRITE
                RenderSystem.enableBlend()
                graphics.blitSprite(sprite, x, y, iconSize, iconSize)
                RenderSystem.disableBlend()
            }

            val font = minecraft.font
            val textX = x + iconSize + 6
            // Main category as a colored pill right of the title (ModMenu-style tag)
            val category = entry.categories.firstOrNull()
            val categoryW = if (category != null) CategoryBadge.width(font, category) + 4 else 0
            val updateW = if (entry.updateAvailable) CategoryBadge.updateWidth(font) + 4 else 0
            val title = trim(entry.title, right - textX - categoryW - updateW, font)
            graphics.drawString(font, title, textX, y + 1, -1)
            var pillX = textX + font.width(title) + 4
            if (category != null && pillX + categoryW - 4 <= right) {
                pillX += CategoryBadge.draw(graphics, font, category, pillX, y + 1) + 4
            }
            if (entry.updateAvailable && pillX + updateW - 4 <= right) {
                CategoryBadge.drawUpdate(graphics, font, pillX, y + 1)
            }
            // Same three-row rhythm as vanilla's world list: name, then two gray detail rows.
            graphics.drawString(font, trim(entry.description, right - textX, font), textX, y + 12, SUBTEXT_COLOR)
            graphics.drawString(font, trim(infoLine(), right - textX, font), textX, y + 21, SUBTEXT_COLOR)
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
