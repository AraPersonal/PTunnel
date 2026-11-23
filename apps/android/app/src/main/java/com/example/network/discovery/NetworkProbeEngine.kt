package com.example.network.discovery

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.model.BackendEndpointConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Result of the two-phase discovery and calibration process.
 */
data class DiscoveryResult(
    val selectedCandidate: PresetCandidateDto,
    val configJson: String,
    val identity: NetworkIdentity,
    val latencyMs: Long,
    val throughputKBps: Double,
    val phase: Int, // 1 = server presets, 2 = matrix scan, 0 = local cache
    val isStable: Boolean = false
)

data class ChunkedProbeResult(
    val totalBytes: Long,
    val durationSeconds: Double,
    val throughputKBps: Double,
    val latencyMs: Long,
    val verdict: String,
    val error: String? = null
)

/**
 * Two-phase discovery engine implementing docs/design/0001:
 * - Phase 1: Fast Presets Probe (Google 204 / fast-probe + 512KB range-chunked throughput gate >= 500 KB/s)
 * - Phase 2: Exploratory Matrix Grid Search (fragment, port, mtu, clean ip)
 * - Protected sockets via SocketProtector
 * - Local caching per network_key
 */
class NetworkProbeEngine(
    private val context: Context,
    private val repository: DiscoveryApiClient,
    private val detector: NetworkDetector = NetworkDetector(context)
) {
    companion object {
        private const val TAG = "NetworkProbeEngine"
        private const val PREFS_PRESETS = "ptunnel_network_presets"
        private const val CHUNK_SIZE_BYTES = 512L * 1024L
        private const val SCAN_CHUNKS_COUNT = 4 // 4 * 512KB = 2MB
        const val THROUGHPUT_GATE_KBPS = 500.0 // T=500 KB/s threshold from ADR-0007 / design 0001
        private const val FAST_PROBE_TIMEOUT_MS = 1200
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_PRESETS, Context.MODE_PRIVATE)

    private val probeClient: OkHttpClient = createProtectedOkHttpClient(
        connectTimeoutSeconds = 4,
        readTimeoutSeconds = 8
    )

    /**
     * Executes the two-phase calibration process.
     * @param authToken Current user auth token
     * @param baseConfig Server endpoint config
     * @param onProgress Callback receiving current discovery reality state
     */
    suspend fun discoverAndTune(
        authToken: String,
        baseConfig: BackendEndpointConfig,
        forceRescan: Boolean = false,
        onProgress: ((status: String, details: String) -> Unit)? = null
    ): Result<DiscoveryResult> = withContext(Dispatchers.IO) {
        onProgress?.invoke("scanning", "Detecting network and carrier...")

        // Step 1: Detect Network and Carrier Identity
        val identity = detector.detect()
        Log.i(TAG, "Network detected: ${identity.operatorTag} (${identity.networkKey})")
        onProgress?.invoke("scanning", "Carrier: ${identity.displayName} (${identity.simDetails})")

        // Step 2: Register brand-new operator if observed
        var activeIdentity = identity
        if (identity.isNewOperator && authToken.isNotBlank()) {
            onProgress?.invoke("scanning", "Registering newly discovered network with registry...")
            try {
                val observeResult = repository.observeOperator(
                    token = authToken,
                    baseUrl = baseConfig.apiBaseUrl,
                    request = ObserveRequestDto(
                        networkType = identity.networkType,
                        evidence = identity.evidence,
                        clientView = ClientViewDto(provinceGuess = identity.region)
                    )
                )
                observeResult.onSuccess { resp ->
                    activeIdentity = identity.copy(
                        operatorKey = resp.operatorKey,
                        operatorTag = resp.operatorTag,
                        displayName = resp.displayName.ifBlank { identity.displayName },
                        networkKey = resp.networkKey.ifBlank { identity.networkKey },
                        confidence = resp.confidence
                    )
                    Log.i(TAG, "Operator registered as provisional: ${resp.operatorKey}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Observe request failed: ${t.message}")
            }
        }

        val origin = baseConfig.apiBaseUrl.substringBefore("/v1").trimEnd('/')
        val probeUrl = "$origin/dl"

        // Step 3: Check Local Cache (if not forcing rescan)
        if (!forceRescan) {
            val cached = getCachedProfile(activeIdentity.networkKey)
            if (cached != null) {
                Log.i(TAG, "Using locally cached validated candidate for ${activeIdentity.networkKey}")
                onProgress?.invoke("scanning", "Found local profile for ${activeIdentity.networkKey}")
                val host = extractCandidateHost(cached.candidate, baseConfig)
                val port = extractCandidatePort(cached.candidate, baseConfig)
                val latency = fastTcpProbe(host, port, FAST_PROBE_TIMEOUT_MS)
                if (latency >= 0) {
                    return@withContext Result.success(
                        DiscoveryResult(
                            selectedCandidate = cached.candidate,
                            configJson = cached.configJson,
                            identity = activeIdentity,
                            latencyMs = latency,
                            throughputKBps = cached.throughputKBps,
                            phase = 0
                        )
                    )
                }
                Log.w(TAG, "Cached profile failed TCP check, advancing to Phase 1")
            }
        }

        // Step 4: Phase 1 — Server Presets
        onProgress?.invoke("scanning", "Querying server presets for ${activeIdentity.operatorTag}...")
        val presetsResult = repository.fetchPresets(
            token = authToken,
            baseUrl = baseConfig.apiBaseUrl,
            operatorKey = activeIdentity.operatorKey,
            operatorTag = activeIdentity.operatorTag,
            networkKey = activeIdentity.networkKey,
            networkType = activeIdentity.networkType
        )

        val candidates = presetsResult.getOrNull()?.candidates.orEmpty()
        var bestPreset: PresetCandidateDto? = null
        var bestPresetLatency = -1L
        var bestPresetThroughput = 0.0

        for (candidate in candidates) {
            onProgress?.invoke("scanning", "Testing preset: ${candidate.engine}/${candidate.transport} (priority ${candidate.priority})...")
            val host = extractCandidateHost(candidate, baseConfig)
            val port = extractCandidatePort(candidate, baseConfig)

            // Fast Probe (TCP handshake)
            val latency = fastTcpProbe(host, port, FAST_PROBE_TIMEOUT_MS)
            if (latency < 0) {
                Log.d(TAG, "Candidate ${candidate.configId} failed fast TCP probe")
                continue
            }

            // Gate 2: Chunked throughput gate (2MB total)
            val chunked = measureChunkedThroughput(probeUrl, CHUNK_SIZE_BYTES, SCAN_CHUNKS_COUNT)
            Log.i(TAG, "Candidate ${candidate.configId} throughput: ${chunked.throughputKBps} KB/s (latency: ${chunked.latencyMs}ms)")

            if (bestPreset == null || chunked.throughputKBps > bestPresetThroughput) {
                bestPreset = candidate
                bestPresetLatency = latency
                bestPresetThroughput = chunked.throughputKBps
            }

            if (chunked.verdict == "ok" && chunked.throughputKBps >= THROUGHPUT_GATE_KBPS) {
                Log.i(TAG, "Phase 1 success! Candidate ${candidate.configId} qualified with ${chunked.throughputKBps} KB/s")
                val config = buildCandidateConfigJson(candidate, baseConfig)
                val injected = DomesticSplitTunnel.injectDomesticRouting(config)
                cacheProfile(activeIdentity.networkKey, candidate, injected, chunked.throughputKBps)

                return@withContext Result.success(
                    DiscoveryResult(
                        selectedCandidate = candidate,
                        configJson = injected,
                        identity = activeIdentity,
                        latencyMs = chunked.latencyMs,
                        throughputKBps = chunked.throughputKBps,
                        phase = 1
                    )
                )
            }
        }

        // If a server preset answered TCP probe, use it immediately
        if (bestPreset != null) {
            Log.i(TAG, "Using TCP-validated preset ${bestPreset.configId} (latency: ${bestPresetLatency}ms)")
            val config = buildCandidateConfigJson(bestPreset, baseConfig)
            val injected = DomesticSplitTunnel.injectDomesticRouting(config)
            cacheProfile(activeIdentity.networkKey, bestPreset, injected, bestPresetThroughput)
            return@withContext Result.success(
                DiscoveryResult(
                    selectedCandidate = bestPreset,
                    configJson = injected,
                    identity = activeIdentity,
                    latencyMs = bestPresetLatency,
                    throughputKBps = bestPresetThroughput,
                    phase = 1
                )
            )
        }

        // Step 5: Phase 2 — Exploratory Matrix Grid Search
        onProgress?.invoke("scanning", "Presets exhausted. Initiating exploratory matrix grid scan...")
        Log.w(TAG, "No presets passed throughput gate; starting Phase 2 matrix grid search")

        val matrix = buildMatrixCandidates()
        var bestCandidate: PresetCandidateDto? = null
        var bestScore = -1.0
        var bestThroughput = 0.0
        var bestLatency = -1L

        for (matrixItem in matrix) {
            val candidatePort = (matrixItem.params["port"] as? Number)?.toInt() ?: 443
            onProgress?.invoke("scanning", "Scanning matrix: port=${candidatePort}, frag=${matrixItem.params["fragment"] ?: "none"}...")
            val latency = fastTcpProbe(extractHostFromUrl(baseConfig.apiBaseUrl), candidatePort, FAST_PROBE_TIMEOUT_MS)
            if (latency < 0) continue

            val chunked = measureChunkedThroughput(probeUrl, CHUNK_SIZE_BYTES, 2)
            val score = scoreCandidate(chunked.throughputKBps, chunked.latencyMs, 0.0)

            if (score > bestScore) {
                bestScore = score
                bestCandidate = matrixItem
                bestThroughput = chunked.throughputKBps
                bestLatency = chunked.latencyMs
            }

            // Early stop as soon as we find a good connection
            if (chunked.throughputKBps >= THROUGHPUT_GATE_KBPS) {
                Log.i(TAG, "Phase 2 early stop on candidate ${matrixItem.configId} with ${chunked.throughputKBps} KB/s")
                break
            }
        }

        val winningCandidate = bestCandidate ?: PresetCandidateDto(
            configId = "fallback-reality",
            status = "seed",
            engine = "xray",
            transport = "reality",
            params = mapOf("port" to 443, "sni" to "www.microsoft.com")
        )

        val config = buildCandidateConfigJson(winningCandidate, baseConfig)
        val injected = DomesticSplitTunnel.injectDomesticRouting(config)
        cacheProfile(activeIdentity.networkKey, winningCandidate, injected, bestThroughput)

        Result.success(
            DiscoveryResult(
                selectedCandidate = winningCandidate,
                configJson = injected,
                identity = activeIdentity,
                latencyMs = if (bestLatency > 0) bestLatency else 150L,
                throughputKBps = bestThroughput,
                phase = 2
            )
        )
    }

    private fun fastTcpProbe(host: String, port: Int, timeoutMs: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                SocketProtector.protect(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (_: Throwable) {
            -1L
        }
    }

    suspend fun measureChunkedThroughput(
        testUrl: String,
        chunkSizeBytes: Long = CHUNK_SIZE_BYTES,
        numChunks: Int = SCAN_CHUNKS_COUNT
    ): ChunkedProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var totalBytes = 0L
        var initialLatency = -1L

        try {
            for (i in 0 until numChunks) {
                val rangeStart = i * chunkSizeBytes
                val rangeEnd = rangeStart + chunkSizeBytes - 1

                val request = Request.Builder()
                    .url(testUrl)
                    .header("Range", "bytes=$rangeStart-$rangeEnd")
                    .get()
                    .build()

                val chunkStart = System.currentTimeMillis()
                probeClient.newCall(request).execute().use { response ->
                    if (i == 0) {
                        initialLatency = System.currentTimeMillis() - chunkStart
                    }
                    if (response.code != 200 && response.code != 206) {
                        return@withContext ChunkedProbeResult(
                            totalBytes = totalBytes,
                            durationSeconds = (System.currentTimeMillis() - start) / 1000.0,
                            throughputKBps = 0.0,
                            latencyMs = initialLatency,
                            verdict = "fail",
                            error = "HTTP ${response.code}"
                        )
                    }

                    val body = response.body ?: return@withContext ChunkedProbeResult(
                        totalBytes = totalBytes,
                        durationSeconds = 0.0,
                        throughputKBps = 0.0,
                        latencyMs = initialLatency,
                        verdict = "fail",
                        error = "Empty body"
                    )

                    val buffer = ByteArray(32 * 1024)
                    body.byteStream().use { stream ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                        }
                    }
                }
            }

            val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
            val duration = if (elapsedSec <= 0.0) 0.001 else elapsedSec
            val throughput = (totalBytes.toDouble() / 1024.0) / duration
            val verdict = if (throughput >= THROUGHPUT_GATE_KBPS) "ok" else "fail"

            ChunkedProbeResult(
                totalBytes = totalBytes,
                durationSeconds = duration,
                throughputKBps = throughput,
                latencyMs = initialLatency,
                verdict = verdict
            )
        } catch (t: Throwable) {
            val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
            ChunkedProbeResult(
                totalBytes = totalBytes,
                durationSeconds = elapsedSec,
                throughputKBps = 0.0,
                latencyMs = initialLatency,
                verdict = "fail",
                error = t.message
            )
        }
    }

    internal fun buildMatrixCandidates(): List<PresetCandidateDto> {
        val list = mutableListOf<PresetCandidateDto>()

        // 1. Fragment variations
        val fragmentOptions = listOf(
            mapOf("packets" to "1-3", "length" to "10-50", "interval" to "5-10"),
            mapOf("packets" to "1-3", "length" to "100-200", "interval" to "10-20"),
            mapOf("packets" to "1-3", "length" to "300-600", "interval" to "1-5")
        )
        for (frag in fragmentOptions) {
            list.add(
                PresetCandidateDto(
                    priority = 5,
                    configId = "matrix-frag-${frag["length"]}",
                    status = "candidate",
                    engine = "xray",
                    transport = "reality",
                    params = mapOf("port" to 443, "fragment" to frag, "mtu" to 1280)
                )
            )
        }

        // 2. Port hopping & Hysteria 2 options
        val ports = listOf(443, 8443, 2053, 25000)
        for (port in ports) {
            list.add(
                PresetCandidateDto(
                    priority = 6,
                    configId = "matrix-hy2-$port",
                    status = "candidate",
                    engine = "hysteria",
                    transport = "hy2",
                    params = mapOf("port" to port, "ports" to "20000-50000", "mtu" to 1280)
                )
            )
        }

        return list
    }

    internal fun scoreCandidate(throughputKBps: Double, latencyMs: Long, deathRate: Double): Double {
        if (throughputKBps <= 0) return 0.0
        val dr = deathRate.coerceIn(0.0, 1.0)
        var score = throughputKBps * (1.0 - dr)
        if (latencyMs in 1..4999) {
            score += (5000.0 - latencyMs.toDouble()) / 50000.0
        }
        return score
    }

    private fun extractCandidateHost(candidate: PresetCandidateDto, baseConfig: BackendEndpointConfig): String {
        val hostFromParams = candidate.params["host"] as? String
        return hostFromParams?.ifBlank { null } ?: extractHostFromUrl(baseConfig.apiBaseUrl)
    }

    private fun extractCandidatePort(candidate: PresetCandidateDto, baseConfig: BackendEndpointConfig): Int {
        val portNum = (candidate.params["port"] as? Number)?.toInt()
        return portNum ?: baseConfig.defaultPort
    }

    private fun extractHostFromUrl(url: String): String {
        return url.substringAfter("://").substringBefore("/").substringBefore(":")
    }

    private fun buildCandidateConfigJson(candidate: PresetCandidateDto, baseConfig: BackendEndpointConfig): String {
        val root = JSONObject()
        root.put("tag", candidate.transport)
        root.put("transport", candidate.transport)
        root.put("engine", candidate.engine)
        val paramsObj = JSONObject(candidate.params)
        root.put("params", paramsObj)
        return root.toString()
    }

    private data class CachedProfile(
        val candidate: PresetCandidateDto,
        val configJson: String,
        val throughputKBps: Double,
        val savedAt: Long
    )

    private fun getCachedProfile(networkKey: String): CachedProfile? {
        val raw = prefs.getString(networkKey, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val candidateObj = obj.getJSONObject("candidate")
            val candidate = PresetCandidateDto(
                configId = candidateObj.getString("config_id"),
                status = candidateObj.optString("status", "validated"),
                confidence = candidateObj.optDouble("confidence", 0.8),
                engine = candidateObj.optString("engine", "xray"),
                transport = candidateObj.optString("transport", "reality")
            )
            CachedProfile(
                candidate = candidate,
                configJson = obj.getString("config_json"),
                throughputKBps = obj.optDouble("throughput", 0.0),
                savedAt = obj.optLong("saved_at", 0L)
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun cacheProfile(
        networkKey: String,
        candidate: PresetCandidateDto,
        configJson: String,
        throughput: Double
    ) {
        try {
            val obj = JSONObject().apply {
                val cObj = JSONObject().apply {
                    put("config_id", candidate.configId)
                    put("status", candidate.status)
                    put("confidence", candidate.confidence)
                    put("engine", candidate.engine)
                    put("transport", candidate.transport)
                }
                put("candidate", cObj)
                put("config_json", configJson)
                put("throughput", throughput)
                put("saved_at", System.currentTimeMillis())
            }
            prefs.edit().putString(networkKey, obj.toString()).apply()
            Log.i(TAG, "Cached profile for $networkKey (throughput: $throughput KB/s)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cache profile: ${t.message}")
        }
    }
}
