package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import kotlinx.serialization.Serializable

/**
 * List of projects that are not dependent but still valid.
 * Gathered in a big batch call
 *
 * ```json
 * [
 *   { "source": "modrinth", "slug": "waterworldpack" }
 * ]
 * ```
 */
@Serializable
data class ModrinthPointer(
    val source: String = "modrinth",
    val slug: String? = null,
)

object ModrinthIndex {
    val slugs: List<String> by lazy { read() }

    private fun read(): List<String> {
        val body = ModrinthIndex::class.java.getResourceAsStream(Constants.MODRINTH_INDEX)?.use {
            it.readAllBytes().decodeToString()
        }
        if (body == null) {
            Constants.LOG.warn("Bundled Modrinth list {} is missing", Constants.MODRINTH_INDEX)
            return emptyList()
        }
        return Http.decode<List<ModrinthPointer>>(body).orEmpty()
            .filter { it.source.equals("modrinth", true) }
            .mapNotNull { it.slug?.takeIf { s -> s.isNotBlank() } }
    }
}
