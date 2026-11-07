package com.example.network

import android.util.Log
import com.example.model.BackendEndpointConfig
import com.example.model.UserProfile
import com.example.model.VpnLocation
import com.example.model.VpnServer
import com.example.update.AppUpdateInfo
import com.example.vpn.ParsedQuotaInfo
import com.example.vpn.VpnUriParser
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Url
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * STRICT SERVER-VERIFIED API CONTRACT (FOR BACKEND ENGINEERING TEAM)
 * ============================================================================
 *
 * Every action (Login, Create Profile, Save Profile, Fetch Profile, Sync Traffic)
 * strictly requires a real HTTP 2xx verification from the configured server
 * (`BackendEndpointConfig.apiBaseUrl`). No local bypass or mock login is allowed.
 *
 * Endpoints expected on the backend server:
 * 1. `POST auth/login`        -> Verifies email & password, returns `ServerAuthResponse`
 * 2. `POST auth/register`     -> Creates user profile on server, returns `ServerAuthResponse`
 * 3. `GET  user/profile`      -> Returns `ServerAuthResponse` (usedBytes, totalBytes, plan, nodes)
 * 4. `PUT  user/profile`      -> Updates user profile on server, returns `ServerAuthResponse`
 * 5. `POST user/traffic-sync` -> Syncs consumed VPN session bytes with server DB
 */

data class LoginRequestDto(
    val username: String,
    val password: String,
    val deviceName: String = "Android-PTunnel"
)

data class RegisterProfileRequestDto(
    val displayName: String,
    val email: String,
    val password: String
)

data class UpdateProfileRequestDto(
    val displayName: String,
    val email: String
)

data class TrafficSyncRequestDto(
    val uid: String,
    val consumedSessionBytes: Long,
    val totalUsedBytes: Long,
    val activeNodeHost: String,
    val activeNodePort: Int
)

data class ServerNodeDto(
    val tag: String = "",
    val id: String = "",
    val name: String = "",
    val protocol: String = "",
    val port: Int = 443,
    val host: String = "",
    val flag: String = "",
    val transport: String = "",
    val security: String = ""
)

data class LocationDto(
    val id: String = "",
    val name: String = "",
    val country: String = "",
    val host: String = "",
    val domain: String = "",
    val flag: String = "",
    @Json(name = "api_base") val apiBase: String = ""
)

data class LocationsResponse(
    val locations: List<LocationDto> = emptyList()
)

data class AppLatestDto(
    val versionCode: Long = 0L,
    val versionName: String = "",
    /** ECDSA P-256 signature over `url|sha256|versionCode`; see [UpdateMetadata]. */
    val sig: String = "",
    val url: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val minVersionCode: Long = 0L,
    val mandatory: Boolean = false,
    val notes: String = "",
    val publishedAt: String = ""
)

data class ServerAuthResponse(
    val uid: String,
    val displayName: String,
    val email: String,
    val token: String,
    val planTitle: String = "",
    val usedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expiryDaysRemaining: Int = 0,
    val subscriptionPayload: String? = null,
    val username: String = "",
    val status: String = "active",
    val expiresAt: String? = null,
    val servers: List<ServerNodeDto> = emptyList()
)

interface ShieldBackendApi {

    @POST("auth/login")
    suspend fun login(
        @Body request: LoginRequestDto
    ): Response<ServerAuthResponse>

    @POST("auth/register")
    suspend fun registerProfile(
        @Body request: RegisterProfileRequestDto
    ): Response<ServerAuthResponse>

    @GET("user/profile")
    suspend fun fetchUserProfile(
        @Header("Authorization") bearerToken: String
    ): Response<ServerAuthResponse>

    @PUT("user/profile")
    suspend fun updateUserProfile(
        @Header("Authorization") bearerToken: String,
        @Body request: UpdateProfileRequestDto
    ): Response<ServerAuthResponse>

    @POST("user/traffic-sync")
    suspend fun syncTrafficUsage(
        @Header("Authorization") bearerToken: String,
        @Body request: TrafficSyncRequestDto
    ): Response<ServerAuthResponse>

    @GET
    suspend fun downloadSubscriptionUrl(
        @Url subscriptionUrl: String
    ): Response<ResponseBody>

    @GET("client/xray")
    suspend fun fetchXrayConfig(
        @Header("Authorization") bearerToken: String,
        @Query("tag") tag: String
    ): Response<ResponseBody>

    @GET("locations")
    suspend fun fetchLocations(): Response<LocationsResponse>

    @GET("app/latest")
    suspend fun fetchLatestApp(): Response<AppLatestDto>

    @POST("reachability")
    suspend fun reportReachability(
        @Body request: ReachabilityReportDto
    ): Response<ResponseBody>
}

data class ReachabilityReportDto(
    val verdict: String,
    val port: Int,
    /** Which control-plane endpoint this client actually reached; empty on older builds. */
    @Json(name = "box") val box: String = ""
)

/**
 * Hostname of an endpoint such as `https://86-54-42-124.nip.io/v1/`.
 *
 * Sent with every reachability report, which is what lets `/s` answer "which of our boxes can clients
 * in this country reach" without anyone opening a URL by hand.
 */
fun endpointHost(endpoint: String?): String? {
    val trimmed = endpoint?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    // Stripping only when a scheme is there: `substringAfter` with a default returns that default
    // when the delimiter is missing, which would file a scheme-less host as no host at all.
    val authority = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
    return authority
        .substringBefore("/")
        .substringBefore(":")
        .ifBlank { null }
}

/** A failure the server stated explicitly (bad credentials, expired token). Never retried. */
open class DefinitiveFailure(message: String) : IllegalStateException(message)

/**
 * The server refused the token we are holding. This is the only failure that may end a session:
 * matching on the text of an error message instead cannot work, because a connect timeout reports
 * the device's own ephemeral source port, and a port like 40106 contains "401".
 */
class SessionExpired(val statusCode: Int) :
    DefinitiveFailure("Server refused the stored token (HTTP $statusCode)")

/** Whether a failure means the stored token is dead, as opposed to the network being down. */
fun shouldEndSession(error: Throwable?): Boolean = error is SessionExpired

/** Maps a failed profile refresh to the failure the caller can tell apart. */
fun failureForRefreshCode(code: Int): Throwable =
    if (code == 401) SessionExpired(code)
    else IllegalStateException("Server returned HTTP $code on refresh")

/** Reachability verdicts, shared with the server-side fleet aggregation. */
const val REACHABILITY_DEVICE_NETWORK = "device_network"
const val REACHABILITY_SERVER_UNREACHABLE = "server_unreachable"
const val REACHABILITY_TUNNEL_FAILED = "tunnel_failed"

/** Heartbeat: this client's tunnel is up, so the fleet has a healthy network to weigh against. */
const val REACHABILITY_OK = "ok"

/**
 * Whether a failed attempt should move onto the Cloudflare-fronted path.
 *
 * "Our address is not answering at all" is enough evidence on its own: that is the block we can
 * actually route around, and waiting for anyone else to confirm it would leave the first users cut
 * off. "Our address answers but the tunnel will not come up" is ambiguous from one device, so that
 * one only counts once the fleet has corroborated it from several distinct networks.
 */
fun shouldFallBackToCdn(deviceVerdict: String?, fleetVerdict: String?): Boolean =
    when (deviceVerdict) {
        REACHABILITY_SERVER_UNREACHABLE -> true
        REACHABILITY_TUNNEL_FAILED -> fleetVerdict == REACHABILITY_SERVER_UNREACHABLE
        else -> false
    }

class VpnBackendRepository {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    /**
     * The control channel must not depend on one hostname or one DNS answer: local DNS is
     * poisoned, so every known host is pinned to the server IP and several hosts are tried in
     * order before we give up. Both lists can be replaced at runtime from the bootstrap channel,
     * so a new entry point does not need an app release.
     */
    @Volatile
    private var hostPins: Map<String, String> = API_HOST_PINS

    @Volatile
    private var baseUrls: List<String> = BOOTSTRAP_BASE_URLS

    /**
     * The endpoint that carried the last successful call. It leads every later candidate list, so a
     * hostname that has been filtered away does not cost a read timeout on each request.
     */
    @Volatile
    private var lastWorkingBase: String? = null

    private val apiDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val pinned = hostPins[hostname.lowercase()]
            if (pinned != null) {
                return listOf(InetAddress.getByName(pinned))
            }
            try {
                val systemAddresses = Dns.SYSTEM.lookup(hostname)
                // Iranian DPI DNS poisoning typically injects 10.10.34.34, 10.10.34.35 or loopbacks
                val isPoisoned = systemAddresses.any {
                    val addr = it.hostAddress ?: ""
                    addr.startsWith("10.10.34.") || it.isLoopbackAddress
                }
                if (!isPoisoned && systemAddresses.isNotEmpty()) {
                    return systemAddresses
                }
            } catch (_: Exception) {
            }
            // Fallback to secure DoH lookup against neutral resolvers (1.1.1.1 / 8.8.8.8)
            val dohAddresses = resolveViaDoH(hostname)
            if (dohAddresses.isNotEmpty()) {
                return dohAddresses
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun resolveViaDoH(hostname: String): List<InetAddress> {
        for (resolver in DOH_RESOLVERS) {
            try {
                val request = Request.Builder()
                    .url("$resolver?name=$hostname&type=A")
                    .header("accept", "application/dns-json")
                    .get()
                    .build()
                val ips = bootstrapClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<InetAddress>()
                    val body = response.body?.string().orEmpty()
                    val answers = if (body.isBlank()) null else JSONObject(body).optJSONArray("Answer")
                    if (answers == null) {
                        emptyList()
                    } else {
                        (0 until answers.length())
                            .mapNotNull { i -> answers.optJSONObject(i)?.optString("data") }
                            .filter { ip -> !ip.startsWith("10.10.34.") && !ip.startsWith("127.") }
                            .mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
                    }
                }
                if (ips.isNotEmpty()) return ips
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    /**
     * Refreshes the entry-point list from the out-of-band bootstrap channel. The pointer lives in
     * a DNS TXT record (`_ptu-bootstrap.ptu.beauty`); the payload is mirrored on hosts we control.
     * Only used when the normal control channel is unreachable, so a blocked API or hostname
     * cannot leave the app with no way back in.
     */
    suspend fun refreshBootstrap(): Boolean = withContext(Dispatchers.IO) {
        val candidates = (BOOTSTRAP_MIRRORS + baseUrls).distinct()
        for (url in candidates) {
            if (fetchBootstrapDocument(url)) return@withContext true
        }
        false
    }

    /**
     * The same document, found through the out-of-band pointer: the DNS TXT record
     * `_ptu-bootstrap.ptu.beauty` holds `v=1;b=<url>;b=<url>`.
     *
     * It is read over DoH against the resolver's own address, so it still works when the local
     * resolver is poisoned or every name we control is unqueryable — the case that leaves the app
     * with no entry points at all. Returns false when nothing usable landed, including when the
     * resolver itself is unreachable.
     */
    suspend fun refreshBootstrapFromDns(): Boolean = withContext(Dispatchers.IO) {
        for (url in bootstrapPointers()) {
            if (fetchBootstrapDocument(url)) return@withContext true
        }
        false
    }

    private fun fetchBootstrapDocument(url: String): Boolean = try {
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use false
            applyBootstrap(JSONObject(body))
        }
    } catch (_: Exception) {
        false
    }

    /** The `b=` entries of the bootstrap TXT record, resolved over DoH. */
    private fun bootstrapPointers(): List<String> {
        for (resolver in DOH_RESOLVERS) {
            val pointers: List<String> = try {
                val request = Request.Builder()
                    .url("$resolver?name=$BOOTSTRAP_TXT_NAME&type=TXT")
                    .header("accept", "application/dns-json")
                    .get()
                    .build()
                val parsed: List<String> = bootstrapClient.newCall(request).execute().use { response ->
                    val body = if (response.isSuccessful) response.body?.string().orEmpty() else ""
                    val answers = if (body.isBlank()) null else JSONObject(body).optJSONArray("Answer")
                    if (answers == null) {
                        emptyList()
                    } else {
                        (0 until answers.length())
                            .mapNotNull { index -> answers.optJSONObject(index)?.optString("data") }
                            .flatMap { data -> data.trim('"').split(';') }
                            .mapNotNull { entry -> entry.trim().removePrefix("b=") }
                            .filter { it.startsWith("https://") }
                            .distinct()
                    }
                }
                parsed
            } catch (_: Exception) {
                // resolver unreachable; try the next one
                emptyList()
            }
            if (pointers.isNotEmpty()) return pointers
        }
        return emptyList()
    }

    /**
     * The addresses the recovery ladder should measure as "our own server".
     *
     * These follow the bootstrap payload rather than the address the APK was built with, so a
     * rotated server IP is probed instead of being read as a permanent block.
     */
    fun serverProbeHosts(): List<String> = hostPins.values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Merges a bootstrap document into the live entry-point list.
     * Returns true only when a usable endpoint list landed, so a mirror that answers with
     * something useless does not stop the search.
     */
    internal fun applyBootstrap(root: JSONObject): Boolean {
        val api = root.optJSONArray("api") ?: return false
        val newBases = (0 until api.length())
            .mapNotNull { api.optString(it).takeIf { s -> s.isNotBlank() } }
        if (newBases.isEmpty()) return false

        val mergedPins = LinkedHashMap(API_HOST_PINS)
        root.optJSONObject("pins")?.let { pins ->
            pins.keys().forEach { key ->
                val value = pins.optString(key)
                if (value.isNotBlank()) mergedPins[key.lowercase()] = value
            }
        }
        val mirrors = root.optJSONArray("mirrors")
        val newMirrors = if (mirrors != null) {
            (0 until mirrors.length())
                .mapNotNull { mirrors.optString(it).takeIf { s -> s.isNotBlank() } }
        } else {
            emptyList()
        }

        baseUrls = (newBases + newMirrors).distinct()
        hostPins = mergedPins
        return true
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(apiDns)
        // 3 s, not 8 s: a candidate that is blackholed must not own the failover budget. Measured —
        // with 8 s, walking three dead hosts before the reachable one took 24 s of every reconnect,
        // and a coroutine timeout around a blocking execute() cannot cut it short. A healthy endpoint
        // accepts in well under a second, so 3 s only ever fires on a path we were going to skip.
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    // Separate client for the signed APK download: long reads, same pinned DNS.
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .dns(apiDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * The same client, pointed at the HTTP proxy the core listens for on loopback
     * (`loopback-proxy` in the fetched profile), so bytes leave through the tunnel instead of through
     * the shaped path to our own address. Mirrors `LOOPBACK_PROXY_PORT` in the profile builder.
     */
    private val tunnelDownloadClient: OkHttpClient = downloadClient.newBuilder()
        .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(LOOPBACK_PROXY_HOST, LOOPBACK_PROXY_PORT)))
        .build()

    /**
     * Out-of-band lookups. Every address here is an IP literal, so this client is deliberately
     * not pinned and stays short-tempered: it is only ever used on the failure path.
     */
    private val bootstrapClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    /**
     * Tells a broken local network apart from a blocked server.
     *
     * A device can fail to connect for its own reasons, and blaming the server for that would
     * push users onto a slower fallback path for nothing. So every probe is paired with a control
     * probe to an unrelated host: if that one also fails, the device's own connectivity is the
     * problem and the server is not implicated at all.
     *
     * The probe targets are parameters so the decision table can be tested without a device.
     */
    suspend fun classifyReachability(
        serverHost: String = SERVER_PROBE_HOST,
        serverPort: Int = SERVER_PROBE_PORT,
        neutralHost: String = NEUTRAL_PROBE_HOST,
        neutralPort: Int = NEUTRAL_PROBE_PORT
    ): String = withContext(Dispatchers.IO) {
        if (!tcpProbe(neutralHost, neutralPort)) {
            return@withContext REACHABILITY_DEVICE_NETWORK
        }
        if (tcpProbe(serverHost, serverPort)) {
            REACHABILITY_TUNNEL_FAILED
        } else {
            REACHABILITY_SERVER_UNREACHABLE
        }
    }

    /**
     * The fleet's own verdict, published out of band. A single device classifying the server as
     * unreachable is not enough to act on; this is the corroborated answer when it is reachable.
     */
    suspend fun fetchFleetVerdict(): String? = withContext(Dispatchers.IO) {
        for (url in STATUS_URLS) {
            try {
                val request = Request.Builder().url(url).get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val verdict = JSONObject(body).optString("verdict")
                    if (verdict.isNotBlank()) return@withContext verdict
                }
            } catch (_: Exception) {
                // next mirror
            }
        }
        null
    }

    /** Scored Cloudflare edge addresses for the fallback path, best first. */
    suspend fun scoredCdnAddresses(): List<String> = withContext(Dispatchers.IO) {
        for (url in CDN_IP_URLS) {
            try {
                val request = Request.Builder().url(url).get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val ips = JSONObject(body).optJSONArray("ips") ?: return@use
                    val list = (0 until ips.length()).mapNotNull { index ->
                        ips.optJSONObject(index)?.optString("ip")?.takeIf { it.isNotBlank() }
                    }
                    if (list.isNotEmpty()) return@withContext list
                }
            } catch (_: Exception) {
                // next mirror
            }
        }
        FALLBACK_CDN_EDGES
    }

    /**
     * Dials one specific Cloudflare edge address while keeping the hostname that both the edge and
     * our origin expect. This is how a throttled edge range gets skipped without the app knowing
     * anything about the network topology.
     */
    fun withCdnAddress(profileJson: String, edgeIp: String): String = try {
        val root = JSONObject(profileJson)
        val outbounds = root.optJSONArray("outbounds")
        var changed = false
        if (outbounds != null) {
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                if (outbound.optString("tag") != "cdn") continue
                val node = outbound.optJSONObject("settings")
                    ?.optJSONArray("vnext")
                    ?.optJSONObject(0) ?: continue
                node.put("address", edgeIp)
                changed = true
            }
        }
        if (changed) root.toString() else profileJson
    } catch (_: Exception) {
        profileJson
    }

    /** Best effort. A report only counts when a client can actually reach the server. */
    suspend fun reportReachability(verdict: String): Boolean = withContext(Dispatchers.IO) {
        for (base in baseUrls) {
            try {
                val response = buildApi(base).reportReachability(
                    ReachabilityReportDto(
                        verdict = verdict,
                        port = SERVER_PROBE_PORT,
                        // `base` is the box this report is being written through, so it is the box
                        // that has to be credited. `lastWorkingBase` would name the previous one
                        // and quietly blame the wrong box on the very first report of a session.
                        box = endpointHost(base) ?: ""
                    )
                )
                if (response.isSuccessful) {
                    // Remember it: a control-channel call that worked is the endpoint worth trying first.
                    lastWorkingBase = base
                    return@withContext true
                }
            } catch (_: Exception) {
                // try the next entry point
            }
        }
        false
    }

    private fun tcpProbe(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Milliseconds for a complete HTTPS round trip to a box, or -1 when it never answered.
     *
     * This is what the country ranking must use, and a TCP handshake is not it: measured on the
     * operator's own line, the second box accepts the connection and then its TLS handshake dies in
     * the middle — `TLS handshake error from …: i/o timeout` in the box's log — while a SYN probe
     * still returns 83 ms and calls that box the fastest one available. Ranking by handshake cost
     * would move the user onto a server that cannot build a tunnel at all.
     *
     * `/s` is a few hundred bytes of JSON that every box serves, so timing it proves DNS, TCP, TLS and
     * HTTP in one small request. A 404 still counts: what the tunnel needs is a completed handshake,
     * not a particular document.
     */
    suspend fun measureBoxRoundTripMs(url: String): Int = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext -1
        val startedAt = System.nanoTime()
        try {
            boxProbeClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.code in 200..499) {
                    ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
                } else {
                    -1
                }
            }
        } catch (_: Exception) {
            -1
        }
    }

    /** The probe client shares OkHttp's connection pool but carries its own, shorter leash. */
    private val boxProbeClient by lazy {
        okHttpClient.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    /**
     * The URL that answers the "can this box finish a handshake" question.
     *
     * It has to be the name the box actually serves its API on. Reading the location's `domain`
     * instead was a real bug: Turkey publishes `api_base = …nip.io/v1/` while its `domain` still
     * says `…sslip.io`, so the probe measured the name this class of line is known to drop and
     * reported a healthy box as unreachable. `/s` is asked for at the origin, not under `/v1/`.
     */
    fun boxProbeUrl(location: VpnLocation): String {
        val origin = location.apiBase
            .substringBefore("/v1", "")
            .trimEnd('/')
            .ifBlank { "https://${location.domain.ifBlank { location.host }.trim()}" }
        return "$origin/s"
    }

    /**
     * Makes an explicitly chosen box the first candidate for the next calls.
     *
     * Without this the remembered endpoint outranks the choice: a background profile refresh against
     * one box would silently keep the tunnel on that box even after the user picked another country,
     * and the UI would then name a country that is not carrying the traffic.
     */
    fun preferEndpoint(baseUrl: String?) {
        if (!baseUrl.isNullOrBlank()) lastWorkingBase = baseUrl
    }

    /** The endpoint that actually served the last successful call, so the UI can tell the truth. */
    val servedEndpoint: String?
        get() = lastWorkingBase

    private fun candidateBaseUrls(config: BackendEndpointConfig): List<String> =
        PathLadder.orderedEndpoints(lastWorkingBase, config.apiBaseUrl, baseUrls)

    /**
     * Runs [call] against every known endpoint in order and returns the first success.
     * Explicit server answers (4xx) stop the loop immediately; transport errors and 5xx
     * move on to the next endpoint.
     */
    private suspend fun <T> withFailover(
        config: BackendEndpointConfig,
        call: suspend (ShieldBackendApi) -> Result<T>
    ): Result<T> {
        var lastError: Throwable? = null
        for (base in candidateBaseUrls(config)) {
            val result = call(buildApi(base))
            if (result.isSuccess) {
                lastWorkingBase = base
                return result
            }
            val error = result.exceptionOrNull()
            if (error is DefinitiveFailure) return result
            // The remembered good endpoint went bad mid-session; stop putting it first.
            if (base == lastWorkingBase) lastWorkingBase = null
            lastError = error
        }
        return Result.failure(lastError ?: IllegalStateException("All backend endpoints are unreachable"))
    }

    private fun buildApi(baseUrl: String): ShieldBackendApi {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val validUrl = if (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            normalizedUrl
        } else {
            "https://$normalizedUrl"
        }
        return Retrofit.Builder()
            .baseUrl(validUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ShieldBackendApi::class.java)
    }

    /**
     * Strictly authenticates the user against the remote backend (`POST auth/login`).
     * Never allows login unless the server responds with HTTP 2xx and a valid body/token.
     */
    suspend fun loginWithServer(
        config: BackendEndpointConfig,
        email: String,
        password: String
    ): Result<Pair<UserProfile, List<VpnServer>>> = withContext(Dispatchers.IO) {
        withFailover(config) { api ->
            try {
                val response = api.login(LoginRequestDto(username = email.trim(), password = password))
                if (response.isSuccessful) {
                    val dto = response.body()
                    if (dto != null && dto.token.isNotBlank() && dto.uid.isNotBlank()) {
                        val servers = serversFromDto(dto, config)
                        return@withFailover Result.success(dto.toUserProfile() to servers)
                    }
                    return@withFailover Result.failure(
                        DefinitiveFailure("Server returned an invalid authentication token.")
                    )
                }
                if (response.code() in 400..499) {
                    Result.failure(DefinitiveFailure("Server rejected login (HTTP ${response.code()})."))
                } else {
                    Result.failure(IllegalStateException("Server rejected login (HTTP ${response.code()})."))
                }
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException(
                        "Cannot reach verification server (${config.apiBaseUrl}): ${e.localizedMessage ?: "Connection refused"}"
                    )
                )
            }
        }
    }

    /**
     * Strictly registers the user profile on the remote backend (`POST auth/register`).
     * Never allows entry unless verified and created by the server.
     */
    suspend fun registerProfileWithServer(
        config: BackendEndpointConfig,
        displayName: String,
        email: String,
        password: String
    ): Result<Pair<UserProfile, List<VpnServer>>> = withContext(Dispatchers.IO) {
        try {
            val api = buildApi(config.apiBaseUrl)
            val response = api.registerProfile(
                RegisterProfileRequestDto(
                    displayName = displayName.trim(),
                    email = email.trim(),
                    password = password
                )
            )
            if (response.isSuccessful) {
                val dto = response.body()
                if (dto != null && dto.token.isNotBlank() && dto.uid.isNotBlank()) {
                    val servers = serversFromDto(dto, config)
                    return@withContext Result.success(dto.toUserProfile() to servers)
                } else {
                    return@withContext Result.failure(
                        IllegalStateException("Server returned an invalid profile response.")
                    )
                }
            } else {
                return@withContext Result.failure(
                    IllegalStateException("Server rejected profile creation (HTTP ${response.code()}).")
                )
            }
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Cannot reach verification server (${config.apiBaseUrl}): ${e.localizedMessage ?: "Connection refused"}"
                )
            )
        }
    }

    /**
     * Strictly updates the user's profile on the backend (`PUT user/profile`).
     * Only updates state if confirmed by the server.
     */
    suspend fun updateProfileOnServer(
        config: BackendEndpointConfig,
        currentProfile: UserProfile,
        newDisplayName: String,
        newEmail: String
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val api = buildApi(config.apiBaseUrl)
            val response = api.updateUserProfile(
                bearerToken = "Bearer ${currentProfile.authToken}",
                request = UpdateProfileRequestDto(
                    displayName = newDisplayName.trim(),
                    email = newEmail.trim()
                )
            )
            if (response.isSuccessful && response.body() != null) {
                return@withContext Result.success(response.body()!!.toUserProfile())
            } else {
                return@withContext Result.failure(
                    IllegalStateException("Server rejected profile update (HTTP ${response.code()}).")
                )
            }
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Server verification failed (${config.apiBaseUrl}): ${e.localizedMessage ?: "Unreachable"}"
                )
            )
        }
    }

    /**
     * Fetches the latest User Profile, Package Quota, and Subscription Nodes from the server (`GET user/profile`).
     */
    suspend fun fetchProfileAndNodesFromServer(
        config: BackendEndpointConfig,
        currentProfile: UserProfile
    ): Result<Pair<UserProfile, List<VpnServer>>> = withContext(Dispatchers.IO) {
        withFailover(config) { api ->
            try {
                val response = api.fetchUserProfile(bearerToken = "Bearer ${currentProfile.authToken}")
                if (response.isSuccessful && response.body() != null) {
                    val dto = response.body()!!
                    val servers = serversFromDto(dto, config)
                    return@withFailover Result.success(dto.toUserProfile() to servers)
                }
                Result.failure(failureForRefreshCode(response.code()))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Syncs consumed VPN traffic with the server (`POST user/traffic-sync`).
     */
    suspend fun syncTrafficWithServer(
        config: BackendEndpointConfig,
        profile: UserProfile,
        sessionDeltaBytes: Long,
        activeServer: VpnServer?
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val api = buildApi(config.apiBaseUrl)
            val response = api.syncTrafficUsage(
                bearerToken = "Bearer ${profile.authToken}",
                request = TrafficSyncRequestDto(
                    uid = profile.uid,
                    consumedSessionBytes = sessionDeltaBytes,
                    totalUsedBytes = profile.usedBytes,
                    activeNodeHost = activeServer?.host ?: "",
                    activeNodePort = activeServer?.port ?: config.defaultPort
                )
            )
            if (response.isSuccessful && response.body() != null) {
                return@withContext Result.success(response.body()!!.toUserProfile())
            }
            return@withContext Result.failure(
                IllegalStateException("Traffic sync failed: HTTP ${response.code()}")
            )
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun fetchSubscriptionUrlIfHttp(
        rawUrl: String
    ): Pair<List<VpnServer>, ParsedQuotaInfo?> = withContext(Dispatchers.IO) {
        try {
            val api = buildApi("https://localhost/")
            val response = api.downloadSubscriptionUrl(rawUrl.trim())
            if (response.isSuccessful) {
                val bodyText = response.body()?.string().orEmpty()
                val servers = VpnUriParser.parseClipboardPayload(bodyText)
                val userInfoHeader = response.headers()["Subscription-Userinfo"]
                    ?: response.headers()["subscription-userinfo"]
                val quotaInfo = parseSubscriptionUserInfoHeader(userInfoHeader)
                    ?: VpnUriParser.extractQuotaFromPayload(bodyText)
                if (servers.isNotEmpty()) {
                    return@withContext servers to quotaInfo
                }
            }
        } catch (_: Exception) {
        }
        emptyList<VpnServer>() to null
    }

    private fun parseSubscriptionUserInfoHeader(header: String?): ParsedQuotaInfo? {
        if (header.isNullOrBlank()) return null
        return try {
            val map = header.split(";").associate { part ->
                val k = part.substringBefore("=").trim().lowercase()
                val v = part.substringAfter("=", "0").trim().toLongOrNull() ?: 0L
                k to v
            }
            val upload = map["upload"] ?: 0L
            val download = map["download"] ?: 0L
            val total = map["total"] ?: 0L
            if (total > 0L) ParsedQuotaInfo(usedBytes = upload + download, totalBytes = total) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun ServerAuthResponse.toUserProfile(): UserProfile = UserProfile(
        uid = uid,
        displayName = displayName,
        email = username.ifBlank { email },
        authToken = token,
        planTitle = planTitle,
        usedBytes = usedBytes,
        totalBytes = totalBytes,
        expiryDaysRemaining = expiryDaysRemaining,
        username = username,
        status = status,
        expiresAt = expiresAt
    )

    private fun serversFromDto(
        dto: ServerAuthResponse,
        fallbackConfig: BackendEndpointConfig
    ): List<VpnServer> {
        if (dto.servers.isNotEmpty()) {
            return dto.servers.map { node ->
                val tag = node.tag.ifBlank { node.id }
                VpnServer(
                    id = tag,
                    flagEmoji = node.flag.ifBlank { "🌐" },
                    name = node.name.ifBlank { node.protocol.uppercase() },
                    host = node.host,
                    port = node.port,
                    protocolScheme = node.protocol,
                    credential = "",
                    security = node.security,
                    transport = node.transport,
                    sni = node.host,
                    rawUri = tag,
                    pingMs = 0
                )
            }
        }

        return dto.subscriptionPayload
            ?.let { VpnUriParser.parseClipboardPayload(it) }
            .orEmpty()
    }

    suspend fun fetchLocations(
        config: BackendEndpointConfig
    ): Result<List<VpnLocation>> = withContext(Dispatchers.IO) {
        // Same reasoning as fetchLatestApp: reading the server list is a GET, and an empty list is
        // what a user sees as "no servers" even when the service is healthy behind another name.
        withFailover(config) { api ->
            try {
                val response = api.fetchLocations()
                if (!response.isSuccessful) {
                    return@withFailover Result.failure(
                        IllegalStateException("Unable to load locations (HTTP ${response.code()}).")
                    )
                }
                val locations = response.body()?.locations.orEmpty().mapNotNull { dto ->
                    if (dto.id.isBlank()) return@mapNotNull null
                    VpnLocation(
                        id = dto.id,
                        name = dto.name.ifBlank { dto.country },
                        country = dto.country.ifBlank { dto.name },
                        host = dto.host,
                        domain = dto.domain,
                        flag = dto.flag,
                        apiBase = dto.apiBase
                    )
                }
                Result.success(locations)
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException("Cannot load locations: ${e.localizedMessage ?: "network error"}")
                )
            }
        }
    }

    suspend fun fetchLatestApp(
        config: BackendEndpointConfig
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        // A GET, so the endpoint list is safe to walk here: a user whose first hostname is filtered
        // must still be told a release exists, and this is the call that decides that.
        withFailover(config) { api ->
            try {
                val response = api.fetchLatestApp()
                if (response.code() == 404) {
                    return@withFailover Result.success(null)
                }
                if (!response.isSuccessful) {
                    return@withFailover Result.failure(
                        IllegalStateException("Update check failed (HTTP ${response.code()}).")
                    )
                }
                val dto = response.body()
                    ?: return@withFailover Result.failure(IllegalStateException("Empty update response."))
                if (dto.versionCode <= 0L || dto.url.isBlank()) {
                    return@withFailover Result.failure(IllegalStateException("Invalid update metadata."))
                }
                // Fail closed: an offer we cannot verify is an offer we do not make. Older builds
                // ignore the field entirely, so this only ever tightens what this build accepts.
                val signed = try {
                    dto.sig.isNotBlank() && UpdateMetadata.verify(
                        UpdateMetadata.payload(dto.url, dto.sha256, dto.versionCode),
                        android.util.Base64.decode(dto.sig, android.util.Base64.DEFAULT),
                        android.util.Base64.decode(
                            UpdateMetadata.PUBLIC_KEY_B64,
                            android.util.Base64.DEFAULT
                        )
                    )
                } catch (_: Throwable) {
                    false
                }
                if (!signed) {
                    return@withFailover Result.failure(
                        IllegalStateException("Update metadata is unsigned or its signature does not match.")
                    )
                }
                Result.success(
                    AppUpdateInfo(
                        versionCode = dto.versionCode,
                        versionName = dto.versionName,
                        url = dto.url,
                        sha256 = dto.sha256,
                        sizeBytes = dto.sizeBytes,
                        minVersionCode = dto.minVersionCode,
                        mandatory = dto.mandatory,
                        notes = dto.notes,
                        publishedAt = dto.publishedAt
                    )
                )
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Cannot check for updates: ${e.localizedMessage ?: "network error"}"))
            }
        }
    }

    /**
     * Pulls an update through the tunnel when one is up.
     *
     * Measured on an Iranian line with the 39 MB APK: 3674 B/s on the direct path — the app is absent
     * from its own TUN, so this is the shaped TCP road to our address — against 2.79 MB/s through the
     * finished tunnel (8 MB in 3.0 s). Three hours against fourteen seconds decides this: an update that
     * cannot be fetched is also the fix that cannot be delivered, and the 0.62 recovery rung for
     * UDP-blocking lines was exactly such an update. If the core has no loopback proxy (an older
     * profile), the direct path is still tried, so the tunnel can never make updating impossible.
     */
    suspend fun downloadApk(
        url: String,
        destination: File,
        expectedSha256: String,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
        viaTunnel: Boolean = false
    ): Result<File> {
        // Chunked on both roads: the same per-flow shaping was measured outside the tunnel as well
        // (one 4 MiB stream direct: 952 B/s), so the shape matters wherever the line is.
        if (!viaTunnel) {
            return downloadChunked(downloadClient, url, destination, expectedSha256, expectedSize, onProgress)
        }
        val throughTunnel =
            downloadChunked(tunnelDownloadClient, url, destination, expectedSha256, expectedSize, onProgress)
        return throughTunnel.fold(
            onSuccess = { throughTunnel },
            onFailure = { error ->
                Log.w(TAG, "Update through the tunnel failed (${error.message}); falling back to direct")
                downloadChunked(downloadClient, url, destination, expectedSha256, expectedSize, onProgress)
            }
        )
    }

    /**
     * Pulls the APK as a sequence of short ranged requests rather than one long stream.
     *
     * Measured on an Iranian line, over the tunnel, with the profile a phone actually fetches, on 2026-11-04:
     * a single 4 MiB stream delivered 425 KB in 55 s (7.7 KB/s) and never finished, while the same 4 MiB as
     * eight sequential 512 KiB ranges completed in 6.7 s (629 KB/s) — eighty times faster. The shaping is
     * applied per sustained flow: a fresh connection is allowed to run for its first megabyte, so the
     * *shape* of the download beats any retry logic bolted onto a stream that is already being throttled.
     * Eight parallel ranges were worse (6.4 KB/s aggregate), so parallelism is deliberately not used.
     * At this rate the 39 MB package finishes in about a minute instead of never, which is the difference
     * between a fleet that receives these fixes and one that stays on a stale profile.
     */
    private suspend fun downloadChunked(
        client: OkHttpClient,
        url: String,
        destination: File,
        expectedSha256: String,
        expectedSize: Long,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (expectedSize <= 0L) {
            return@withContext downloadWith(client, url, destination, expectedSha256, expectedSize, onProgress)
        }
        // Two bytes is enough to learn whether this server answers Range at all.
        val rangeAnswered = try {
            client.newCall(
                Request.Builder().url(url).header("Range", "bytes=0-1").build()
            ).execute().use { it.code == 206 }
        } catch (e: Exception) {
            false
        }
        if (!rangeAnswered) {
            return@withContext downloadWith(client, url, destination, expectedSha256, expectedSize, onProgress)
        }

        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".part")
        if (temp.exists()) temp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var lastPercent = -1
        try {
            java.io.FileOutputStream(temp, true).use { output ->
                while (written < expectedSize) {
                    val from = written
                    val to = minOf(written + UPDATE_CHUNK_BYTES - 1L, expectedSize - 1L)
                    var attempt = 0
                    var chunkBytes = 0L
                    while (chunkBytes == 0L) {
                        attempt += 1
                        val request = Request.Builder().url(url)
                            .header("Range", "bytes=$from-$to")
                            .build()
                        try {
                            client.newCall(request).execute().use { response ->
                                if (response.code == 206) {
                                    val body = response.body
                                    if (body != null) {
                                        body.byteStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                val read = input.read(buffer)
                                                if (read < 0) break
                                                output.write(buffer, 0, read)
                                                digest.update(buffer, 0, read)
                                                chunkBytes += read
                                            }
                                        }
                                    }
                                } else if (attempt >= CHUNK_ATTEMPTS) {
                                    return@withContext Result.failure(
                                        IllegalStateException("Update download failed (HTTP ${response.code}).")
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (attempt >= CHUNK_ATTEMPTS) {
                                return@withContext Result.failure(
                                    IllegalStateException(
                                        "Update download failed: ${e.localizedMessage ?: "network error"}"
                                    )
                                )
                            }
                        }
                        if (chunkBytes == 0L) {
                            if (attempt >= CHUNK_ATTEMPTS) {
                                return@withContext Result.failure(
                                    IllegalStateException("Update stalled at byte $from of $expectedSize.")
                                )
                            }
                            kotlinx.coroutines.delay(400L)
                        }
                    }
                    written += chunkBytes
                    val percent = ((written * 100L) / expectedSize).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (expectedSha256.isNotBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                temp.delete()
                return@withContext Result.failure(IllegalStateException("Update failed integrity check."))
            }
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.delete()
                return@withContext Result.failure(IllegalStateException("Could not save the update file."))
            }
            Result.success(destination)
        } catch (e: Exception) {
            temp.delete()
            Result.failure(IllegalStateException("Update download failed: ${e.localizedMessage ?: "network error"}"))
        }
    }

    private suspend fun downloadWith(
        client: OkHttpClient,
        url: String,
        destination: File,
        expectedSha256: String,
        expectedSize: Long,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Update download failed (HTTP ${response.code}).")
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("Empty update download."))
                val totalBytes = if (body.contentLength() > 0L) body.contentLength() else expectedSize
                destination.parentFile?.mkdirs()
                val temp = File(destination.parentFile, destination.name + ".part")
                tempFile = temp
                var readBytes = 0L
                var lastPercent = -1
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            readBytes += read
                            if (totalBytes > 0L) {
                                val percent = ((readBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                if (expectedSha256.isNotBlank() && !actualSha.equals(expectedSha256, ignoreCase = true)) {
                    temp.delete()
                    return@withContext Result.failure(IllegalStateException("Update failed integrity check."))
                }
                if (expectedSize > 0L && readBytes != expectedSize) {
                    temp.delete()
                    return@withContext Result.failure(IllegalStateException("Update has an unexpected size."))
                }
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) {
                    temp.delete()
                    return@withContext Result.failure(IllegalStateException("Could not save the update file."))
                }
                Result.success(destination)
            }
        } catch (e: Exception) {
            tempFile?.delete()
            Result.failure(IllegalStateException("Update download failed: ${e.localizedMessage ?: "network error"}"))
        }
    }

    suspend fun fetchXrayConfig(
        config: BackendEndpointConfig,
        profile: UserProfile,
        tag: String
    ): Result<String> = withContext(Dispatchers.IO) {
        withFailover(config) { api ->
            try {
                val response = api.fetchXrayConfig("Bearer ${profile.authToken}", tag)
                if (response.isSuccessful) {
                    val bodyText = response.body()?.string().orEmpty()
                    val root = JSONObject(bodyText)
                    val configObject = root.optJSONObject("config")
                    if (configObject != null) {
                        return@withFailover Result.success(configObject.toString())
                    }
                    return@withFailover Result.failure(
                        IllegalStateException("Server returned an invalid VPN configuration.")
                    )
                }
                if (response.code() == 401) {
                    Result.failure(SessionExpired(response.code()))
                } else if (response.code() in 400..499) {
                    Result.failure(DefinitiveFailure("Unable to load VPN profile (HTTP ${response.code()})."))
                } else {
                    Result.failure(IllegalStateException("Unable to load VPN profile (HTTP ${response.code()})."))
                }
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException("Cannot load VPN profile: ${e.localizedMessage ?: "network error"}")
                )
            }
        }
    }

    private companion object {
        private const val TAG = "VpnApi"

        /** The loopback HTTP proxy the core serves from the fetched profile. */
        private const val LOOPBACK_PROXY_HOST = "127.0.0.1"
        private const val LOOPBACK_PROXY_PORT = 38104

        /**
         * Size of one ranged pull of the APK. Chosen by measurement, not taste: on an Iranian line a fresh
         * 512 KiB range runs at 0.5–1.2 MB/s while a single long stream is cut to 7.7 KB/s after its first
         * megabyte, so the chunk is small enough to stay inside the un-throttled burst of each connection.
         */
        private const val UPDATE_CHUNK_BYTES = 512L * 1024L
        private const val CHUNK_ATTEMPTS = 3

        /** Hostname → server IP. Every entry point is pinned, because local DNS answers lie. */
        private val API_HOST_PINS = mapOf(
            "ptu.beauty" to "86.54.42.124",
            "www.ptu.beauty" to "86.54.42.124",
            "86-54-42-124.sslip.io" to "86.54.42.124",
            "86-54-42-124.nip.io" to "86.54.42.124",
            // Second address, second country: a detour that terminates TLS and relays to the origin.
            "206-1-101-67.sslip.io" to "206.1.101.67",
            "206-1-101-67.nip.io" to "206.1.101.67"
        )

        /**
         * Ordered control-channel entry points. The first reachable one wins.
         *
         * Ordered by measured reachability rather than by how the name reads: filtering is by SNI
         * suffix, so on a measured line every `*.ptu.beauty` handshake is dropped while both
         * wildcard-DNS names complete — they go first. The last two are a different IP in a
         * different country, insurance against the address itself being blocked, and they stay last
         * because an address that is blackholed from this line would otherwise cost a full connect
         * timeout on every call. One name is one point of failure, and signing in is the one thing a
         * user must always be able to do.
         */
        private val BOOTSTRAP_BASE_URLS = listOf(
            "https://86-54-42-124.nip.io/v1/",
            "https://86-54-42-124.sslip.io/v1/",
            "https://ptu.beauty/v1/",
            "https://206-1-101-67.nip.io/v1/",
            "https://206-1-101-67.sslip.io/v1/"
        )

        /**
         * Out-of-band mirrors of the endpoint list, reachable independently of the API. The
         * matching DNS TXT record is `_ptu-bootstrap.ptu.beauty`.
         */
        private val BOOTSTRAP_MIRRORS = listOf(
            "https://86-54-42-124.nip.io/b",
            "https://86-54-42-124.sslip.io/b",
            "https://ptu.beauty/b",
            "https://206-1-101-67.nip.io/b",
            "https://206-1-101-67.sslip.io/b"
        )

        private val STATUS_URLS = listOf(
            "https://86-54-42-124.nip.io/s",
            "https://ptu.beauty/s",
            "https://86-54-42-124.sslip.io/s",
            "https://206-1-101-67.nip.io/s"
        )

        private val CDN_IP_URLS = listOf(
            "https://86-54-42-124.nip.io/c",
            "https://ptu.beauty/c",
            "https://86-54-42-124.sslip.io/c",
            "https://206-1-101-67.nip.io/c"
        )

        /**
         * Cloudflare edges to fall back on when the scored list cannot be fetched.
         *
         * `/c` is served by our own address, so in the middle of a block it is exactly what is
         * unreachable. Without a known IP the CDN profile would carry a hostname that can only be
         * resolved through a tunnel that is not up yet, and the rung would fail by construction.
         */
        private val FALLBACK_CDN_EDGES = listOf("188.114.96.3", "188.114.97.3", "104.17.2.81")

        /** The out-of-band pointer to the bootstrap document, published as a DNS TXT record. */
        private const val BOOTSTRAP_TXT_NAME = "_ptu-bootstrap.ptu.beauty"

        /** Resolvers for the TXT lookup, addressed by IP so a poisoned local DNS cannot interfere. */
        private val DOH_RESOLVERS = listOf(
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query"
        )

        private const val SERVER_PROBE_HOST = "86.54.42.124"
        private const val SERVER_PROBE_PORT = 443
        private const val NEUTRAL_PROBE_HOST = "1.1.1.1"
        private const val NEUTRAL_PROBE_PORT = 443
        private const val PROBE_TIMEOUT_MS = 4_000
    }
}
