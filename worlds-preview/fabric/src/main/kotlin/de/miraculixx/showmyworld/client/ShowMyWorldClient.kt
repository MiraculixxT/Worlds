package de.miraculixx.showmyworld.client

import de.miraculixx.showmyworld.Constants
import net.fabricmc.api.ClientModInitializer

fun initShowMyWorldClient() {
    Constants.LOG.info("Show My World loaded")
    PreviewConfig.save()
}

class ShowMyWorldClient : ClientModInitializer {
    override fun onInitializeClient() = initShowMyWorldClient()
}
