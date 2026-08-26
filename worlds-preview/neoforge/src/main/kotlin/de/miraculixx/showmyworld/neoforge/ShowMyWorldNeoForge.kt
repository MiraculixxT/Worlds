package de.miraculixx.showmyworld.neoforge

import de.miraculixx.showmyworld.client.initShowMyWorldClient
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod

@Mod(value = "showmyworld", dist = [Dist.CLIENT])
object ShowMyWorldNeoForge {
    init {
        initShowMyWorldClient()
    }
}
