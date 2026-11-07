package com.example.network

/**
 * One candidate path, as measured directly from the device: a completed TCP handshake to it, or
 * the fact that it never answered.
 */
data class PathProbe(
    val host: String,
    val port: Int,
    /** Round trip of the handshake in milliseconds, or -1 when the path never answered. */
    val pingMs: Int
)

/**
 * One attempt in the mid-session recovery order.
 *
 * Every step is a whole profile to dial, not a knob on the previous one: when a route dies the
 * only thing that helps is arriving at the server by a different road.
 */
enum class RecoveryStep {
    /** Reuse the profile that was live when the tunnel died. Costs nothing and fixes blips. */
    SAME_CONFIG,

    /**
     * Put the tunnel on a *different box*.
     *
     * Every other rung rebuilds the path to the server we are already on, so when that box is the
     * problem — filtered on this line, or simply not building a tunnel — the ladder would walk its
     * whole length, hand back, and walk it again against the same dead address. That is the failure
     * an operator reported in words: automatic mode chose Turkey, Turkey never came up, and nothing
     * moved it to Germany.
     */
    OTHER_BOX,

    /** Ask the backend for a fresh multi-link profile (the rotation is kcp; the rest is diallable). */
    FRESH_MULTI,

    /**
     * Same server, different family: the Reality path, over TCP.
     *
     * It replaces the old mKCP rung because UDP is now the rotation itself, so "try the other family"
     * means TCP. The ordering is measurement, not taste: on the same Iranian line minutes apart, kcp
     * gave 1.67 / 1.78 / 1.70 MB/s and reality gave 1.14 MB/s then 3.3 KB/s — the TCP path to this
     * address is granted roughly a megabyte and then shaped, and plain HTTPS to it without any tunnel at
     * all measured 4.4 KB/s while Cloudflare from the same line gave 686 KB/s. A line that cannot carry
     * UDP still has to connect, so this rung is how it gets there.
     */
    REALITY_ONLY,

    /** The last CDN-fronted profile we cached, dialled as-is. */
    CACHED_CDN,

    /** Fetch a CDN profile and dial the best-scored Cloudflare edge. */
    FRESH_CDN
}

/**
 * The order in which a dead tunnel is retried.
 *
 * All of our direct paths (reality, vmess, trojan, kcp) dial the *same* address, so when that
 * address stops answering they are dead as a group. In that case the Cloudflare-fronted routes —
 * the only ones whose destination address is different — have to go first, because they are the
 * only ones that can survive an IP block. Otherwise the cheap local rebuilds go first and the
 * CDN stays the last resort, so a transient drop never silently moves the user onto a slower path.
 */
object PathLadder {

    /**
     * Neutral hosts for the "is the device itself online" question.
     *
     * One target is not enough: on Iranian paths a single anycast address can be filtered while
     * the user's own link is fine, and that mistake was measured at ~210 seconds of lost recovery
     * time on a real line. Any one answer is treated as "online".
     */
    val NEUTRAL_HOSTS = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8")

    /** True when at least one neutral target answered, so the device's own link is up. */
    fun deviceNetworkUp(replies: List<Int>): Boolean = replies.any { it >= 0 }

    /**
     * Endpoint order for a control-channel call.
     *
     * A filtered hostname accepts the connection and then never answers, so each call in front of it
     * waits the full read timeout — measured on a live device as 6 s + 6 s wasted before the third
     * endpoint returned in 103 ms. Remembering which endpoint actually carried the last success turns
     * that into a penalty paid once per process instead of once per request.
     */
    fun orderedEndpoints(preferred: String?, first: String, rest: List<String>): List<String> =
        (listOfNotNull(preferred?.takeIf { it.isNotBlank() }) + listOf(first) + rest).distinct()

    /**
     * The edges worth dialling, in the order they answered.
     *
     * A detour can be disproved with a TCP probe in seconds, while dialling it costs a tunnel rebuild
     * plus the verification deadline — measured on Iranian lines as ~24 s of "Connecting..." on a path
     * that was never going to answer, three times in one episode. Candidates are capped so one rung
     * cannot spend the whole recovery window on a family of addresses that is filtered, and edges that
     * already failed are never retried inside the same episode.
     */
    fun liveEdges(
        probes: List<PathProbe>,
        tried: Iterable<String>,
        limit: Int
    ): List<String> =
        rank(probes).filter { it.pingMs >= 0 }.map { it.host }.distinct().filterNot { it in tried }.take(limit)

    /**
     * Whether a connected session should be rebuilt onto what the server is now serving.
     *
     * Both sides must be real profiles: an empty fetch (a timeout, a 5xx) is never a reason to tear a
     * working tunnel down.
     */
    fun profileDiffers(running: String, served: String): Boolean =
        running.isNotBlank() && served.isNotBlank() && running != served

    /**
     * Puts the rung that carried the last verified session ahead of the rest of the plan.
     *
     * The reason this exists is in the fleet log: one Iranian /24 pulled `tag=cdn` 202 times in a day and
     * established nothing, while its first choice was always the kcp profile that its own line refuses.
     * Every reconnect restarted the climb at rung zero, so the user watched "Connecting…" for tens of
     * seconds and saw the server change under their feet. A rung that has already worked on this line is
     * worth one reconnect to learn and saves the whole climb on every reconnect after that.
     *
     * Only the ordering changes: every rung in the original plan is still reachable, so a line whose
     * blocking pattern moved still gets walked down the rest of the ladder.
     */
    fun planWithRememberedRung(
        plan: List<RecoveryStep>,
        remembered: RecoveryStep?
    ): List<RecoveryStep> {
        if (remembered == null || remembered == RecoveryStep.SAME_CONFIG) return plan
        val index = plan.indexOf(remembered)
        if (index < 2) return plan
        return plan.subList(0, 1) + remembered + plan.subList(1, index) + plan.drop(index + 1)
    }

    /** How many attempts an unbroken recovery episode may spend before the ladder restarts. */
    const val MAX_ATTEMPTS = 6

    /**
     * The order in which a dead tunnel is retried.
     *
     * [fleetCorroborates] decides whether the Cloudflare rungs are offered at all when the failure
     * is ambiguous (our address answers, the tunnel does not). One device's word is not evidence of
     * a block, and the detour is slower, so without corroboration the ladder stops after the direct
     * rebuilds instead of moving the user onto it.
     *
     * Another box comes second in both orders, right after the free retry of what was already live:
     * a second server is cheaper than a detour, and unlike every rung after it, it survives the
     * failure mode where the address itself is the problem.
     */
    fun plan(serverUnreachable: Boolean, fleetCorroborates: Boolean = true): List<RecoveryStep> =
        if (serverUnreachable) {
            listOf(
                RecoveryStep.SAME_CONFIG,
                RecoveryStep.OTHER_BOX,
                RecoveryStep.CACHED_CDN,
                RecoveryStep.FRESH_CDN,
                RecoveryStep.FRESH_MULTI,
                RecoveryStep.REALITY_ONLY
            )
        } else {
            val direct = listOf(
                RecoveryStep.SAME_CONFIG,
                RecoveryStep.OTHER_BOX,
                RecoveryStep.FRESH_MULTI,
                RecoveryStep.REALITY_ONLY
            )
            if (fleetCorroborates) {
                direct + listOf(RecoveryStep.CACHED_CDN, RecoveryStep.FRESH_CDN)
            } else {
                direct
            }
        }

    /**
     * Delay before attempt [attempt] (0-based). The first rebuild is immediate: a path that comes
     * straight back must not cost the user a visible pause.
     */
    fun backoffMs(attempt: Int): Long = when {
        attempt <= 0 -> 0L
        attempt == 1 -> 1_000L
        attempt == 2 -> 2_000L
        attempt == 3 -> 4_000L
        else -> 8_000L
    }

    /**
     * Orders candidates that were probed in parallel: the ones that answered first, cheapest
     * handshake first, and the silent ones after them in the order they were given.
     *
     * Rebuilding the tunnel takes one path at a time, so the only way to "try the best one" is to
     * settle the order with one cheap parallel round before dialling anything.
     */
    fun rank(probes: List<PathProbe>): List<PathProbe> =
        probes.filter { it.pingMs >= 0 }.sortedBy { it.pingMs } +
            probes.filter { it.pingMs < 0 }

    /** True when at least one candidate answered, so the path is worth dialling at all. */
    fun anyAnswered(probes: List<PathProbe>): Boolean = probes.any { it.pingMs >= 0 }

    /**
     * How much better a path must look before a working session is disturbed for it.
     *
     * Twenty per cent, expressed without floating point (five times the candidate against four times
     * the current) so the arithmetic cannot round a tie into a switch. On the measured line this is
     * not a nicety: the tunnel's own throughput is pinned by the bandwidth-delay product — a 40 KB
     * receive window over 205 ms is about 1.6 Mbit/s whatever the server wants to send — so cutting
     * the round trip is the one lever that moves the number a user feels, and doing it on a coin
     * flip would be worse than not doing it at all.
     */
    fun fasterPathWins(currentMs: Int, candidateMs: Int, sinceLastSwitchMs: Long): Boolean =
        currentMs > 0 && candidateMs > 0 &&
            sinceLastSwitchMs >= SWITCH_COOLDOWN_MS &&
            candidateMs.toLong() * 5L < currentMs.toLong() * 4L

    /**
     * True when the last [DEGRADED_ROUNDS] probes were all clearly slower than the session's own
     * baseline, which is what asks the question at all.
     *
     * One slow probe is not a degradation: on a mobile link a single sample can triple for reasons
     * that have nothing to do with the path. Three in a row means the path changed.
     */
    fun isDegraded(recentMs: List<Int>, baselineMs: Int): Boolean =
        baselineMs > 0 && recentMs.size >= DEGRADED_ROUNDS &&
            recentMs.takeLast(DEGRADED_ROUNDS).all { it > 0 && it.toLong() * 2L > baselineMs.toLong() * 3L }

    /**
     * The median of [samples] — the session's baseline is a middle value, not the luckiest probe of
     * the first minutes, or the app would be comparing today against the best day.
     */
    fun baselineMs(samples: List<Int>): Int {
        val sorted = samples.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return 0
        return sorted[sorted.size / 2]
    }

    /**
     * True when the tunnel was provably carrying the user's traffic during the last health window.
     *
     * A saturated link starves its own health probe: the probe's handshake queues behind the user's
     * download, misses its deadline twice, and the app reads that as a dead tunnel. Measured on a SIM
     * on 2026-11-04, seconds after a 39 MB file finished at full speed: `Health probe failed (2/2)` →
     * `Tunnel lost; starting the recovery ladder` → `Recovery attempt did not come up in time`. The
     * tunnel was healthy and fast; the probe was hungry — and tearing the tunnel down at that moment
     * is the "sometimes the VPN just turns off" the operator reports.
     *
     * Bytes moved answers "was the tunnel working?" for free, and a throttled path does not clear the
     * bar: 32 KB/s sustained is the gap between the clamped transports (5-7 KB/s) and the working one
     * (1-3 MB/s) measured in the same window.
     */
    fun linkBusy(bytesMoved: Long, windowMs: Long): Boolean =
        windowMs >= 1_000L && bytesMoved * 1_000L >= BUSY_BYTES_PER_SECOND * windowMs

    /**
     * Worst case from a real tunnel death to the ladder noticing it.
     *
     * Each missed probe costs one interval plus its own deadline, and the death is declared only after
     * [limit] of them. This is a number rather than a feeling because both inputs get tuned under
     * pressure: the deadline went 3 s → 6 s to stop tearing down healthy tunnels, and that must not
     * silently turn detection into minutes. Guarded by a test.
     */
    fun detectionWorstCaseMs(intervalMs: Long, deadlineMs: Long, limit: Int): Long =
        (intervalMs + deadlineMs) * limit

    /** Consecutive slow probes before the path is considered worse than it was. */
    const val DEGRADED_ROUNDS = 3

    /** Below this the tunnel is idle or throttled; above it, the user is actually using it. */
    const val BUSY_BYTES_PER_SECOND = 32L * 1024L

    /** Shortest spacing between two automatic switches, so one unlucky sample cannot start a flap. */
    const val SWITCH_COOLDOWN_MS = 5 * 60 * 1000L
}
