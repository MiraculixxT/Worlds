package de.miraculixx.common

import java.nio.file.Files
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.Util
import net.minecraft.world.level.storage.LevelStorageSource

object LevelDat {

    /** The `Data` compound, or null when `level.dat` is missing or unreadable */
    fun read(access: LevelStorageSource.LevelStorageAccess): CompoundTag? = try {
        readRoot(access).getCompound(DATA)
    } catch (e: Exception) {
        null
    }

    /**
     * Applies [mutator] to the `Data` compound and writes the file back
     */
    fun modify(access: LevelStorageSource.LevelStorageAccess, mutator: (CompoundTag) -> Unit) {
        val root = readRoot(access)
        val data = root.getCompound(DATA)
        mutator(data)
        root.put(DATA, data)
        write(access, root)
    }

    private fun readRoot(access: LevelStorageSource.LevelStorageAccess): CompoundTag =
        NbtIo.readCompressed(access.levelDirectory.dataFile(), NbtAccounter.unlimitedHeap())

    private fun write(access: LevelStorageSource.LevelStorageAccess, root: CompoundTag) {
        val directory = access.levelDirectory
        val temp = Files.createTempFile(directory.path(), "level", ".dat")
        NbtIo.writeCompressed(root, temp)
        Util.safeReplaceFile(directory.dataFile(), temp, directory.oldDataFile())
    }

    private const val DATA = "Data"
}
