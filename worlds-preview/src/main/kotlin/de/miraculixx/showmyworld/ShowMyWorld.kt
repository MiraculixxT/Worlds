package de.miraculixx.showmyworld

import de.miraculixx.showmyworld.client.ui.PreviewSettingsScreen
import de.miraculixx.showmyworld.client.ui.panorama.WorldPanorama
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Public API to allow direct overrides & modifications of the panorama and settings
 */
object ShowMyWorld {
    /**
     * Show [saveFolder]'s panorama, `null` for none
     *
     * Fades, so calling it per selection change is the intended use. A screen showing a
     * selection must clear it again in `removed()`
     */
    fun select(saveFolder: String?) = WorldPanorama.select(saveFolder)

    /** The mod's settings screen, returning to [parent] on close. */
    fun settingsScreen(parent: Screen): Screen = PreviewSettingsScreen(parent)

    fun openSettings(parent: Screen) {
        Minecraft.getInstance().gui.setScreen(settingsScreen(parent))
    }
}
