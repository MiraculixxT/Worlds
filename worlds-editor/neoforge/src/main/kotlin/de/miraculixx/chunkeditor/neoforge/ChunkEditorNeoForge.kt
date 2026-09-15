package de.miraculixx.chunkeditor.neoforge

import de.miraculixx.chunkeditor.initChunkEditor
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment

@Mod("chunkeditor")
object ChunkEditorNeoForge {
    init {
        initChunkEditor()
        if (FMLEnvironment.getDist() == Dist.CLIENT) ChunkEditorNeoForgeClient.init()
    }
}
