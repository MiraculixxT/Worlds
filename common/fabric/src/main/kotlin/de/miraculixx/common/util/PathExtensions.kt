package de.miraculixx.common.util

import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

val logger: Logger = LoggerFactory.getLogger("WorldsCommon")

val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

inline fun <reified T> Path.load(default: T, instance: Json = json): T {
    return if (!exists()) {
        createParentDirectories()
        val string = instance.encodeToString(default)
        writeText(string)
        logger.info("Created {} default config", fileName)
        default
    } else {
        try {
            instance.decodeFromString<T>(readText())
        } catch (e: Exception) {
            logger.warn("Failed to load {} config: {}", fileName, e.message)
            default
        }
    }
}
