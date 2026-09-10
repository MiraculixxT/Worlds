package de.miraculixx.showmyworld.client.ui.panorama

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.CubeMap
import net.minecraft.resources.ResourceLocation

/**
 * Draws a world's panorama with vanilla's own [CubeMap], which why ever allows blending here?
 * No need for custom behavior, can just use vanillas own.
 */
class WorldCubeMap : AutoCloseable {
    private val maps = HashMap<ResourceLocation, CubeMap>()

    fun render(location: ResourceLocation, rotXInDegrees: Float, rotYInDegrees: Float, alpha: Float) {
        val cube = maps.getOrPut(location) { CubeMap(location) }
        cube.render(Minecraft.getInstance(), rotXInDegrees, rotYInDegrees, alpha)
    }

    override fun close() = maps.clear()
}
