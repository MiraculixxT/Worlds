package de.miraculixx.worlds.client.ui

import com.mojang.blaze3d.platform.NativeImage
import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/** A registered, GPU-ready image plus its pixel dimensions. */
data class LoadedImage(val id: Identifier, val width: Int, val height: Int)

/**
 * Downloads remote PNG/JPEG/WebP images (map icons, readme images), uploads them as dynamic textures,
 * and caches the result by URL. [get] is safe to call every frame: it kicks off a background load
 * on first request and returns null until the texture is ready.
 */
object MapTextures {
    private sealed interface State
    private data object Loading : State
    private data object Failed : State
    private data class Ready(val image: LoadedImage) : State

    private val cache = ConcurrentHashMap<String, State>()

    /** Returns the loaded image for [url], or null while loading / on failure. */
    fun get(url: String?): LoadedImage? {
        if (url.isNullOrBlank()) return null
        when (val state = cache[url]) {
            is Ready -> return state.image
            Loading, Failed -> return null
            null -> {} // fall through to start loading
        }
        cache[url] = Loading
        Constants.SCOPE.launch {
            val bytes = loadBytes(url)
            if (bytes == null) {
                cache[url] = Failed
                return@launch
            }
            // Texture registration must happen on the render thread.
            Minecraft.getInstance().execute {
                cache[url] = try {
                    val image = decode(bytes)
                    val id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "dyn/${hash(url)}")
                    Minecraft.getInstance().textureManager.register(id, DynamicTexture({ url }, image))
                    Ready(LoadedImage(id, image.width, image.height))
                } catch (e: Exception) {
                    Constants.LOG.warn("Image decode failed {}: {}", url, e.message)
                    Failed
                }
            }
        }
        return null
    }

    /**
     * Fetch image bytes for [url]: HTTP(S) URLs go through [Http], anything else is treated as a
     * local filesystem path (an installed save's own `icon.png`).
     */
    private fun loadBytes(url: String): ByteArray? {
        if (url.startsWith("http://") || url.startsWith("https://")) return Http.getBytes(url)
        return try {
            Files.readAllBytes(Path.of(url))
        } catch (e: Exception) {
            Constants.LOG.warn("Local image read failed {}: {}", url, e.message)
            null
        }
    }

    @Volatile private var pluginsScanned = false

    /**
     * Decode image bytes into a [NativeImage]. STB (`NativeImage.read`) handles PNG/JPEG but not
     * WebP, which Modrinth uses for icons — those are routed through ImageIO (TwelveMonkeys WebP
     * plugin) and copied pixel-by-pixel into a NativeImage.
     */
    private fun decode(bytes: ByteArray): NativeImage {
        if (isWebp(bytes)) return readViaImageIO(bytes)
        // STB is strict and rejects some otherwise-valid PNG/JPEG ("bad png sig", odd chunks);
        // fall back to the more lenient ImageIO decoder before giving up.
        return try {
            NativeImage.read(bytes)
        } catch (e: Exception) {
            Constants.LOG.warn("STB decode failed ({}); retrying via ImageIO", e.message)
            readViaImageIO(bytes)
        }
    }

    private fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    /** ImageIO decode with the bundled TwelveMonkeys WebP plugin registered. */
    fun readBuffered(bytes: ByteArray): BufferedImage? {
        if (!pluginsScanned) {
            val prev = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = MapTextures::class.java.classLoader
                ImageIO.scanForPlugins()
            } finally {
                Thread.currentThread().contextClassLoader = prev
            }
            pluginsScanned = true
        }
        return ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    }

    private fun readViaImageIO(bytes: ByteArray): NativeImage {
        val buffered = readBuffered(bytes) ?: throw IOException("No ImageIO reader for WebP")
        val w = buffered.width
        val h = buffered.height
        val image = NativeImage(NativeImage.Format.RGBA, w, h, false)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = buffered.getRGB(x, y) // 0xAARRGGBB
                val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val bl = argb and 0xFF
                image.setPixelABGR(x, y, (a shl 24) or (bl shl 16) or (g shl 8) or r) // 0xAABBGGRR
            }
        }
        return image
    }

    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
