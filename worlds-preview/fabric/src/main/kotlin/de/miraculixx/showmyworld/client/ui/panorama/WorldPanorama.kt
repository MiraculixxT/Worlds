package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import de.miraculixx.showmyworld.client.PreviewConfig
import java.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.util.Util

/**
 * Worlds can provide their own preview under "panorama/panaorama_x.png" like vanilla resource packs.
 * Servers get the same folder in the config folder, see [PanoramaRoots].
 * Only drawn aboth vanilla when available via custom shader.
 */
object WorldPanorama {
    /** Longer than this between draws and the fade is resumed from scratch instead of continued */
    private const val STALE_MS = 1_000L
    private val fadeMs: Long get() = PreviewConfig.settings.fade.coerceAtLeast(1)

    /**
     * Two texture slots, allowing cross-fades
     */
    private val SLOT_IDS = listOf(
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "panorama/slot_a"),
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "panorama/slot_b"),
    )

    private class Layer(val dir: Path, val textureId: Identifier) {
        var alpha = 0f
        var ready = false
        var loading = false

        fun fade(step: Float) { alpha = (alpha + step).coerceIn(0f, 1f) }
    }

    private var selectedRoot: Path? = null
    private var sessionRandom: Path? = null
    private var defaultMode: DefaultPanorama? = null
    private var defaultPick: Path? = null
    private var defaultDone = false
    private var library: List<PanoramaCandidate>? = null
    private var libraryJob: Job? = null
    private var target: Path? = null
    private var base: Layer? = null // what's the main on screen
    private var incoming: Layer? = null // fading layer
    private val failed = HashSet<Path>()
    private var loadGen = 0
    private var lastMs = 0L
    private var cubeMap: WorldCubeMap? = null

    /** A save folder under `saves/` */
    fun select(saveFolder: String?) = selectRoot(saveFolder?.let(PanoramaRoots::world))

    /** A server address in the config folder */
    fun selectServer(address: String?) = selectRoot(address?.let(PanoramaRoots::server))

    private fun selectRoot(root: Path?) {
        selectedRoot = root
        refresh()
    }

    /** Re-reads the settings for the current selection, so toggling `show` takes effect at once. */
    fun refresh() {
        val settings = PreviewConfig.settings
        val root = selectedRoot?.takeIf { settings.show }
        if (root != null) {
            target = WorldPanoramaTexture.resolve(root)
            return
        }
        val mode = if (settings.show) settings.default else DefaultPanorama.VANILLA
        if (defaultMode != mode) resetDefault()
        defaultMode = mode
        target = defaultTarget(mode)
    }

    fun invalidateLibrary() {
        library = null
        resetDefault()
    }

    /**
     * The pick is cached until the mode changes or the library is rescanned
     */
    private fun defaultTarget(mode: DefaultPanorama): Path? {
        if (defaultDone) return defaultPick
        if (mode == DefaultPanorama.VANILLA) {
            defaultDone = true
            return null
        }
        val candidates = library ?: run {
            loadLibrary()
            return null
        }
        val available = candidates.filter { it.dir !in failed }
        defaultPick = when (mode) {
            DefaultPanorama.RANDOM -> sessionRandom?.takeIf { pick -> available.any { it.dir == pick } }
                ?: mode.pick(available).also { sessionRandom = it }
            else -> mode.pick(available)
        }
        defaultDone = true
        return defaultPick
    }

    private fun resetDefault() {
        defaultDone = false
        defaultPick = null
    }

    private fun loadLibrary() {
        if (libraryJob?.isActive == true) return
        libraryJob = Constants.SCOPE.launch {
            val found = DefaultPanorama.scan()
            Minecraft.getInstance().execute {
                library = found
                refresh()
            }
        }
    }

    /** Called from `GuiRendererMixin`, drew after vanillas */
    fun render(rotXInDegrees: Float, rotYInDegrees: Float) {
        // Nothing calls select() before the title screen's first frame, so the default resolves here
        if (selectedRoot == null && !defaultDone) refresh()
        val now = Util.getMillis()
        val elapsed = now - lastMs
        lastMs = now
        // Abort animation that can not be rendered
        if (elapsed > STALE_MS) reset()
        val fade = fadeMs
        val step = elapsed.coerceIn(0, fade) / fade.toFloat()
        // A folder that failed to load counts as none, or the outgoing panorama would stay up.
        val want = target?.takeIf { it !in failed }

        when (want) {
            null -> {}
            base?.dir -> dropIncoming() // back to what is already up, mid-switch
            incoming?.dir -> {}
            else -> {
                dropIncoming()
                incoming = Layer(want, SLOT_IDS.first { it != base?.textureId })
            }
        }

        val current = base
        val next = incoming
        when {
            want == null -> {
                current?.fade(-step)
                next?.fade(-step)
                if ((current?.alpha ?: 0f) <= 0f && (next?.alpha ?: 0f) <= 0f) {
                    reset()
                    return
                }
            }
            next != null -> {
                current?.fade(step) // hold the outgoing one underneath while the new one loads
                if (!next.ready) load(next)
                else {
                    next.fade(step)
                    if (next.alpha >= 1f) promote(next)
                }
            }
            current != null -> current.fade(step)
        }

        val cube = cubeMap ?: WorldCubeMap().also { cubeMap = it }
        base?.let { if (it.ready && it.alpha > 0f) cube.render(it.textureId, rotXInDegrees, rotYInDegrees, it.alpha) }
        incoming?.let { if (it.ready && it.alpha > 0f) cube.render(it.textureId, rotXInDegrees, rotYInDegrees, it.alpha) }
    }

    fun invalidate(root: Path) {
        failed.remove(WorldPanoramaTexture.manualDir(root))
        failed.remove(WorldPanoramaTexture.captureDir(root))
        invalidateLibrary()
    }

    /** Six PNG decodes: off the render thread, uploaded on it. */
    private fun load(layer: Layer) {
        if (layer.loading) return
        layer.loading = true
        val gen = ++loadGen
        Constants.SCOPE.launch {
            val contents = try {
                WorldPanoramaTexture.readContentsParallel(layer.dir)
            } catch (e: Exception) {
                Constants.LOG.warn("Could not read panorama {}: {}", layer.dir, e.message)
                Minecraft.getInstance().execute {
                    failed.add(layer.dir)
                    layer.loading = false
                    if (layer.dir == defaultPick) resetDefault()
                }
                return@launch
            }
            Minecraft.getInstance().execute {
                if (loadGen != gen) {
                    contents.close()
                    return@execute
                }
                val texture = WorldPanoramaTexture(layer.textureId, layer.dir)
                Minecraft.getInstance().textureManager.register(layer.textureId, texture)
                texture.apply(contents) // uploads the cube map and closes the image
                layer.ready = true
                layer.loading = false
            }
        }
    }

    private fun promote(layer: Layer) {
        release(base)
        layer.alpha = 1f
        base = layer
        incoming = null
    }

    private fun dropIncoming() {
        release(incoming)
        incoming = null
        loadGen++ // whatever was being read for it is no longer wanted
    }

    private fun reset() {
        if (base == null && incoming == null) return // nothing to do = dont attempt render
        release(base)
        release(incoming)
        base = null
        incoming = null
        loadGen++
    }

    private fun release(layer: Layer?) {
        if (layer != null && layer.ready) Minecraft.getInstance().textureManager.release(layer.textureId)
    }
}
