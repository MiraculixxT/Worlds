package de.miraculixx.common

import net.fabricmc.loader.api.FabricLoader
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

object Loader {
    enum class Kind(val id: String) {
        /** The id both platforms use in their own metadata, and what Modrinth calls them. */
        FABRIC("fabric"), NEOFORGE("neoforge")
    }

    private interface Impl {
        val configDir: Path
        fun isModLoaded(id: String): Boolean
        fun modVersion(id: String): String?
    }

    val kind: Kind = if (present("net.fabricmc.loader.api.FabricLoader")) Kind.FABRIC else Kind.NEOFORGE

    private val impl: Impl = if (kind == Kind.FABRIC) FabricImpl() else NeoForgeImpl()

    /** `<gamedir>/config`. */
    val configDir: Path get() = impl.configDir

    fun isModLoaded(id: String): Boolean = impl.isModLoaded(id)

    /** Version of [id] as the loader reports it, or `null` when it is not installed. */
    fun modVersion(id: String): String? = impl.modVersion(id)

    private fun present(name: String) =
        runCatching { Class.forName(name, false, Loader::class.java.classLoader) }.isSuccess

    private class FabricImpl : Impl {
        override val configDir: Path get() = FabricLoader.getInstance().configDir
        override fun isModLoaded(id: String) = FabricLoader.getInstance().isModLoaded(id)
        override fun modVersion(id: String): String? = FabricLoader.getInstance().getModContainer(id)
            .map { it.metadata.version.friendlyString }.orElse(null)
    }

    private class NeoForgeImpl : Impl {
        override val configDir: Path get() = FMLPaths.CONFIGDIR.get()
        override fun isModLoaded(id: String) = ModList.get().isLoaded(id)
        override fun modVersion(id: String): String? = ModList.get().getModContainerById(id)
            .map { it.modInfo.version.toString() }.orElse(null)
    }
}
