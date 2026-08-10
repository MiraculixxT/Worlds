package de.miraculixx.chunkeditor.client

import de.miraculixx.chunkeditor.Constants
import net.fabricmc.api.ClientModInitializer

class ChunkEditorClient : ClientModInitializer {
    override fun onInitializeClient() {
        Constants.LOG.info("Chunk Editor loaded")
    }
}