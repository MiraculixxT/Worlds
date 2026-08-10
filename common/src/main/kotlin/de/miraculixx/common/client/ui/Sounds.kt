package de.miraculixx.common.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * The click a `Button` plays for free. Hand hit-tested regions are not widgets, so they have to
 * play it themselves.
 */
fun clickSound() {
    Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
}
