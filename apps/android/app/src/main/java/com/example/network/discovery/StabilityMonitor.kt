package com.example.network.discovery

import android.util.Log
import com.example.model.BackendEndpointConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Monitors connection stability window and submits crowdsourced reports to
 * POST /v1/presets/report once the connection remains stable for >= 3 minutes (T_stable_min).
 */
class StabilityMonitor(
    private val apiClient: DiscoveryApiClient
) {
    companion object {
        private const val TAG = "StabilityMonitor"
        const val STABILITY_THRESHOLD_MINUTES = 3.0 // T_stable_min = 3 minutes
        private const val CHECK_INTERVAL_MS = 20_000L
    }

    private var connectedSince: Long = 0L
    private var reconnectCount: Int = 0
    private var deathCount: Int = 0
    private var reportedForThisEpisode: Boolean = false

    private var activeCandidate: PresetCandidateDto? = null
    private var activeIdentity: NetworkIdentity? = null
    private val samples = mutableListOf<PresetSampleDto>()

    private var monitorJob: Job? = null

    fun onConnected(
        candidate: PresetCandidateDto,
        identity: NetworkIdentity,
        initialLatencyMs: Long,
        initialThroughputKBps: Double
    ) {
        connectedSince = System.currentTimeMillis()
        reconnectCount = 0
        deathCount = 0
        reportedForThisEpisode = false
        activeCandidate = candidate
        activeIdentity = identity
        samples.clear()

        // Record initial sample from discovery
        recordSample(
            latencyMs = initialLatencyMs,
            throughputKBps = initialThroughputKBps,
            bytes = 2097152L,
            durationS = 2.0,
            verdict = if (initialThroughputKBps >= 500.0) "ok" else "fail"
        )
        Log.i(TAG, "Connection verified. Stability window started for candidate ${candidate.configId} on ${identity.networkKey}")
    }

    fun recordSample(
        latencyMs: Long,
        throughputKBps: Double,
        bytes: Long,
        durationS: Double,
        verdict: String
    ) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sample = PresetSampleDto(
            latencyMs = latencyMs,
            throughputKBps = throughputKBps,
            bytes = bytes,
            durationS = durationS,
            verdict = verdict,
            ts = dateFormat.format(Date())
        )
        synchronized(samples) {
            samples.add(sample)
            if (samples.size > 20) {
                samples.removeAt(0)
            }
        }
    }

    fun recordDeath() {
        deathCount++
        Log.w(TAG, "Connection drop/death observed ($deathCount)")
    }

    fun recordReconnect() {
        reconnectCount++
        Log.i(TAG, "Reconnect recorded ($reconnectCount)")
    }

    fun onDisconnected() {
        monitorJob?.cancel()
        monitorJob = null
        connectedSince = 0L
        reportedForThisEpisode = false
    }

    fun startMonitoring(
        scope: CoroutineScope,
        authToken: String,
        baseConfig: BackendEndpointConfig
    ) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && connectedSince > 0L) {
                delay(CHECK_INTERVAL_MS)

                val elapsedMinutes = (System.currentTimeMillis() - connectedSince) / 60000.0
                val candidate = activeCandidate
                val identity = activeIdentity

                if (candidate != null && identity != null && !reportedForThisEpisode) {
                    // Check stability requirements from design 0001 §5.3
                    if (elapsedMinutes >= STABILITY_THRESHOLD_MINUTES && deathCount == 0) {
                        val sampleList: List<PresetSampleDto>
                        synchronized(samples) {
                            sampleList = samples.toList()
                        }

                        if (sampleList.isNotEmpty() && authToken.isNotBlank()) {
                            Log.i(TAG, "Stability window completed (${"%.1f".format(elapsedMinutes)} min). Submitting crowdsourced report to POST /v1/presets/report")
                            val report = PresetReportDto(
                                operatorKey = identity.operatorKey,
                                operatorTag = identity.operatorTag,
                                networkKey = identity.networkKey,
                                configId = candidate.configId,
                                probeMethod = "range-512k",
                                samples = sampleList,
                                stability = PresetStabilityDto(
                                    connectedMinutes = elapsedMinutes,
                                    reconnects = reconnectCount,
                                    deaths = deathCount
                                )
                            )

                            try {
                                val result = apiClient.reportPreset(
                                    token = authToken,
                                    baseUrl = baseConfig.apiBaseUrl,
                                    report = report
                                )
                                result.onSuccess {
                                    reportedForThisEpisode = true
                                    Log.i(TAG, "Crowdsourced report accepted by server: status=${it.status}, samples=${it.samples}")
                                }.onFailure { error ->
                                    Log.w(TAG, "Preset report rejected or failed: ${error.message}")
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "Failed to submit preset report: ${t.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    fun currentStabilityMinutes(): Double {
        return if (connectedSince > 0L) (System.currentTimeMillis() - connectedSince) / 60000.0 else 0.0
    }

    fun currentDeathCount(): Int = deathCount
}
