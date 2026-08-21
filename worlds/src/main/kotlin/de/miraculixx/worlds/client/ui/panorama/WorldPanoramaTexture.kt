package de.miraculixx.worlds.client.ui.panorama

import com.mojang.blaze3d.platform.NativeImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

        /**
         * Auto captures land at "<world>/panorama/screenshots" (vanilla format)
         */
        const val CAPTURE_FOLDER = "screenshots"

        fun manualDir(saveDir: Path): Path = saveDir.resolve(FOLDER)
        fun captureDir(saveDir: Path): Path = manualDir(saveDir).resolve(CAPTURE_FOLDER)

        fun facePath(dir: Path, face: Int): Path = dir.resolve("panorama_$face.png")

        fun isComplete(dir: Path): Boolean =
            Files.isDirectory(dir) && (0..5).all { Files.isRegularFile(facePath(dir, it)) }

        /** "panorama/" > "panorama/screenshots" > null */
        fun resolve(saveDir: Path): Path? {
            val manual = manualDir(saveDir)
            if (isComplete(manual)) return manual
            return captureDir(saveDir).takeIf(::isComplete)
        }

        /** Blocking, one face after another (only for pack reloading) */
        fun readContents(dir: Path): TextureContents = stack(dir) { image ->
            for (layer in 1 until 6) readFace(dir, FACE_ORDER[layer]).use { copyFace(it, image, layer) }
        }

        /**
         * [readContents] but async for high quality panoramas
         */
        suspend fun readContentsParallel(dir: Path): TextureContents = withContext(Dispatchers.IO) {
            stack(dir) { image ->
                coroutineScope {
                    (1 until 6).map { layer ->
                        async { readFace(dir, FACE_ORDER[layer]).use { copyFace(it, image, layer) } }
                    }.awaitAll()
                }
            }
        }

        /**
         * Read face 0 before anything else, rest comes lazy via [fillRest]
         */
        private inline fun stack(dir: Path, fillRest: (image: NativeImage) -> Unit): TextureContents {
            var stacked: NativeImage? = null
            try {
                val image = readFace(dir, FACE_ORDER[0]).use { first ->
                    val target = NativeImage(first.width, first.height * 6, false)
                    stacked = target
                    copyFace(first, target, 0)
                    target
                }
                fillRest(image)
                val contents = TextureContents(image, TextureMetadataSection(true, false, MipmapStrategy.MEAN, 0f))
                stacked = null // handed over to the contents, which closes it after upload
                return contents
            } finally {
                stacked?.close()
            }
        }

        /**
         * One face into layer [layer] of [stacked], flipped vertically the way vanilla's
         * `copyRect(…, swapY = true)` does it
         */
        private fun copyFace(face: NativeImage, stacked: NativeImage, layer: Int) {
            val width = face.width
            val height = face.height
            if (width != stacked.width || height * 6 != stacked.height) {
                throw IOException(
                    "Panorama faces differ in size: expected ${stacked.width}x${stacked.height / 6}, got ${width}x$height"
                )
            }
            if (face.format() != NativeImage.Format.RGBA || stacked.format() != NativeImage.Format.RGBA) {
                face.copyRect(stacked, 0, 0, 0, layer * height, width, height, false, true)
                return
            }
            val source = face.pixelBytes
            val target = stacked.pixelBytes
            val rowBytes = width * 4
            for (y in 0 until height) {
                target.put((layer * height + (height - 1 - y)) * rowBytes, source, y * rowBytes, rowBytes)
            }
        }

        private fun readFace(dir: Path, face: Int): NativeImage =
            Files.newInputStream(facePath(dir, face)).use { NativeImage.read(it) }
    }
}
