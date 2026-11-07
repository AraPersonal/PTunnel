package com.example.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DebugLogLevel {
    INFO, SUCCESS, WARN, ERROR
}

data class DebugLogEntry(
    val id: Long,
    val timeFormatted: String,
    val stage: String,
    val protocol: String? = null,
    val server: String? = null,
    val message: String,
    val level: DebugLogLevel = DebugLogLevel.INFO
)

/**
 * Thread-safe live connection diagnostics logger.
 * Captures real-time handshake, protocol negotiation, ladder transitions,
 * and failovers for instant user transparency across all app states.
 */
object VpnDebugLogger {

    private const val MAX_LOGS = 120
    private var sequenceId = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    private val _activeStage = MutableStateFlow("IDLE")
    val activeStage: StateFlow<String> = _activeStage.asStateFlow()

    private val _activeProtocol = MutableStateFlow("VLESS • Reality (TCP)")
    val activeProtocol: StateFlow<String> = _activeProtocol.asStateFlow()

    private val _activeServer = MutableStateFlow("🇩🇪 Germany")
    val activeServer: StateFlow<String> = _activeServer.asStateFlow()

    private val _activePingMs = MutableStateFlow(-1)
    val activePingMs: StateFlow<Int> = _activePingMs.asStateFlow()

    init {
        log(
            stage = "INIT",
            message = "PTunnel core initialized. Ready for adaptive ladder connection.",
            protocol = "VLESS • Reality (TCP)",
            server = "🇩🇪 Germany",
            level = DebugLogLevel.INFO
        )
    }

    @Synchronized
    fun log(
        stage: String,
        message: String,
        protocol: String? = null,
        server: String? = null,
        level: DebugLogLevel = DebugLogLevel.INFO,
        pingMs: Int? = null
    ) {
        val now = timeFormat.format(Date())
        sequenceId++

        if (stage.isNotBlank()) {
            _activeStage.value = stage
        }
        if (!protocol.isNullOrBlank()) {
            _activeProtocol.value = protocol
        }
        if (!server.isNullOrBlank()) {
            _activeServer.value = server
        }
        if (pingMs != null) {
            _activePingMs.value = pingMs
        }

        val entry = DebugLogEntry(
            id = sequenceId,
            timeFormatted = now,
            stage = stage,
            protocol = protocol ?: _activeProtocol.value,
            server = server ?: _activeServer.value,
            message = message,
            level = level
        )

        _logs.update { current ->
            val updated = current + entry
            if (updated.size > MAX_LOGS) {
                updated.takeLast(MAX_LOGS)
            } else {
                updated
            }
        }
    }

    fun setPing(pingMs: Int) {
        _activePingMs.value = pingMs
    }

    @Synchronized
    fun clear() {
        _logs.value = emptyList()
        _activeStage.value = "IDLE"
        log("IDLE", "Diagnostics logs cleared.", level = DebugLogLevel.INFO)
    }

    fun dumpFormattedText(): String {
        val sb = StringBuilder()
        sb.append("=== PTunnel Live Diagnostics Report ===\n")
        sb.append("Generated At: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sb.append("Active Server: ${_activeServer.value}\n")
        sb.append("Active Protocol: ${_activeProtocol.value}\n")
        sb.append("Active Stage: ${_activeStage.value}\n")
        sb.append("Active Ping: ${if (_activePingMs.value >= 0) "${_activePingMs.value}ms" else "N/A"}\n")
        sb.append("---------------------------------------\n")
        _logs.value.forEach { entry ->
            val protoTag = if (!entry.protocol.isNullOrBlank()) " [${entry.protocol}]" else ""
            sb.append("[${entry.timeFormatted}] [${entry.stage}]${protoTag} [${entry.level}]: ${entry.message}\n")
        }
        sb.append("=======================================\n")
        return sb.toString()
    }
}
