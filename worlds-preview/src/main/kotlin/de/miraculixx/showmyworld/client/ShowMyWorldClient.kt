package de.miraculixx.showmyworld.client

import de.miraculixx.showmyworld.Constants
import net.fabricmc.api.ClientModInitializer

class ShowMyWorldClient : ClientModInitializer {
    override fun onInitializeClient() {
        Constants.LOG.info("Show My World loaded")
        PreviewConfig.save()
    }
}
