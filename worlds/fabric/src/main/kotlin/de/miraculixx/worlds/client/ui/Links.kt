package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.Constants
import net.minecraft.util.Util
import java.net.URI
import java.net.URISyntaxException

/**
 * Scheme allowlist for every externally supplied link, i.e. vanilla's own
 * `Util.parseAndValidateUntrustedUri` without the `ConfirmLinkScreen` prompt.
 */
object Links {
    private val ALLOWED_PROTOCOLS = setOf("http", "https")

    /** The URI [url] denotes, or null when it is malformed or not http(s). */
    fun parse(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            Constants.LOG.warn("Refused malformed link '{}': {}", url, e.message)
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in ALLOWED_PROTOCOLS) {
            Constants.LOG.warn("Refused link '{}': scheme '{}' is not allowed", url, uri.scheme)
            return null
        }
        return uri
    }

    /** Open [url] in the system handler, silently dropping anything [parse] rejects. */
    fun open(url: String?) {
        parse(url)?.let { Util.getPlatform().openUri(it) }
    }
}
