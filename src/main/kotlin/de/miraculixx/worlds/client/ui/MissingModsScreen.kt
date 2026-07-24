package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.data.MapRequirement
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util

/**
 * Shown before joining a map when one or more of its `requiredMods` is not loaded. Lists the missing
 * mods, each with a Download button (opens the mod's page), plus Back and "Ignore & Join" at the
 * bottom. [onJoin] performs the actual world open when the player chooses to proceed anyway.
 */
class MissingModsScreen(
    private val parent: Screen?,
    private val mapTitle: String,
    private val missing: List<MapRequirement>,
    private val onJoin: () -> Unit,
) : Screen(Component.literal("Missing mods")) {

    override fun init() {
        val rowW = 300
        val rowX = width / 2 - rowW / 2
        val dlW = 90
        var y = listTop()

        for (req in missing) {
            val requirement = req
            addRenderableWidget(
                Button.builder(Component.literal("Download")) { openUrl(downloadUrl(requirement)) }
                    .bounds(rowX + rowW - dlW, y, dlW, 20).build()
            )
            y += 24
        }

        val btnW = 150
        val gap = 4
        val totalW = btnW * 2 + gap
        val bx = width / 2 - totalW / 2
        val by = height - 32
        addRenderableWidget(
            Button.builder(Component.literal("Back")) { onClose() }
                .bounds(bx, by, btnW, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Ignore & Join")) {
                onJoin()
            }.bounds(bx + btnW + gap, by, btnW, 20).build()
        )
    }

    /** First row y — below the two-line header. */
    private fun listTop(): Int = 56

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, Component.literal("Missing required mods").withStyle { it.withBold(true) }, width / 2, 18, 0xFFFF6B6B.toInt())
        graphics.centeredText(font, "\"$mapTitle\" needs these mods, which are not installed:", width / 2, 34, 0xFFB0B0B0.toInt())

        val rowW = 300
        val rowX = width / 2 - rowW / 2
        var y = listTop()
        for (req in missing) {
            graphics.text(font, Component.literal("• ${req.name}"), rowX, y + 6, -1)
            req.modId?.let { graphics.text(font, it, rowX + 12, y + 6 + font.lineHeight + 1, 0xFF808080.toInt()) }
            y += 24
        }
    }

    private fun downloadUrl(req: MapRequirement): String? =
        req.link?.takeIf { it.isNotBlank() }
            ?: req.download?.takeIf { it.isNotBlank() }
            ?: req.projectId?.let { "https://modrinth.com/mod/$it" }

    private fun openUrl(url: String?) {
        if (!url.isNullOrBlank()) Util.getPlatform().openUri(url)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }
}
