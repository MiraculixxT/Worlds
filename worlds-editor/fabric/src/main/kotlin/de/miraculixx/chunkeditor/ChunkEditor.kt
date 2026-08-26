package de.miraculixx.chunkeditor

import de.miraculixx.chunkeditor.client.ui.ChunkMapScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * Quick access for other mods.
 *
 * NOTE: World can **NOT** be loaded at the same time
 */
object ChunkEditor {
    fun open(parent: Screen, access: LevelStorageSource.LevelStorageAccess) {
        Minecraft.getInstance().gui.setScreen(ChunkMapScreen(parent, access))
    }
}
