package de.miraculixx.common.client.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The native pickers, tinyfd's here - blocking, so they run off the render thread
 */
object NativeDialogs {
    private val log = LoggerFactory.getLogger("Native Dialogs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pickFolder(title: String, start: Path?, onPicked: (Path?) -> Unit) =
        show(title, start, onPicked) { where, _ -> TinyFileDialogs.tinyfd_selectFolderDialog(title, where) }

    /** @param pattern `png`, or `png;jpg` */
    fun pickFile(title: String, start: Path?, filterName: String, pattern: String, onPicked: (Path?) -> Unit) =
        show(title, start, onPicked) { where, stack ->
            val patterns = pattern.split(';')
            val filters = stack.mallocPointer(patterns.size)
            patterns.forEach { filters.put(stack.UTF8("*.$it")) }
            filters.flip()
            TinyFileDialogs.tinyfd_openFileDialog(title, where, filters, filterName, false)
        }

    private fun show(
        title: String, start: Path?, onPicked: (Path?) -> Unit, dialog: (String?, MemoryStack) -> String?,
    ) {
        val minecraft = Minecraft.getInstance()
        // tinyfd opens where the trailing separator points, and a folder that is not there yet is refused
        val where = start?.takeIf { Files.isDirectory(it) }?.let { "$it${File.separator}" }
        scope.launch {
            val picked = try {
                MemoryStack.stackPush().use { stack -> dialog(where, stack) }
            } catch (e: Exception) {
                log.warn("The {} dialog could not be shown: {}", title, e.message)
                null
            }
            minecraft.execute { onPicked(picked?.let { runCatching { Path.of(it) }.getOrNull() }) }
        }
    }
}
