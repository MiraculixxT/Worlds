package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import de.miraculixx.showmyworld.client.PreviewConfig
import java.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.Util

/**
 * Worlds can provide their own preview under "panorama/panaorama_x.png" like vanilla resource packs.
 * Servers get the same folder in the config folder, see [PanoramaRoots].
 * Only drawn above vanilla's own cube map, with vanilla's alpha blend.
 */
object WorldPanorama {
    /** Longer than this between draws and the fade is resumed from scratch instead of continued */
    private const val STALE_MS = 1_000L

    /** A capture is written off-thread, these bound the wait for its files */
    private const val CAPTURE_PROBE_MS = 500L
    private const val CAPTURE_WAIT_MS = 30_000L

    /** File timestamps are not always as exact as the clock */
    private const val FS_TIME_SLACK_MS = 2_000L
    private val fadeMs: Long get() = PreviewConfig.settings.fade.coerceAtLeast(1)

    /**
     * Two texture slots, allowing cross-fades
     */
    private val SLOT_IDS = listOf(
        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "panorama/slot_a"),
        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "panorama/slot_b"),
    )

    private class Layer(val dir: Path, val revision: Int, val textureId: ResourceLocation) {
        var alpha = 0f
        var ready = false
        var loading = false

        fun fade(step: Float) { alpha = (alpha + step).coerceIn(0f, 1f) }
    }

    private var selectedRoot: Path? = null
    private var joining: Path? = null
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
    private var pendingCapture: Path? = null
    private var pendingUntil = 0L
    private var pendingSince = 0L
    private var lastProbe = 0L

    /** Bumped when a folder is rewritten, so a layer on the same path is read again */
    private var revision = 0

    /** A save folder under `saves/` */
    fun select(saveFolder: String?) = selectRoot(saveFolder?.let(PanoramaRoots::world))

    /** A server address in the config folder */
    fun selectServer(address: String?) = selectRoot(address?.let(PanoramaRoots::server))

    /** Joining keeps the world up through the loading screens, whatever the leaving screen clears */
    fun join(root: Path) {
        joining = root
        selectRoot(root)
    }

    /** Back on a screen that owns the selection again */
    fun releaseJoin() {
        joining = null
    }

    private fun selectRoot(root: Path?) {
        if (root == null && joining != null) return
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
        if (mode != DefaultPanorama.RANDOM) { // only a random pick needs the whole library
            defaultPick = mode.pick(emptyList())?.takeIf { it !in failed }
            defaultDone = true
            return defaultPick
        }
        val candidates = library ?: run {
            loadLibrary()
            return null
        }
        val available = candidates.filter { it.dir !in failed }
        defaultPick = sessionRandom?.takeIf { pick -> available.any { it.dir == pick } }
            ?: mode.pick(available).also { sessionRandom = it }
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

    /** A capture just started for [root], its files appear later */
    fun awaitCapture(root: Path) {
        pendingCapture = root
        pendingUntil = Util.getMillis() + CAPTURE_WAIT_MS
        pendingSince = System.currentTimeMillis() - FS_TIME_SLACK_MS
    }

    /**
     * Polls for the capture. A world that captured before still has the old faces on disk, so the
     * files have to be newer than the capture, not merely there.
     */
    private fun probeCapture(now: Long) {
        val root = pendingCapture ?: return
        if (now - lastProbe < CAPTURE_PROBE_MS) return
        lastProbe = now
        if (WorldPanoramaTexture.isCompleteSince(WorldPanoramaTexture.captureDir(root), pendingSince)) {
            pendingCapture = null
            invalidate(root)
        } else if (now > pendingUntil) pendingCapture = null
    }

    /** Called from `GuiRendererMixin`, drew after vanillas */
    fun render(rotXInDegrees: Float, rotYInDegrees: Float) {
        val now = Util.getMillis()
        probeCapture(now)
        // Nothing calls select() before the title screen's first frame, so the default resolves here
        if (selectedRoot == null && !defaultDone) refresh()
        val elapsed = now - lastMs
        lastMs = now
        if (elapsed > STALE_MS && joining == null) reset()
        val fade = fadeMs
        val step = elapsed.coerceIn(0, fade) / fade.toFloat()
        // A folder that failed to load counts as none, or the outgoing panorama would stay up.
        val want = target?.takeIf { it !in failed }

        val shows = { layer: Layer? -> layer != null && layer.dir == want && layer.revision == revision }
        when {
            want == null -> {}
            shows(base) -> dropIncoming() // back to what is already up, mid-switch
            shows(incoming) -> {}
            else -> {
                dropIncoming()
                incoming = Layer(want, revision, SLOT_IDS.first { it != base?.textureId })
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
        val dirs = setOf(WorldPanoramaTexture.manualDir(root), WorldPanoramaTexture.captureDir(root))
        failed.removeAll(dirs)
        // A rewritten capture keeps its path, so only the revision tells the layers apart
        if (dirs.any { it == base?.dir || it == incoming?.dir }) revision++
        invalidateLibrary()
    }

    /** Six PNG decodes: off the render thread, uploaded on it. */
    private fun load(layer: Layer) {
        if (layer.loading) return
        layer.loading = true
        val gen = ++loadGen
        Constants.SCOPE.launch {
            val faces = try {
                WorldPanoramaTexture.readFaces(layer.dir)
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
                    faces.forEach { it.close() }
                    return@execute
                }
                // The texture manager owns the images from here and closes them on release
                WorldPanoramaTexture.register(layer.textureId, faces)
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
        if (layer != null && layer.ready) WorldPanoramaTexture.release(layer.textureId)
    }
}
