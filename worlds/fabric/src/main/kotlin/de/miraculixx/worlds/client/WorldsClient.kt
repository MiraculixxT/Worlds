package de.miraculixx.worlds.client

import de.miraculixx.worlds.Constants
import net.fabricmc.api.ClientModInitializer

fun initWorldsClient() {
    Constants.LOG.info("Worlds loaded")
    WorldsConfig.save()
    ModUpdate.check()
}

class WorldsClient : ClientModInitializer {

    override fun onInitializeClient() = initWorldsClient()
}
