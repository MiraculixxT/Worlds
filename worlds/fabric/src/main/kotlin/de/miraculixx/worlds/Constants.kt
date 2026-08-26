package de.miraculixx.worlds

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory


object Constants {
    const val MOD_ID = "worlds"

    /** Modrinth project id/slug of this mod, used for the reverse-dependency search. */
    const val WORLDS_MODRINTH_ID = "world"

    const val MODRINTH_API = "https://api.modrinth.com/v2"

    /** Backend base URL see [de.miraculixx.worlds.api.WorldsApi]. */
    const val API_BASE = "https://api.miraculixx.de/worlds"

    /** Manual added maps list */
    const val MANUAL_INDEX = "/assets/$MOD_ID/maps.json"

    /** Manual added modrinth projects list */
    const val MODRINTH_INDEX = "/assets/$MOD_ID/modrinth.json"

    val USER_AGENT = "miraculixx/Worlds (worlds mod client)"

    val LOG = LoggerFactory.getLogger("Worlds")

    /** Background scope for network + IO work. UI results are marshalled back via Minecraft#execute. */
    val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
