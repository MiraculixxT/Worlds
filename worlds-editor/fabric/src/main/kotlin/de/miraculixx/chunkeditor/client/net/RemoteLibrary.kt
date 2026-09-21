package de.miraculixx.chunkeditor.client.net

import de.miraculixx.chunkeditor.data.ClipEntry
import de.miraculixx.chunkeditor.data.ClipFootprint
import de.miraculixx.chunkeditor.data.ClipLibrary
import de.miraculixx.chunkeditor.data.ParsedSelection
import de.miraculixx.chunkeditor.data.SelectionEntry
import de.miraculixx.chunkeditor.net.C2S
import de.miraculixx.chunkeditor.net.LibraryBodies
import java.nio.file.Path

/**
 * The servers shelf, only names are available, no folder or clip data
 */
object RemoteLibrary : ClipLibrary {

    override val folder: Path? = null

    override val selectionFolder: Path? = null

    override suspend fun clips(): List<ClipEntry> =
        LibraryBodies.readClips(ClientNet.request(C2S.CLIP_LIST, ByteArray(0)))

    override suspend fun selections(): List<SelectionEntry> =
        LibraryBodies.readSelections(ClientNet.request(C2S.SELECTION_LIST, ByteArray(0)))

    override suspend fun footprint(name: String): ClipFootprint? =
        LibraryBodies.readFootprint(ClientNet.request(C2S.CLIP_FOOTPRINT, LibraryBodies.name(name)))

    override suspend fun selection(name: String): ParsedSelection? =
        LibraryBodies.readSelection(ClientNet.request(C2S.SELECTION_READ, LibraryBodies.name(name)))

    override suspend fun deleteClip(name: String): Boolean =
        LibraryBodies.readBoolean(ClientNet.request(C2S.CLIP_DELETE, LibraryBodies.name(name)))

    override suspend fun deleteSelection(name: String): Boolean =
        LibraryBodies.readBoolean(ClientNet.request(C2S.SELECTION_DELETE, LibraryBodies.name(name)))
}
