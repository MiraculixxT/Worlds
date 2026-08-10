package de.miraculixx.worlds.client

import de.miraculixx.worlds.Constants
import net.fabricmc.api.ClientModInitializer

class WorldsClient : ClientModInitializer {

    override fun onInitializeClient() {
        Constants.LOG.info("Worlds loaded")
        ModUpdate.check()
    }
}
