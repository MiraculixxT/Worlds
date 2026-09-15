package de.miraculixx.chunkeditor

import de.miraculixx.chunkeditor.client.ui.ChunkMapScreen
import de.miraculixx.chunkeditor.client.ui.SaveBiomes
import de.miraculixx.chunkeditor.data.LocalBackend
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
        val backend = LocalBackend(access, Minecraft.getInstance().user.profileId) { SaveBiomes.load(access) }
        Minecraft.getInstance().gui.setScreen(ChunkMapScreen(parent, backend))
    }
}
