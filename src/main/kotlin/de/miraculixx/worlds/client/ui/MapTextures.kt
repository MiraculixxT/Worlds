package de.miraculixx.worlds.client.ui

import com.mojang.blaze3d.platform.NativeImage
import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/** A registered, GPU-ready image plus its pixel dimensions. */
data class LoadedImage(val id: Identifier, val width: Int, val height: Int)

/**
 * Downloads remote PNG/JPEG images (map icons, readme images), uploads them as dynamic textures,
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
            val bytes = Http.getBytes(url)
            if (bytes == null) {
                cache[url] = Failed
                return@launch
            }
            // Texture registration must happen on the render thread.
            Minecraft.getInstance().execute {
                cache[url] = try {
                    val image = NativeImage.read(bytes)
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

    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
