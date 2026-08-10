package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http.json
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Shared HTTP client + JSON parser. All calls are blocking and must run off the render thread
 * (they are only invoked from within [Constants.SCOPE] coroutines on [kotlinx.coroutines.Dispatchers.IO]).
 */
object Http {
    const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024 * 1024 // 200GB

    /** Only icons are read into memory. they are never large. */
    const val MAX_MEMORY_BYTES = 32L * 1024 * 1024

    private const val BUFFER = 64 * 1024

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

    /**
     * GET the URL and return the raw bytes, or null on failure or on a body exceeding [maxBytes].
     */
    fun getBytes(url: String, maxBytes: Long = MAX_MEMORY_BYTES): ByteArray? = try {
        val cap = maxBytes.coerceAtMost(Int.MAX_VALUE - 8L).toInt()
        val res = client.send(request(url), HttpResponse.BodyHandlers.ofInputStream())
        when {
            res.statusCode() !in 200..299 -> {
                Constants.LOG.warn("GET(bytes) {} -> HTTP {}", url, res.statusCode())
                res.body().close()
                null
            }

            declaredTooLarge(res, maxBytes) -> {
                Constants.LOG.warn("GET(bytes) {} declares more than {} bytes, refused", url, maxBytes)
                res.body().close()
                null
            }

            else -> res.body().use { it.readNBytes(cap + 1) }
                .takeIf { it.size <= cap }
                ?: run {
                    Constants.LOG.warn("GET(bytes) {} exceeds {} bytes, refused", url, maxBytes)
                    null
                }
        }
    } catch (e: Exception) {
        Constants.LOG.warn("GET(bytes) {} failed: {}", url, e.message)
        null
    }

    /**
     * GET the URL straight into [dest], never holding the body in memory
     */
    fun download(url: String, dest: Path, maxBytes: Long = MAX_DOWNLOAD_BYTES): Boolean = try {
        val res = client.send(request(url), HttpResponse.BodyHandlers.ofInputStream())
        when {
            res.statusCode() !in 200..299 -> {
                Constants.LOG.warn("GET(file) {} -> HTTP {}", url, res.statusCode())
                res.body().close()
                false
            }

            declaredTooLarge(res, maxBytes) -> {
                Constants.LOG.warn("GET(file) {} declares more than {} bytes, refused", url, maxBytes)
                res.body().close()
                false
            }

            else -> {
                res.body().use { input -> Files.newOutputStream(dest).use { out -> copyCapped(input, out, maxBytes) } }
                true
            }
        }
    } catch (e: Exception) {
        Constants.LOG.warn("GET(file) {} failed: {}", url, e.message)
        false
    }

    /**
     * Copy [input] into [out], aborting past [maxBytes]. Returns the number of bytes written
     */
    fun copyCapped(input: InputStream, out: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) throw IOException("Stream exceeds the limit of $maxBytes bytes")
            out.write(buffer, 0, read)
        }
    }

    private fun declaredTooLarge(res: HttpResponse<*>, maxBytes: Long) =
        res.headers().firstValueAsLong("content-length").orElse(-1L) > maxBytes

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
