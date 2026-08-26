package de.miraculixx.chunkeditor.neoforge

import de.miraculixx.chunkeditor.client.initChunkEditorClient
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod

@Mod(value = "chunkeditor", dist = [Dist.CLIENT])
object ChunkEditorNeoForge {
    init {
        initChunkEditorClient()
    }
}
