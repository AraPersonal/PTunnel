package com.example.vpn

import android.content.Context
import android.util.Log
import com.example.model.BackendEndpointConfig
import com.example.model.LocationMode
import com.example.model.UserProfile
import com.example.model.VpnLocation
import com.example.network.PathLadder
import com.example.network.PathProbe
import com.example.network.REACHABILITY_TUNNEL_FAILED
import com.example.network.RecoveryStep
import com.example.network.VpnBackendRepository
import com.example.network.shouldFallBackToCdn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What the service should do after a recovery step has been prepared. */
sealed interface RecoveryOutcome {
    /**
     * A profile worth dialling. [detour] marks a profile that reaches the internet by a route other
     * than our own address, which is what makes it worth leaving again later.
     */
    data class Dial(val config: String, val detour: Boolean = false) : RecoveryOutcome

    /** The device itself is offline; hold still instead of burning attempts on a dead radio. */
    data object WaitForNetwork : RecoveryOutcome

    /** Nothing left to try. */
    data class GiveUp(val reason: String) : RecoveryOutcome
}

/**
 * Walks the recovery ladder when a verified tunnel dies mid-session.
 *
 * It lives with the service rather than in the UI on purpose: a foreground VPN outlives the
 * activity, so recovery has to keep working after the user swipes the app away. Everything it
 * needs — auth token, cached profiles, backend endpoints — it reads from the same session
 * preferences the ViewModel writes.
 */
class TunnelRecovery(context: Context) {

    private val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    private val repository = VpnBackendRepository()

    /** Decided once per episode, on the first attempt that has to go out to the network. */
    private var plan: List<RecoveryStep>? = null

    /** True when none of our own ports answered, which also means the API behind them is gone. */
    private var serverUnreachable = false

    /** The out-of-band endpoint list is worth asking for once per episode, not once per rung. */
    private var endpointListRefreshed = false

    /** Cloudflare edges in the order the parallel race found them, best first. */
    private var rankedEdges: List<String> = emptyList()

    /** Cloudflare edges already dialled in this episode, so a dead edge is not retried. */
    private val triedEdges = mutableListOf<String>()

    /** When we last moved the session onto another box, so one fast sample cannot start a flap. */
    private var lastPathSwitchAt = 0L

    /** The rung currently in flight, so a verified session can name the path that carried it. */
    private var rungInFlight: RecoveryStep? = null

    /** Starts a new episode; the next [next] call re-diagnoses from scratch. */
    fun reset() {
        plan = null
        serverUnreachable = false
        endpointListRefreshed = false
        rankedEdges = emptyList()
        triedEdges.clear()
        rungInFlight = null
    }

    /**
     * Called when the tunnel verified. The rung that produced it becomes this line's first choice after
     * the next drop, which is what stops every reconnect from climbing the ladder from zero again.
     */
    fun noteSessionVerified() {
        val rung = rungInFlight
        if (rung == null) {
            // The live profile carried it; there is nothing worth remembering beyond what is stored.
            return
        }
        prefs.edit()
            .putString(KEY_LAST_GOOD_RUNG, "${rung.name}:${System.currentTimeMillis()}")
            .apply()
        Log.i(TAG, "Remembered $rung as this line's first choice after a drop")
    }

    /** The remembered rung, or null when nothing was learned yet or the memory has gone stale. */
    private fun rememberedRung(): RecoveryStep? {
        val stored = prefs.getString(KEY_LAST_GOOD_RUNG, null).orEmpty()
        if (stored.isBlank()) return null
        val name = stored.substringBefore(':')
        val at = stored.substringAfter(':', "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - at > RUNG_MEMORY_MS) {
            prefs.edit().remove(KEY_LAST_GOOD_RUNG).apply()
            return null
        }
        return RecoveryStep.values().firstOrNull { it.name == name }
    }

    /**
     * The profile to dial for [attempt] (0-based). Attempt 0 always reuses the live profile so a
     * transient drop costs nothing; everything after that goes out to the network.
     */
    suspend fun next(liveConfig: String, attempt: Int): RecoveryOutcome {
        if (attempt <= 0) return RecoveryOutcome.Dial(liveConfig)

        val rawPlan = plan ?: diagnose()?.also { plan = it } ?: return RecoveryOutcome.WaitForNetwork
        val steps = PathLadder.planWithRememberedRung(rawPlan, rememberedRung())
        if (attempt >= steps.size) {
            // The whole ladder failed with the memory in front of it, so the memory is now known wrong.
            prefs.edit().remove(KEY_LAST_GOOD_RUNG).apply()
            plan = null
            return RecoveryOutcome.GiveUp("Connection lost. Tap connect to try again.")
        }
        val rung = steps[attempt]
        rungInFlight = rung
        Log.i(TAG, "Rung $attempt/${steps.size}: $rung (serverUnreachable=$serverUnreachable)")

        return when (rung) {
            RecoveryStep.SAME_CONFIG -> RecoveryOutcome.Dial(liveConfig)
            RecoveryStep.OTHER_BOX -> otherBox(liveConfig)
            RecoveryStep.FRESH_MULTI -> freshMulti(liveConfig)
            RecoveryStep.REALITY_ONLY -> tagged("vless-reality", liveConfig)
            RecoveryStep.CACHED_CDN -> cachedCdn() ?: freshCdn(liveConfig)
            RecoveryStep.FRESH_CDN -> freshCdn(liveConfig)
        }
    }

    /**
     * Move the tunnel to a different box.
     *
     * Deliberately without the 20 % margin and the five-minute cooldown that [fasterPath] applies:
     * those rules exist so a *working* session is not disturbed for a small gain. Here the session
     * is not working — a box that cannot carry a tunnel is not being compared against anything, it
     * is being left. The only bar is that the other box completes an HTTPS round trip, because a TCP
     * handshake has been measured answering in 83 ms on a box whose TLS never finished.
     */
    private suspend fun otherBox(fallback: String): RecoveryOutcome {
        val profile = sessionProfile() ?: return noSession(fallback)
        // A country the user picked by hand is theirs, even when it is not working: they may be
        // waiting for it to come back, and being moved elsewhere against that choice is worse than
        // the pause. Automatic mode is where the app owns the decision, and only here.
        if (!automaticMode()) {
            Log.i(TAG, "OTHER_BOX skipped: the user chose this country")
            return RecoveryOutcome.Dial(fallback)
        }
        val current = profileHost(fallback)
        val others = repository.fetchLocations(BackendEndpointConfig()).getOrNull()
            .orEmpty()
            .filter { it.host.isNotBlank() && it.host != current }
        if (others.isEmpty()) {
            Log.i(TAG, "No other box to move to (on $current); staying on the ladder")
            return cachedCdn() ?: RecoveryOutcome.Dial(fallback)
        }

        val ranked = measureBoxes(others)
        if (ranked.isEmpty()) {
            Log.w(TAG, "No other box finished a handshake; staying on the ladder")
            return cachedCdn() ?: RecoveryOutcome.Dial(fallback)
        }

        for ((box, ms) in ranked) {
            repository.preferEndpoint(box.apiBase)
            val config = repository.fetchXrayConfig(BackendEndpointConfig(), profile, MULTI_TAG).getOrNull()
                ?: continue
            Log.i(TAG, "Escaping ${current ?: "unknown"} for ${box.country} (${ms}ms handshake)")
            return RecoveryOutcome.Dial(config)
        }
        return cachedCdn() ?: RecoveryOutcome.Dial(fallback)
    }

    /**
     * One parallel HTTPS round trip per box, cheapest completed handshake first.
     *
     * Boxes that never finished a handshake are dropped rather than ranked last, so no caller can
     * select a server the device cannot actually reach. Measured in parallel because a blackholed
     * address costs a full timeout, and doing that once instead of per box is the difference between
     * a recovery that is invisible and one the user watches.
     */
    private suspend fun measureBoxes(boxes: List<VpnLocation>): List<Pair<VpnLocation, Int>> =
        coroutineScope {
            val started = boxes.map { box ->
                box to async { repository.measureBoxRoundTripMs(repository.boxProbeUrl(box)) }
            }
            started.map { (box, deferred) -> box to deferred.await() }
                .filter { it.second >= 0 }
                .sortedBy { it.second }
        }

    /**
     * True when at least one of our own addresses answers again, so rebuilding onto the direct path
     * is worth the interruption. Addresses are measured in parallel, because the second one only
     * exists for the case where the first is blackholed, and a blackhole costs a full timeout.
     */
    suspend fun directPathAnswers(): Boolean = coroutineScope {
        serverHosts()
            .map { host -> async { DIRECT_PROBE_PORTS.any { port -> probe(host, port) >= 0 } } }
            .any { it.await() }
    }

    /**
     * The addresses we own. Taken from the bootstrap payload where it has been refreshed, so a
     * rotated server IP is measured instead of being read as a permanent block.
     */
    private fun serverHosts(): List<String> =
        repository.serverProbeHosts().ifEmpty { listOf(SERVER_PROBE_HOST) }

    /** A fresh balancer profile for the direct path, or null while the control channel is unreachable. */
    suspend fun directPathProfile(): String? {
        val profile = sessionProfile() ?: return null
        return repository.fetchXrayConfig(BackendEndpointConfig(), profile, MULTI_TAG).getOrNull()
    }

    /**
     * One parallel round of probes over everything that can be learned without building a tunnel.
     *
     * All candidates are measured at the same instant, so the whole ranking costs one round trip
     * instead of one timeout per guess.
     */
    private suspend fun race(): Race = coroutineScope {
        val neutral = PathLadder.NEUTRAL_HOSTS.map { host ->
            async { probe(host, NEUTRAL_PROBE_PORT) }
        }
        val direct = serverHosts().flatMap { host ->
            DIRECT_PROBE_PORTS.map { port ->
                async { PathProbe(host, port, probe(host, port)) }
            }
        }
        val knownEdge = cachedEdge()
        val cachedCdn = prefs.getString(KEY_CACHED_CDN_CONFIG, "").orEmpty()
        val edge = knownEdge?.let { host ->
            async { PathProbe(host, CDN_PROBE_PORT, probeEdge(host, cachedCdn)) }
        }

        val neutralAnswered = PathLadder.deviceNetworkUp(neutral.map { it.await() })
        val directProbes = direct.map { it.await() }
        val edgeProbes = listOfNotNull(edge?.await())

        Race(
            neutralAnswered = neutralAnswered,
            directAnswered = PathLadder.anyAnswered(directProbes),
            rankedEdges = PathLadder.rank(edgeProbes).filter { it.pingMs >= 0 }.map { it.host }
        )
    }

    /**
     * Decides which family of paths to try first. Returns null when the device's own link is down —
     * the one verdict where retrying anything is wasted effort.
     */
    private suspend fun diagnose(): List<RecoveryStep>? = coroutineScope {
        // The fleet verdict costs its own capped round trip to a different host, so it is started
        // before the local probes instead of after them: one wait instead of two, ~4 s saved on the
        // path where the user is already watching a spinner.
        val fleetJob = async {
            withTimeoutOrNull(FLEET_VERDICT_TIMEOUT_MS) { repository.fetchFleetVerdict() }
        }
        val result = race()
        if (!result.neutralAnswered) {
            fleetJob.cancel()
            return@coroutineScope null
        }

        serverUnreachable = !result.directAnswered
        rankedEdges = result.rankedEdges

        // Only worth reporting when our own address answered: with it silent the API lives on the
        // same address, so the report would just burn a timeout the next rung needs.
        if (serverUnreachable) {
            fleetJob.cancel()
            return@coroutineScope PathLadder.plan(serverUnreachable = true)
        }

        repository.reportReachability(REACHABILITY_TUNNEL_FAILED)
        // Ambiguous from one device, so the detour is only offered if the fleet agrees the address
        // is the problem. Capped: this is already a failure path and the user is waiting.
        val fleet = fleetJob.await()
        val corroborated = shouldFallBackToCdn(REACHABILITY_TUNNEL_FAILED, fleet)
        Log.i(TAG, "Diagnosis: tunnel_failed, fleet=${fleet ?: "none"} → cdnRungs=$corroborated")
        PathLadder.plan(serverUnreachable = false, fleetCorroborates = corroborated)
    }

    /**
     * A profile for the box that answers clearly faster than the one we are on, or null when there is
     * nothing better to move to.
     *
     * The measurement is one parallel round of TCP handshakes over every published box — the same
     * round the connect-time choice makes, and deliberately not the tunnel's own latency: comparing a
     * handshake to another box against a request through this tunnel compares two different things.
     * What makes the comparison worth having is the bandwidth-delay product. On a 200 ms path a
     * receive window of tens of kilobytes caps a single stream near 1.6 Mbit/s no matter what the
     * server is willing to send, so the box with the shorter round trip is the box with the higher
     * ceiling — and that is the only reason to disturb a working session at all.
     */
    suspend fun fasterPath(liveConfig: String): String? = coroutineScope {
        val boxes = repository.fetchLocations(BackendEndpointConfig()).getOrNull()
            ?.filter { it.host.isNotBlank() }
            .orEmpty()
        if (boxes.size < 2) {
            Log.i(TAG, "Path search: only ${boxes.size} box(es) published, nothing to compare")
            return@coroutineScope null
        }

        val currentHost = profileHost(liveConfig)
        val measured = measureBoxes(boxes)
        // Each reason to stay put is logged, because an operator who just read "Tunnel degraded"
        // should be able to find the sentence that says why nothing moved, instead of wondering
        // whether the search ran at all.
        val best = measured.firstOrNull() ?: run {
            Log.w(TAG, "Path search: no box finished a handshake")
            return@coroutineScope null
        }
        if (currentHost == null) {
            Log.w(TAG, "Path search: the live profile does not name a box")
            return@coroutineScope null
        }
        // A box that did not finish a handshake has no number to compare, and the optimisation path
        // must not fire on a missing measurement — the escape path (OTHER_BOX) is the one that acts
        // when the current box has stopped answering.
        val currentMs = measured.firstOrNull { it.first.host == currentHost }?.second ?: -1
        if (currentMs <= 0) {
            Log.w(TAG, "Path search: $currentHost did not answer this round, leaving it to the ladder")
            return@coroutineScope null
        }
        if (best.first.host == currentHost) {
            Log.i(
                TAG,
                "Already on the fastest box: $currentHost ${currentMs}ms of " +
                    measured.joinToString { "${it.first.country}=${it.second}ms" }
            )
            return@coroutineScope null
        }

        val sinceSwitch = System.currentTimeMillis() - lastPathSwitchAt
        if (!PathLadder.fasterPathWins(currentMs, best.second, sinceSwitch)) {
            Log.i(
                TAG,
                "Keeping $currentHost (${currentMs}ms): best alternative ${best.first.host} " +
                    "${best.second}ms, sinceSwitch=${sinceSwitch}ms"
            )
            return@coroutineScope null
        }

        val profile = sessionProfile() ?: return@coroutineScope null
        val target = best.first
        // The remembered endpoint outranks whatever we pass unless it is told otherwise, so a request
        // for another country's profile would otherwise come back from this one and the switch would
        // be a label change with the traffic still on the old box. A fetch that fails leaves the
        // preference set, and that is the right residue: withFailover clears it as soon as the box
        // itself turns out not to answer.
        repository.preferEndpoint(target.apiBase)
        val config = repository.fetchXrayConfig(BackendEndpointConfig(), profile, MULTI_TAG).getOrNull()
            ?: return@coroutineScope null
        lastPathSwitchAt = System.currentTimeMillis()
        Log.i(TAG, "Faster path: $currentHost ${currentMs}ms -> ${target.host} ${best.second}ms")
        config
    }

    /**
     * True when the app owns the choice of box.
     *
     * A country the user picked by hand is theirs, not a candidate: moving that session onto another
     * box would be overriding a decision they made, and the label would name a country they never
     * chose. Only the automatic mode may move, and the mode is read from the same session file the
     * ViewModel writes for exactly that reason.
     */
    fun automaticMode(): Boolean =
        prefs.getString(KEY_LOCATION_MODE, null)
            ?.let { runCatching { LocationMode.valueOf(it) }.getOrNull() }
            ?.let { it == LocationMode.AUTO }
            ?: true

    /** The address the live profile dials, which is the box the tunnel is actually on. */
    private fun profileHost(config: String): String? {
        return try {
            val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return null
            for (index in 0 until outbounds.length()) {
                val settings = outbounds.optJSONObject(index)?.optJSONObject("settings") ?: continue
                val address = settings.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")
                    ?: settings.optJSONArray("servers")?.optJSONObject(0)?.optString("address")
                if (!address.isNullOrBlank()) return address
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Asks the out-of-band channels for a new endpoint list, at most once per episode.
     *
     * The HTTP mirrors are our own host, so they can only help while it still answers; when it does
     * not, the DNS pointer is the only channel that is genuinely independent of it and goes first.
     */
    private suspend fun refreshEndpointListOnce(): Boolean {
        if (endpointListRefreshed) return false
        endpointListRefreshed = true
        return if (serverUnreachable) {
            repository.refreshBootstrapFromDns() || repository.refreshBootstrap()
        } else {
            repository.refreshBootstrap() || repository.refreshBootstrapFromDns()
        }
    }

    /**
     * A fresh multi-link profile. The endpoint list can itself be stale, so the out-of-band
     * channels are consulted once before this step is written off.
     */
    private suspend fun freshMulti(fallback: String): RecoveryOutcome {
        val profile = sessionProfile() ?: return noSession(fallback)
        val config = BackendEndpointConfig()

        repository.fetchXrayConfig(config, profile, MULTI_TAG).getOrNull()?.let {
            return RecoveryOutcome.Dial(it)
        }
        if (refreshEndpointListOnce()) {
            repository.fetchXrayConfig(config, profile, MULTI_TAG).getOrNull()?.let {
                return RecoveryOutcome.Dial(it)
            }
        }
        return cachedCdn() ?: RecoveryOutcome.Dial(fallback)
    }

    /** A single-transport profile: same server, a different family on a different port. */
    private suspend fun tagged(tag: String, fallback: String): RecoveryOutcome {
        val profile = sessionProfile() ?: return noSession(fallback)
        val config = BackendEndpointConfig()
        return repository.fetchXrayConfig(config, profile, tag).getOrNull()
            ?.let { RecoveryOutcome.Dial(it) }
            ?: cachedCdn()
            ?: RecoveryOutcome.Dial(fallback)
    }

    /**
     * A CDN-fronted profile dialled at an edge the race already found healthy. Kept in the cache so
     * the next episode starts warm instead of paying for the round trip again.
     */
    private suspend fun freshCdn(fallback: String): RecoveryOutcome {
        val profile = sessionProfile() ?: return noSession(fallback)
        val config = BackendEndpointConfig()
        val raw = repository.fetchXrayConfig(config, profile, CDN_TAG).getOrNull()
            ?: return cachedCdn() ?: RecoveryOutcome.Dial(fallback)

        // Only edges that answered a TCP probe may be dialled, and the probe runs in parallel, so the
        // whole check costs one timeout instead of one per candidate. Measured on an Iranian line again on
        // 2026-11-04: three different Cloudflare edges refused the connection in under 0.11 s, yet the
        // ladder kept rebuilding a tunnel onto an unprobed address and waiting out the verification
        // deadline — ~24 seconds of "Connecting..." on a path that was never going to answer. A rung that
        // can be disproved in four seconds must be disproved first, because the user is watching.
        val candidates = (rankedEdges + repository.scoredCdnAddresses())
            .distinct()
            .filterNot { it in triedEdges }
            .take(CDN_CANDIDATE_LIMIT)
        if (candidates.isEmpty()) return cachedCdn() ?: RecoveryOutcome.Dial(fallback)

        val live = coroutineScope {
            PathLadder.liveEdges(
                candidates.map { host ->
                    async { PathProbe(host, CDN_PROBE_PORT, probeEdge(host, raw)) }
                }.map { it.await() },
                triedEdges,
                CDN_CANDIDATE_LIMIT
            )
        }
        if (live.isEmpty()) {
            Log.w(
                TAG,
                "CDN rung skipped: none of ${candidates.joinToString()} answered a short TCP probe; " +
                    "staying on the current path instead of rebuilding onto a dead detour"
            )
            return RecoveryOutcome.Dial(fallback)
        }

        val edge = live.firstOrNull { it !in triedEdges }
        Log.i(TAG, "CDN rung: edge=${edge ?: "none"} live=${live.size} candidates=${candidates.size} tried=${triedEdges.size}")
        val ready = if (edge != null) {
            triedEdges += edge
            repository.withCdnAddress(raw, edge)
        } else {
            raw
        }
        prefs.edit().putString(KEY_CACHED_CDN_CONFIG, ready).apply()
        return RecoveryOutcome.Dial(ready, detour = true)
    }

    private fun cachedCdn(): RecoveryOutcome? =
        prefs.getString(KEY_CACHED_CDN_CONFIG, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { RecoveryOutcome.Dial(it, detour = true) }

    /**
     * The Cloudflare edge the cached profile is pinned to. Reading it costs nothing and it is the
     * only edge we know by heart while our own address is not answering.
     */
    private fun cachedEdge(): String? = try {
        val root = JSONObject(prefs.getString(KEY_CACHED_CDN_CONFIG, null).orEmpty())
        val outbounds = root.optJSONArray("outbounds") ?: return null
        (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == CDN_TAG }
            ?.optJSONObject("settings")
            ?.optJSONArray("vnext")
            ?.optJSONObject(0)
            ?.optString("address")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private suspend fun probe(host: String, port: Int): Int =
        VpnUriParser.measureRealTcpPing(host, port)

    /**
     * A CDN edge probed the way the tunnel will actually use it: a TLS handshake that completes with
     * the front's own server name, not a socket that merely opened.
     *
     * On 2026-11-04 the line began accepting every TCP connection to Cloudflare and resetting it during
     * the ClientHello, so the connect-only probe called the front healthy, the ladder rebuilt onto it,
     * and the user waited out the verification deadline on a path that could never answer. Falls back to
     * the old probe only when the profile carries no name to offer, so a parse problem cannot silently
     * disable the rung.
     */
    private suspend fun probeEdge(host: String, config: String): Int {
        val serverName = cdnFrontName(config)
        return if (serverName == null) {
            Log.w(TAG, "CDN edge probe without a server name: falling back to a TCP connect")
            probe(host, CDN_PROBE_PORT)
        } else {
            VpnUriParser.measureTlsHandshakeMs(host, CDN_PROBE_PORT, serverName)
        }
    }

    /** The SNI the CDN outbound will present — the only name Cloudflare will route this zone by. */
    private fun cdnFrontName(config: String): String? = try {
        val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return null
        (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == CDN_TAG }
            ?.optJSONObject("streamSettings")
            ?.optJSONObject("tlsSettings")
            ?.optString("serverName")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun noSession(fallback: String): RecoveryOutcome =
        if (fallback.isNotBlank()) RecoveryOutcome.Dial(fallback)
        else RecoveryOutcome.GiveUp("Please sign in first")

    /**
     * The session the ViewModel persisted. The service can outlive the UI, so preferences are the
     * only reliable source of a token at this point.
     */
    private fun sessionProfile(): UserProfile? {
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        if (token.isBlank()) return null
        val username = prefs.getString(KEY_USERNAME, "").orEmpty()
        return UserProfile(
            uid = prefs.getString(KEY_UID, "").orEmpty(),
            displayName = prefs.getString(KEY_DISPLAY_NAME, username).orEmpty(),
            email = username,
            authToken = token,
            planTitle = "",
            usedBytes = 0L,
            totalBytes = 0L,
            username = username
        )
    }

    private class Race(
        val neutralAnswered: Boolean,
        val directAnswered: Boolean,
        val rankedEdges: List<String>
    )

    private companion object {
        const val TAG = "PTunnelRecovery"

        const val SESSION_PREFS = "ptunnel_session"
        const val KEY_TOKEN = "token"
        const val KEY_USERNAME = "username"
        const val KEY_UID = "uid"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_CACHED_CDN_CONFIG = "cached_cdn_config"

        /** The recovery rung that last produced a verified session, with the time it was written. */
        const val KEY_LAST_GOOD_RUNG = "last_good_rung"

        /** A line's blocking pattern moves, so a rung that has not been needed for a day is forgotten. */
        const val RUNG_MEMORY_MS = 24L * 60L * 60L * 1000L

        /** Written by the ViewModel; the name has to match it. */
        const val KEY_LOCATION_MODE = "location_mode"

        const val MULTI_TAG = "multi"
        const val CDN_TAG = "cdn"

        const val SERVER_PROBE_HOST = "86.54.42.124"
        const val NEUTRAL_PROBE_PORT = 443

        /** Mirrors the cdn outbound the backend hands out (Cloudflare, WebSocket over TLS). */
        const val CDN_PROBE_PORT = 2083

        /** How many edges one rung is willing to prove alive before it gives up. */
        const val CDN_CANDIDATE_LIMIT = 3

        /** How long the fleet's verdict may delay a recovery decision before it is treated as none. */
        const val FLEET_VERDICT_TIMEOUT_MS = 4_000L

        /**
         * Every transport we publish on our own address, so a per-port block is visible.
         *
         * 8080 leads because that is where Reality listens now: on an Iranian line the same tunnel on
         * 443 measured 7 KB/s against 2.3 MB/s on 8080, so a box that only answers on 443 is a box the
         * user would experience as crawling. 443 stays probed because nginx still relays it into the
         * same inbound for profiles that were issued before the move.
         */
        val DIRECT_PROBE_PORTS = listOf(8080, 443, 2083, 2053)
    }
}
