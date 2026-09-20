package de.miraculixx.chunkeditor

import de.miraculixx.chunkeditor.fabric.FabricNet
import net.fabricmc.api.ModInitializer

fun initChunkEditor() {
    Constants.LOG.info("Chunk Editor loaded")
}

class ChunkEditorMod : ModInitializer {
    override fun onInitialize() {
        initChunkEditor()
        FabricNet.register()
    }
}
