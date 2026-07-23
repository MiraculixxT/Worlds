package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http.json
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Shared HTTP client + JSON parser. All calls are blocking and must run off the render thread
 * (they are only invoked from within [Constants.SCOPE] coroutines on [kotlinx.coroutines.Dispatchers.IO]).
 */
object Http {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", Constants.USER_AGENT)
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build()

    /** GET the URL and return the body as a String, or null on any non-2xx / failure. */
    fun getString(url: String): String? = try {
        val res = client.send(request(url), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() in 200..299) res.body() else {
            Constants.LOG.warn("GET {} -> HTTP {}", url, res.statusCode())
            null
        }
    } catch (e: Exception) {
        Constants.LOG.warn("GET {} failed: {}", url, e.message)
        null
    }

    /** GET the URL and return the raw bytes, or null on failure. */
    fun getBytes(url: String): ByteArray? = try {
        val res = client.send(request(url), HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() in 200..299) res.body() else {
            Constants.LOG.warn("GET(bytes) {} -> HTTP {}", url, res.statusCode())
            null
        }
    } catch (e: Exception) {
        Constants.LOG.warn("GET(bytes) {} failed: {}", url, e.message)
        null
    }

    /** Parse [body] into [T] using the shared lenient [json], or null on failure. */
    inline fun <reified T> decode(body: String?): T? {
        if (body == null) return null
        return try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            Constants.LOG.warn("JSON decode failed: {}", e.message)
            null
        }
    }
}
