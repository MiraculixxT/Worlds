package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.data.EntityMarkerConfig
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem

/**
 * Entities draws as their spawn egg, or as the item equivalent (e.g. armor_stands)
 */
object EntityIcons {

    private val cache = HashMap<String, ItemStack?>()

    /** Null for an entity nothing can be drawn for, it gets no marker at all */
    fun stack(type: String): ItemStack? =
        if (cache.containsKey(type)) cache[type]
        else resolve(type)?.let { ItemStack(it) }.also { cache[type] = it }

    private fun resolve(type: String): Item? {
        val id = Identifier.tryParse(type) ?: return null
        // config > spawn_egg > item > ignore
        EntityMarkerConfig.icon(type)?.let { special ->
            Identifier.tryParse(special)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
                ?.let { return it }
        }
        BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null)
            ?.let { SpawnEggItem.byId(it) }
            ?.let { return it }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null)
    }
}
