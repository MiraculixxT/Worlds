package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.Loader
import de.miraculixx.common.util.load
import kotlinx.serialization.Serializable

/**
 * @param special entity id → item id, for entities that have no spawn egg and no item of their name
 * @param blacklist entity ids that get no markers
 */
@Serializable
data class EntityMarkerSettings(
    val special: Map<String, String> = DEFAULT_SPECIAL,
    val blacklist: Set<String> = DEFAULT_BLACKLIST,
)

private val DEFAULT_SPECIAL = mapOf(
    "minecraft:marker" to "minecraft:structure_void",
    "minecraft:interaction" to "minecraft:structure_void",
    "minecraft:block_display" to "minecraft:structure_block",
    "minecraft:item_display" to "minecraft:item_frame",
    "minecraft:text_display" to "minecraft:name_tag",
)

private val DEFAULT_BLACKLIST = setOf(
    "minecraft:item",
    "minecraft:experience_orb",
    "minecraft:experience_bottle",
    "minecraft:falling_block",
    "minecraft:lightning_bolt",
    "minecraft:fishing_bobber",
    "minecraft:leash_knot",
    "minecraft:eye_of_ender",
    "minecraft:fireball",
    "minecraft:small_fireball",
    "minecraft:dragon_fireball",
    "minecraft:wither_skull",
    "minecraft:shulker_bullet",
    "minecraft:llama_spit",
    "minecraft:potion",
    "minecraft:area_effect_cloud",
    "minecraft:evoker_fangs",
    "minecraft:breeze_wind_charge",
    "minecraft:ominous_item_spawner",
)

/** `config/chunkeditor/entity_markers.json`, written with its defaults on first read */
object EntityMarkerConfig {
    private val file = Loader.configDir.resolve("${Constants.MOD_ID}/entity_markers.json")

    val settings: EntityMarkerSettings by lazy { file.load(EntityMarkerSettings()) }

    fun hidden(type: String): Boolean = type in settings.blacklist

    fun icon(type: String): String? = settings.special[type]
}
