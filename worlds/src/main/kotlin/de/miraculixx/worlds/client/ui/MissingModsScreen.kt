package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.data.MapRequirement
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

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
) : Screen(Component.translatable("worlds.missing_mods.title")) {

    override fun init() {
        val rowW = 300
        val rowX = width / 2 - rowW / 2
        val dlW = 90
        var y = listTop()

        for (req in missing) {
            val requirement = req
            addRenderableWidget(
                Button.builder(Component.translatable("mco.brokenworld.download")) { openUrl(downloadUrl(requirement)) }
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
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(bx, by, btnW, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("worlds.missing_mods.ignore")) {
                onJoin()
            }.bounds(bx + btnW + gap, by, btnW, 20).build()
        )
    }

    /** First row y — below the two-line header. */
    private fun listTop(): Int = 56

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(
            font, Component.translatable("worlds.missing_mods.title").withStyle { it.withBold(true) },
            width / 2, 18, 0xFFFF6B6B.toInt(),
        )
        graphics.centeredText(
            font, Component.translatable("worlds.missing_mods.subtitle", mapTitle),
            width / 2, 34, 0xFFB0B0B0.toInt(),
        )

        val rowW = 300
        val rowX = width / 2 - rowW / 2
        var y = listTop()
        for (req in missing) {
            graphics.text(font, "• ${req.name}", rowX, y + 6, -1)
            req.modId?.let { graphics.text(font, it, rowX + 12, y + 6 + font.lineHeight + 1, 0xFF808080.toInt()) }
            y += 24
        }
    }

    private fun downloadUrl(req: MapRequirement): String? =
        req.link?.takeIf { it.isNotBlank() }
            ?: req.download?.takeIf { it.isNotBlank() }
            ?: req.projectId?.let { "https://modrinth.com/mod/$it" }

    private fun openUrl(url: String?) = Links.open(url)

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }
}
