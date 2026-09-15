package de.miraculixx.chunkeditor

import net.fabricmc.api.ModInitializer

fun initChunkEditor() {
    Constants.LOG.info("Chunk Editor loaded")
}

class ChunkEditorMod : ModInitializer {
    override fun onInitialize() {
        initChunkEditor()
    }
}
