package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.client.ui.IconButton
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import java.net.URI

internal const val GUIDE_SIZE = 16

private val GUIDE_SPRITE = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "questionmark")

private const val GUIDE_URL = "https://modrinth.com/mod/mca-selector"
private const val GUIDE_LABEL = "Open Guide"

internal fun guideButton(x: Int, y: Int, anchor: String, tip: String? = null): IconButton {
    val url = "$GUIDE_URL#$anchor"
    val button = IconButton(x, y, GUIDE_SIZE, Component.literal(GUIDE_LABEL), GUIDE_SPRITE) {
        Util.getPlatform().openUri(URI(url))
    }
    button.drawBackground = false
    val label = Component.literal("$GUIDE_LABEL ($anchor)")
    button.setTooltip(
        Tooltip.create(
            if (tip == null) label
            else label.append("\n").append(Component.literal(tip).withStyle(ChatFormatting.GRAY))
        )
    )
    return button
}
