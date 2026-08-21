package de.miraculixx.worlds.client.ui.panorama

import com.mojang.blaze3d.platform.NativeImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.client.renderer.texture.CubeMapTexture
import net.minecraft.client.renderer.texture.MipmapStrategy
import net.minecraft.client.renderer.texture.TextureContents
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager

/**
 * A cube map read from a world's own `panorama/` folder instead of from a resource pack.
 * Same format like vanilla (panorama_x.png [0-5])
 */
class WorldPanoramaTexture(id: Identifier, private val dir: Path) : CubeMapTexture(id) {
    override fun loadContents(resourceManager: ResourceManager): TextureContents = readContents(dir)

    companion object {
        private val FACE_ORDER = intArrayOf(1, 3, 5, 4, 0, 2)
        const val FOLDER = "panorama"

        fun facePath(dir: Path, face: Int): Path = dir.resolve("panorama_$face.png")

        fun isComplete(dir: Path): Boolean =
            Files.isDirectory(dir) && (0..5).all { Files.isRegularFile(facePath(dir, it)) }

        /**
         * Read the six faces into one stacked image
         */
        fun readContents(dir: Path): TextureContents {
            val first = readFace(dir, FACE_ORDER[0])
            var stacked: NativeImage? = null
            try {
                val width = first.width
                val height = first.height
                stacked = NativeImage(width, height * 6, false)
                first.copyRect(stacked, 0, 0, 0, 0, width, height, false, true)
                for (i in 1 until 6) {
                    val face = readFace(dir, FACE_ORDER[i])
                    face.use { face ->
                        if (face.width != width || face.height != height) {
                            throw IOException(
                                "Panorama faces in $dir differ in size: face ${FACE_ORDER[0]} is ${width}x$height, " +
                                        "face ${FACE_ORDER[i]} is ${face.width}x${face.height}"
                            )
                        }
                        face.copyRect(stacked, 0, 0, 0, i * height, width, height, false, true)
                    }
                }
                val contents = TextureContents(stacked, TextureMetadataSection(true, false, MipmapStrategy.MEAN, 0f))
                stacked = null // handed over to the contents, which closes it after upload
                return contents
            } finally {
                first.close()
                stacked?.close()
            }
        }

        private fun readFace(dir: Path, face: Int): NativeImage =
            Files.newInputStream(facePath(dir, face)).use { NativeImage.read(it) }
    }
}
