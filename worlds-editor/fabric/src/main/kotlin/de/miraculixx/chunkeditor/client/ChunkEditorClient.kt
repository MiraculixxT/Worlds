package de.miraculixx.chunkeditor.client

import de.miraculixx.chunkeditor.Constants
import net.fabricmc.api.ClientModInitializer

fun initChunkEditorClient() {
    Constants.LOG.debug("Chunk Editor client ready")
}

class ChunkEditorClient : ClientModInitializer {
    override fun onInitializeClient() = initChunkEditorClient()
}
