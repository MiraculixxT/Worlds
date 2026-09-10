package de.miraculixx.showmyworld.client.ui.panorama

import com.mojang.blaze3d.platform.NativeImage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation

/**
 * A cube map read from a world's own `panorama/` folder instead of from a resource pack.
 * Same format like vanilla (panorama_x.png [0-5])
 *
 * 1.21 has no `CubeMapTexture`, they bind each face manually.
 */
object WorldPanoramaTexture {
    const val FOLDER = "panorama"

    /**
     * Auto captures land at "<world>/panorama/screenshots" (vanilla format)
     */
    const val CAPTURE_FOLDER = "screenshots"

    private const val FACES = 6

    fun manualDir(saveDir: Path): Path = saveDir.resolve(FOLDER)
    fun captureDir(saveDir: Path): Path = manualDir(saveDir).resolve(CAPTURE_FOLDER)

    fun facePath(dir: Path, face: Int): Path = dir.resolve("panorama_$face.png")

    fun isComplete(dir: Path): Boolean =
        Files.isDirectory(dir) && (0 until FACES).all { Files.isRegularFile(facePath(dir, it)) }

    /** "panorama/" > "panorama/screenshots" > null */
    fun resolve(saveDir: Path): Path? {
        val manual = manualDir(saveDir)
        if (isComplete(manual)) return manual
        return captureDir(saveDir).takeIf(::isComplete)
    }

    /** The id [net.minecraft.client.renderer.CubeMap] looks a face up under */
    fun faceId(base: ResourceLocation, face: Int): ResourceLocation = base.withPath("${base.path}_$face.png")

    /** Six PNG decodes, in parallel */
    suspend fun readFaces(dir: Path): List<NativeImage> = withContext(Dispatchers.IO) {
        val images = coroutineScope {
            (0 until FACES).map { face -> async { runCatching { readFace(dir, face) } } }.awaitAll()
        }
        if (images.any { it.isFailure }) {
            images.mapNotNull { it.getOrNull() }.forEach { it.close() }
            throw images.first { it.isFailure }.exceptionOrNull()!!
        }
        images.map { it.getOrThrow() }
    }

    /** Render thread: hands every face to the texture manager, which owns them from then on */
    fun register(base: ResourceLocation, images: List<NativeImage>) {
        val manager = Minecraft.getInstance().textureManager
        images.forEachIndexed { face, image -> manager.register(faceId(base, face), DynamicTexture(image)) }
    }

    fun release(base: ResourceLocation) {
        val manager = Minecraft.getInstance().textureManager
        (0 until FACES).forEach { manager.release(faceId(base, it)) }
    }

    private fun readFace(dir: Path, face: Int): NativeImage =
        Files.newInputStream(facePath(dir, face)).use { NativeImage.read(it) }
}
