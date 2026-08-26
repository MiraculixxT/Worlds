package de.miraculixx.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class ModScope(val modId: String, logName: String = modId) {
    val log: Logger = LoggerFactory.getLogger(logName)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
