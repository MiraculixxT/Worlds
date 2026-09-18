package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem

/**
 * Entities draws as their spawn egg, or as the item equivalent (e.g. armor_stands)
 */
object EntityIcons {

    private val SPECIAL = mapOf(
        "minecraft:item" to "minecraft:bundle",
        "minecraft:experience_orb" to "minecraft:experience_bottle",
        "minecraft:experience_bottle" to "minecraft:experience_bottle",
        "minecraft:falling_block" to "minecraft:sand",
        "minecraft:lightning_bolt" to "minecraft:lightning_rod",
        "minecraft:fishing_bobber" to "minecraft:fishing_rod",
        "minecraft:leash_knot" to "minecraft:lead",
        "minecraft:eye_of_ender" to "minecraft:ender_eye",
        "minecraft:fireball" to "minecraft:fire_charge",
        "minecraft:small_fireball" to "minecraft:fire_charge",
        "minecraft:dragon_fireball" to "minecraft:dragon_breath",
        "minecraft:wither_skull" to "minecraft:wither_skeleton_skull",
        "minecraft:shulker_bullet" to "minecraft:shulker_shell",
        "minecraft:llama_spit" to "minecraft:snowball",
        "minecraft:potion" to "minecraft:splash_potion",
        "minecraft:area_effect_cloud" to "minecraft:lingering_potion",
        "minecraft:evoker_fangs" to "minecraft:totem_of_undying",
        "minecraft:breeze_wind_charge" to "minecraft:wind_charge",
        "minecraft:block_display" to "minecraft:structure_block",
        "minecraft:item_display" to "minecraft:item_frame",
        "minecraft:text_display" to "minecraft:oak_sign",
        "minecraft:interaction" to "minecraft:structure_void",
        "minecraft:marker" to "minecraft:structure_void",
        "minecraft:ominous_item_spawner" to "minecraft:trial_key",
    )

    private val cache = HashMap<String, ItemStack>()

    fun stack(type: String): ItemStack = cache.getOrPut(type) { ItemStack(resolve(type)) }

    private fun resolve(type: String): Item {
        val id = Identifier.tryParse(type) ?: return Items.EGG
        BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null)
            ?.let { SpawnEggItem.byId(it).orElse(null) }
            ?.let { return it.value() }
        SPECIAL[type]?.let { special ->
            Identifier.tryParse(special)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
                ?.let { return it }
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.EGG)
    }

    fun bound(): Boolean = Items.STONE.builtInRegistryHolder().areComponentsBound()

    /** Item stacks can not be built before the vanilla components are bound */
    fun prepare(onDone: (Boolean) -> Unit) {
        if (bound()) {
            onDone(true)
            return
        }
        Constants.SCOPE.launch {
            val pending = try {
                BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
            } catch (e: Exception) {
                Constants.LOG.error("Could not build item data components", e)
                Minecraft.getInstance().execute { onDone(false) }
                return@launch
            }
            Minecraft.getInstance().execute {
                pending.forEach { it.apply() }
                onDone(true)
            }
        }
    }
}
