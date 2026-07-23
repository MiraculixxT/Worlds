package de.miraculixx.worlds

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

/**
 * Central constants and shared infrastructure for the Worlds mod.
 *
 * TODO(config): [WORLDS_MODRINTH_ID] and [MAPS_JSON_URL] point at the live sources.
 *  Confirm the real Modrinth project id and the GitHub raw path once the repo/project exist.
 */
object Constants {
    const val MOD_ID = "worlds"

    /** Modrinth project id/slug of this mod, used for the reverse-dependency search. */
    const val WORLDS_MODRINTH_ID = "world"

    /** Raw GitHub URL of the curated manual map index. */
    const val MAPS_JSON_URL =
        "https://raw.githubusercontent.com/MiraculixxT/Worlds/refs/heads/main/maps.json"

    const val MODRINTH_API = "https://api.modrinth.com/v2"

    val USER_AGENT = "miraculixx/Worlds (worlds mod client)"

    val LOG = LoggerFactory.getLogger("Worlds")

    /** Background scope for network + IO work. UI results are marshalled back via Minecraft#execute. */
    val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
