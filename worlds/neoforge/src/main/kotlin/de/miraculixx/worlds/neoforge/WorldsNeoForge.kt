package de.miraculixx.worlds.neoforge

import de.miraculixx.worlds.client.initWorldsClient
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod

@Mod(value = "worlds", dist = [Dist.CLIENT])
object WorldsNeoForge {
    init {
        initWorldsClient()
    }
}
