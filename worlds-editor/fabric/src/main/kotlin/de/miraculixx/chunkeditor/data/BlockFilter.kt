package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.tags.TagKey
import net.minecraft.tags.TagLoader
import net.minecraft.world.level.storage.LevelStorageSource

private val AIR = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

/**
 * Counting: single block, block tag, or everything non-air
 */
class BlockFilter private constructor(private val names: Set<String>?) {

    /** Nothing to count: a tag that resolved to nothing */
    val isEmpty: Boolean get() = names?.isEmpty() == true

    fun matches(name: String): Boolean = names?.contains(name) ?: (name !in AIR)

    companion object {
        /** Blank counts everything but air, `#ns:path` a block tag, anything else one block id */
        fun of(access: LevelStorageSource.LevelStorageAccess?, input: String?): BlockFilter {
            val wanted = input?.trim()?.takeIf { it.isNotEmpty() } ?: return BlockFilter(null)
            if (!wanted.startsWith('#')) return BlockFilter(setOf(namespaced(wanted)))
            val id = ResourceLocation.tryParse(namespaced(wanted.substring(1)))
                ?: return BlockFilter(emptySet())
            return BlockFilter(tagMembers(access, id))
        }

        private fun namespaced(name: String) = if (name.contains(':')) name else "minecraft:$name"

        /**
         * Load world pack storage to resolve block tags
         */
        private fun tagMembers(
            access: LevelStorageSource.LevelStorageAccess?, id: ResourceLocation,
        ): Set<String> = try {
            // A live server already loaded its datapacks
            if (access == null) {
                val set = BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, id)).orElse(null)
                if (set == null) {
                    Constants.LOG.warn("Unknown block tag #{}", id)
                    return emptySet()
                }
                return set.mapNotNullTo(HashSet()) { it.unwrapKey().orElse(null)?.location()?.toString() }
            }
            val repository = ServerPacksSource.createPackRepository(access)
            repository.reload()
            repository.setSelected(repository.availableIds)
            MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected()).use { manager ->
                // 1.21's loader resolves to the element, not to a holder
                val loader = TagLoader(BuiltInRegistries.BLOCK::getOptional, Registries.tagsDirPath(Registries.BLOCK))
                val members = loader.build(loader.load(manager))[id]
                if (members == null) {
                    Constants.LOG.warn("Unknown block tag #{}", id)
                    emptySet()
                } else {
                    members.mapNotNullTo(HashSet()) { BuiltInRegistries.BLOCK.getKey(it)?.toString() }
                }
            }
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to resolve block tag #{}: {}", id, e.message)
            emptySet()
        }
    }
}
