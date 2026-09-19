package de.miraculixx.common.client.ui

import net.minecraft.client.Minecraft
import org.lwjgl.sdl.SDLDialog
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLProperties
import org.lwjgl.sdl.SDL_DialogFileCallback
import org.lwjgl.sdl.SDL_DialogFileFilter
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The native pickers, SDLs since 26.3 (tinyfd went with GLFW)
 */
object NativeDialogs {
    private val log = LoggerFactory.getLogger("Native Dialogs")

    fun pickFolder(title: String, start: Path?, onPicked: (Path?) -> Unit) =
        show(SDLDialog.SDL_FILEDIALOG_OPENFOLDER, title, start, null, onPicked)

    /** @param pattern `png`, or `png;jpg` */
    fun pickFile(title: String, start: Path?, filterName: String, pattern: String, onPicked: (Path?) -> Unit) =
        show(SDLDialog.SDL_FILEDIALOG_OPENFILE, title, start, filterName to pattern, onPicked)

    private fun show(type: Int, title: String, start: Path?, filter: Pair<String, String>?, onPicked: (Path?) -> Unit) {
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            val buffers = mutableListOf<ByteBuffer>()
            fun utf8(text: String): ByteBuffer = MemoryUtil.memUTF8(text, true).also { buffers += it }

            val filters = filter?.let { SDL_DialogFileFilter.malloc().set(utf8(it.first), utf8(it.second)) }
            var callback: SDL_DialogFileCallback? = null
            callback = SDL_DialogFileCallback.create { _, filelist, _ ->
                // A null list is the dialog failing, an empty one the user closing it
                if (filelist == 0L) log.warn("The {} dialog could not be shown: {}", title, SDLError.SDL_GetError())
                val picked = if (filelist == 0L) null else MemoryUtil.memUTF8Safe(MemoryUtil.memGetAddress(filelist))
                minecraft.execute {
                    callback?.free()
                    filters?.free()
                    buffers.forEach(MemoryUtil::memFree)
                    onPicked(picked?.let { runCatching { Path.of(it) }.getOrNull() })
                }
            }

            val props = SDLProperties.SDL_CreateProperties()
            SDLProperties.SDL_SetStringProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title)
            SDLProperties.SDL_SetPointerProperty(
                props, SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER, minecraft.window.handle(),
            )
            // A location that is not there yet makes the portal refuse the dialog
            start?.takeIf { Files.isDirectory(it) || Files.isDirectory(it.parent ?: it) }?.let {
                SDLProperties.SDL_SetStringProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING, it.toString())
            }
            filters?.let {
                SDLProperties.SDL_SetPointerProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER, it.address())
                SDLProperties.SDL_SetNumberProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, 1)
            }
            SDLError.SDL_ClearError()
            SDLDialog.SDL_ShowFileDialogWithProperties(type, callback, 0L, props)
            SDLProperties.SDL_DestroyProperties(props)
        }
    }
}
