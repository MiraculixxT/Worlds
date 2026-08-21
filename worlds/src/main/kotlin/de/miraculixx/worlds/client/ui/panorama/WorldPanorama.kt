package de.miraculixx.worlds.client.ui.panorama

import de.miraculixx.worlds.Constants
import java.nio.file.Path
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.util.Util

/**
 * Worlds can provide their own preview under "panorama/panaorama_x.png" like vanilla resource packs.
 * Only drawn aboth vanilla when available via custom shader.
 */
object WorldPanorama {
    private val TEXTURE_ID = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "panorama/selected")
    private const val FADE_MS = 250L
    /** Longer than this between draws and the fade is resumed from scratch instead of continued */
    private const val STALE_MS = 1_000L

    private var target: Path? = null
    private var shown: Path? = null
    private var loading: Path? = null
    private val failed = HashSet<Path>()
    private var textureReady = false
    private var loadGen = 0
    private var alpha = 0f
    private var lastMs = 0L
    private var cubeMap: WorldCubeMap? = null

    fun select(saveFolder: String?) {
        target = saveFolder?.let { folder ->
            Minecraft.getInstance().gameDirectory.toPath()
                .resolve("saves").resolve(folder).resolve(WorldPanoramaTexture.FOLDER)
                .takeIf(WorldPanoramaTexture::isComplete)
        }
    }

    /** Called from `GuiRendererMixin`, drew after vanillas */
    fun render(rotXInDegrees: Float, rotYInDegrees: Float) {
        val now = Util.getMillis()
        val elapsed = now - lastMs
        lastMs = now
        // Abort animation that can not be rendered
        if (elapsed > STALE_MS) {
            drop()
            alpha = 0f
        }
        val step = elapsed.coerceIn(0, FADE_MS) / FADE_MS.toFloat()

        if (shown != target) {
            // Nothing on screen to fade out (never loaded, or already gone)
            alpha -= step
            if (alpha <= 0f || !textureReady) {
                alpha = 0f
                drop()
                shown = target
            }
        }
        val dir = shown ?: return
        if (!textureReady) {
            ensureLoaded(dir)
            return
        }
        if (shown == target) alpha = (alpha + step).coerceAtMost(1f)
        if (alpha <= 0f) return
        val cube = cubeMap ?: WorldCubeMap(TEXTURE_ID).also { cubeMap = it }
        cube.render(rotXInDegrees, rotYInDegrees, alpha)
    }

    /** Six PNG decodes: off the render thread, uploaded on it. */
    private fun ensureLoaded(dir: Path) {
        if (loading == dir || dir in failed) return
        loading = dir
        val gen = ++loadGen
        Constants.SCOPE.launch {
            val contents = try {
                WorldPanoramaTexture.readContents(dir)
            } catch (e: Exception) {
                Constants.LOG.warn("Could not read panorama {}: {}", dir, e.message)
                Minecraft.getInstance().execute {
                    failed.add(dir)
                    if (loadGen == gen) loading = null
                }
                return@launch
            }
            Minecraft.getInstance().execute {
                if (loadGen != gen || shown != dir) {
                    contents.close()
                    return@execute
                }
                val texture = WorldPanoramaTexture(TEXTURE_ID, dir)
                Minecraft.getInstance().textureManager.register(TEXTURE_ID, texture)
                texture.apply(contents) // uploads the cube map and closes the image
                textureReady = true
                loading = null
            }
        }
    }

    private fun drop() {
        if (textureReady) Minecraft.getInstance().textureManager.release(TEXTURE_ID)
        textureReady = false
        shown = null
        loading = null
        loadGen++
    }
}
