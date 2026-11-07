package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.model.VpnConnectionState
import com.example.network.PathLadder
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android VpnService backed by the Xray-core native library (AndroidLibXrayLite).
 * Xray's bundled `tun` inbound reads packets from the Android TUN file descriptor.
 */
class ShieldVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var coreLoading = false
    private var explicitStop = false
    private var verificationThread: Thread? = null
    private var healthMonitorThread: Thread? = null

    /**
     * Where the health probes run. Cached so a probe abandoned at the deadline does not queue the
     * next one behind it: the whole point of the bound is that rounds keep their rhythm.
     */
    private val probeExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool()
    private val verificationStarted = AtomicBoolean(false)
    private var connectionStartedAt = 0L

    /** When the current attempt must be declared dead. Shorter while recovering. */
    private var connectDeadline = 0L

    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recovery: TunnelRecovery? = null
    private var recoveryJob: Job? = null
    private var recoveryAttempt = 0

    /** Not before this moment: a search for a faster box must not run every health round. */
    private var nextPathSearchAt = 0L

    /** The device has no usable path right now, so attempts are held instead of burned. */
    @Volatile
    private var waitingForNetwork = false

    /** True while the tunnel being built is a rebuild of a live session, not a user connect. */
    @Volatile
    private var reconnecting = false

    /** Probe fast until this instant. Set on connect and on every change of the underlying network. */
    @Volatile
    private var healthFastUntil = 0L

    /** The OS says the default network is gone, so probing it would only spend the user's data. */
    @Volatile
    private var linkReportedDown = false

    /** The profile the core is running right now, which is not always the one the UI handed over. */
    @Volatile
    private var activeConfig: String? = null

    /** True while the tunnel is online through a route other than our own address. */
    @Volatile
    private var onDetour = false

    /** True while we are rebuilding onto the direct path after a detour. */
    @Volatile
    private var returningToFastLane = false

    /** The detour profile that works today, kept so a failed comeback has somewhere to return to. */
    @Volatile
    private var detourConfig: String? = null

    private var fastLaneJob: Job? = null
    private var lastFastLaneReturnAt = 0L

    /** Grows after every failed comeback, so a direct path that keeps lying cannot cause thrash. */
    private var fastLaneGapMs = FAST_LANE_MIN_GAP_MS

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var tunnelVerified = false

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "Xray core started; verifying real tunnel connectivity")
            updateNotification("Verifying tunnel...")
            startTunnelVerification()
            return 0
        }

        override fun shutdown(): Long {
            Log.i(TAG, "Xray core stopped")
            return 0
        }

        override fun onEmitStatus(code: Long, message: String?): Long {
            if (!message.isNullOrBlank()) {
                Log.i(TAG, "core status: $message")
            }
            return 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            initCoreEnvironment()
            coreController = Libv2ray.newCoreController(callback)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to initialize Xray core", t)
            VpnRuntime.set(VpnConnectionState.DISCONNECTED, "VPN engine failed to initialize: ${t.message}")
        }
        recovery = TunnelRecovery(this)
        registerNetworkWatch()
    }

    /**
     * Android does not re-dial a tunnel when the default network changes: switching from Wi-Fi to
     * mobile data simply kills every socket the core holds. Watching the default network turns
     * that from a silent stall into an immediate re-probe, and an immediate rebuild if the new
     * path really cannot carry the tunnel.
     */
    private fun registerNetworkWatch() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Default network available again; resuming health probes")
                linkReportedDown = false
                onDefaultNetworkChanged()
            }

            override fun onLost(network: Network) {
                // An antenna that just went away: stop probing through the tunnel until it returns,
                // so an outage costs no data at all.
                Log.w(TAG, "Default network lost; pausing health probes until it returns")
                linkReportedDown = true
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            connectivityManager = manager
            networkCallback = callback
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to watch the default network: ${t.message}")
        }
    }

    private fun onDefaultNetworkChanged() {
        // A fresh path deserves the fast probe interval again: this is exactly the moment a tunnel
        // that did not survive it has to be noticed.
        healthFastUntil = System.currentTimeMillis() + HEALTH_FAST_WINDOW_MS

        if (waitingForNetwork && !explicitStop && !stopRequested) {
            Log.i(TAG, "Device network is back; restarting recovery")
            waitingForNetwork = false
            recoveryAttempt = 0
            recovery?.reset()
            requestRecoveryStep()
            return
        }
        if (VpnRuntime.state.value == VpnConnectionState.CONNECTED) {
            verifyAfterNetworkChange()
        }
    }

    /**
     * One quick round trip after the path under the tunnel changed. A single miss is normal while
     * the core re-dials, so the tunnel is only rebuilt once it has failed twice.
     */
    private fun verifyAfterNetworkChange() {
        recoveryScope.launch {
            repeat(2) { round ->
                if (round > 0) delay(NETWORK_CHANGE_REPROBE_DELAY_MS)
                if (stopRequested || explicitStop) return@launch
                val latency = probeLatencyBounded(HEALTH_PROBE_TIMEOUT_MS)
                if (latency >= 0L) {
                    Log.i(TAG, "Tunnel survived the network change (core latency ${latency}ms)")
                    return@launch
                }
            }
            if (VpnRuntime.state.value == VpnConnectionState.CONNECTED) {
                Log.w(TAG, "Tunnel did not survive the network change; rebuilding")
                handleTunnelLost()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_XRAY_CONFIG)
                    ?: prefs().getString(PREF_CONFIG, null)
                if (config.isNullOrBlank()) {
                    VpnRuntime.set(VpnConnectionState.DISCONNECTED, "No VPN configuration received")
                    stopSelf()
                    return START_NOT_STICKY
                }
                prefs().edit().putString(PREF_CONFIG, config).apply()
                // The UI asked for this profile, so it is a user connect: the full ceremony
                // applies and any half-finished recovery episode is abandoned.
                beginEpisode(reconnect = false)
                startVpn(config)
            }
            ACTION_DISCONNECT -> {
                explicitStop = true
                prefs().edit().remove(PREF_CONFIG).apply()
                stopVpn()
            }
            else -> {
                // System restart (START_STICKY) with a saved profile.
                val config = prefs().getString(PREF_CONFIG, null)
                if (!explicitStop && !config.isNullOrBlank()) {
                    beginEpisode(reconnect = false)
                    startVpn(config)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    /** Resets the recovery ladder. The next attempt starts from the live profile again. */
    private fun beginEpisode(reconnect: Boolean) {
        recoveryJob?.cancel()
        recoveryJob = null
        recovery?.reset()
        recoveryAttempt = 0
        waitingForNetwork = false
        reconnecting = reconnect
        if (!reconnect) {
            // A profile the user picked is the truth again: any earlier detour is history.
            stopFastLaneWatch()
            onDetour = false
            returningToFastLane = false
            detourConfig = null
        }
    }

    private fun startVpn(config: String, reconnect: Boolean = false) {
        if (coreController == null) {
            // The engine never came up, so every rung would fail, fetch another profile, and come back here forever.
            Log.e(TAG, "VPN engine is not initialized; refusing to start a tunnel")
            VpnRuntime.set(VpnConnectionState.DISCONNECTED, "VPN engine failed to initialize.")
            stopVpn()
            return
        }
        if (coreLoading || coreController?.isRunning == true) {
            Log.w(TAG, "Previous core session still marked active; performing teardown before restart")
            teardownTunnel()
            if (coreController?.isRunning == true) {
                Log.w(TAG, "Previous core did not stop within deadline; advancing the ladder")
                if (reconnect) onAttemptFailed()
                return
            }
        }
        reconnecting = reconnect
        explicitStop = false
        stopRequested = false
        tunnelVerified = false
        activeConfig = config
        connectionStartedAt = System.currentTimeMillis()
        // Fresh connections are the fragile ones, so they are watched closely at first and only
        // relaxed once the session has proven itself.
        healthFastUntil = connectionStartedAt + HEALTH_FAST_WINDOW_MS
        connectDeadline = connectionStartedAt +
            if (reconnect) RECONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
        verificationStarted.set(false)
        verificationThread?.interrupt()
        verificationThread = null
        startConnectionTimeoutWatchdog()
        VpnRuntime.set(VpnConnectionState.CONNECTING)

        try {
            startAsForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start foreground service", t)
            VpnRuntime.set(VpnConnectionState.DISCONNECTED, "Could not start VPN service")
            stopSelf()
            return
        }

        val tun = try {
            establishTun()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to establish TUN interface", t)
            noTunYet()
            return
        }

        tunInterface = tun
        coreLoading = true

        Thread {
            try {
                val controller = coreController
                if (controller == null) throw IllegalStateException("VPN engine is not initialized")
                controller.startLoop(config, tun.fd)
                Log.i(TAG, "startLoop returned; core running=${controller.isRunning}")
                if (!controller.isRunning) {
                    Log.w(TAG, "Core reports not running after startLoop; advancing the ladder")
                    onAttemptFailed()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Xray startLoop failed", t)
                // Advancing the ladder rather than stopping means the next attempt asks for a fresh
                // profile; a config the engine rejects is fixed by getting another one, not by
                // leaving the user disconnected.
                onAttemptFailed()
            } finally {
                coreLoading = false
            }
        }.start()
    }

    private fun establishTun(): ParcelFileDescriptor {
        val linkMtu = underlyingLinkMtu()
        val mtu = TunnelMtu.forLink(linkMtu ?: 0)
        val v6 = linkHasIpv6Address()
        Log.i(TAG, "TUN MTU $mtu (underlying link ${linkMtu ?: "unknown"}), IPv6 on link=$v6")

        return try {
            buildTunBuilder(mtu, v6).establish() ?: throw IllegalStateException("VPN permission was not granted")
        } catch (t: Throwable) {
            // A radio mid-handover can report an IPv6 the platform then refuses to route, and the
            // refusal is a bare RemoteException. Rebuilding without the IPv6 claims costs one extra
            // attempt and is the difference between a session that comes back and one that retries
            // forever — which is what the operator sees as "the VPN turned itself off and never came
            // back on this SIM".
            if (!v6) throw t
            // Reaching here means a network advertised IPv6 and the platform still refused the route.
            // Rebuilding without the claim is what makes the tunnel connectable, and it is also the
            // moment any real IPv6 on the device starts going around the tunnel — the user reads that
            // as an IP leak, so it has to be in the log.
            Log.w(TAG, "establish() refused IPv6 on an IPv6-advertising link; rebuilding IPv4-only (IPv6 traffic will NOT be covered): ${t.message}")
            buildTunBuilder(mtu, false).establish()
                ?: throw IllegalStateException("VPN permission was not granted")
        }
    }

    private fun buildTunBuilder(mtu: Int, ipv6Allowed: Boolean): Builder {
        val builder = Builder()
            .setSession("PTunnel")
            .setMtu(mtu)

        TunnelInterface.addresses(ipv6Allowed).forEach { (address, prefix) -> builder.addAddress(address, prefix) }
        TunnelInterface.routes(ipv6Allowed).forEach { builder.addRoute(it, 0) }
        builder.addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Keep the app outside its own TUN so the Xray outbound sockets never loop back
        // into the tunnel. Connectivity is verified through the core's own dialer instead.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }

        return builder
    }

    /**
     * Whether the tunnel must claim an IPv6 address and an IPv6 default route.
     *
     * Fail closed. Not claiming IPv6 is not a neutral choice: whatever IPv6 the device has then leaves
     * through the carrier untouched, and the user reads it as an IP leak — geo-blocked services break
     * and every lookup page shows Iran. So IPv6 is claimed unless every network on the device
     * positively says it has no global IPv6 of its own, and the refusal path (rebuild without IPv6) is
     * what keeps a link that genuinely has none connectable.
     */
    private fun linkHasIpv6Address(): Boolean {
        return try {
            val manager = getSystemService(ConnectivityManager::class.java) ?: return true
            @Suppress("DEPRECATION")
            val networks = manager.allNetworks
            if (networks.isEmpty()) return true
            networks.any { network ->
                manager.getLinkProperties(network)?.linkAddresses?.any { address ->
                    val raw = address.address
                    raw is java.net.Inet6Address && !raw.isLoopbackAddress && !raw.isLinkLocalAddress
                } == true
            }
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * A TUN that could not be created is almost always a radio that is not there yet.
     *
     * Measured, not assumed: switching mobile data off makes `establish()` throw a RemoteException
     * out of `jniSetAddresses`, and this path used to report DISCONNECTED and stop the service. That
     * is the whole of the "the VPN turns itself off when the net drops" complaint — the ladder was
     * ready to keep trying, and the interface step ended the session first. Only a withdrawn platform
     * permission is something the user can act on; everything else goes back on the 15-second clock.
     */
    private fun noTunYet() {
        if (stopRequested || explicitStop) return
        if (VpnService.prepare(applicationContext) != null) {
            Log.w(TAG, "TUN failure is a missing VPN permission")
            VpnRuntime.set(VpnConnectionState.DISCONNECTED, "VPN permission is not granted")
            stopVpn()
            return
        }
        Log.w(TAG, "No network to build the tunnel on; keeping the session alive")
        VpnRuntime.set(VpnConnectionState.CONNECTING, "Waiting for your internet connection.")
        updateNotification("Waiting for network...")
        keepRetrying("No network to build the tunnel on.")
    }

    /**
     * The MTU of the network the tunnel is being built on, or null when it cannot be asked.
     *
     * A rebuild happens while the VPN is already up, and then the active network *is* the tunnel —
     * reading that back would ratchet the number down every reconnect. VPN-carried networks are
     * therefore excluded, and when the active one is such a network the non-VPN networks are asked
     * instead. The smallest answer wins: the tightest link on the device decides what fits.
     */
    private fun underlyingLinkMtu(): Int? {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null

        fun carriesTraffic(network: Network?): Boolean {
            if (network == null) return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        val active = manager.activeNetwork?.takeIf { carriesTraffic(it) }
        val candidates = listOfNotNull(active)
            .ifEmpty { manager.allNetworks.filter { carriesTraffic(it) } }
        return candidates
            .mapNotNull { manager.getLinkProperties(it)?.mtu }
            .filter { it > 0 }
            .minOrNull()
    }

    /**
     * The core's startup callback only means the engine process is alive. We keep the UI in
     * CONNECTING until a real HTTP request has traversed the tunnel, then hold the connection
     * ceremony open long enough for the full animation to play.
     */
    private fun startTunnelVerification() {
        if (!verificationStarted.compareAndSet(false, true)) return

        val thread = Thread {
            // The 4.2 s hold exists to let the connection ceremony play out. A rebuild of an
            // already-connected session is not a ceremony — the user is waiting behind a
            // "Reconnecting" state, so it goes live the moment the tunnel is proven.
            val minCeremonyMs = if (reconnecting) 0L else CEREMONY_MIN_MS
            val controller = coreController
            var lastLatencyMs = -1L

            while (!stopRequested && !tunnelVerified &&
                System.currentTimeMillis() < connectDeadline
            ) {
                if (controller != null) {
                    try {
                        // Dial gstatic through the live Xray core (real server round-trip).
                        val latency = controller.measureDelay("https://www.gstatic.com/generate_204")
                        if (latency >= 0L) {
                            lastLatencyMs = latency
                            tunnelVerified = true
                            break
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Tunnel probe failed: ${t.message}")
                    }
                }
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
            }

            if (tunnelVerified && !stopRequested) {
                val elapsed = System.currentTimeMillis() - connectionStartedAt
                if (elapsed < minCeremonyMs) {
                    try {
                        Thread.sleep(minCeremonyMs - elapsed)
                    } catch (_: InterruptedException) {
                    }
                }
                if (!stopRequested) {
                    Log.i(
                        TAG,
                        "Tunnel verified after ${System.currentTimeMillis() - connectionStartedAt} ms " +
                            "(core latency ${lastLatencyMs}ms)"
                    )
                    recoveryAttempt = 0
                    reconnecting = false
                    // Record which rung carried this session *before* the episode state is cleared, so
                    // the next drop starts on the path this line has already proven instead of climbing
                    // the ladder from zero in front of the user.
                    recovery?.noteSessionVerified()
                    recovery?.reset()
                    if (returningToFastLane) {
                        // Back on our own address: the detour is no longer needed, and the next
                        // comeback starts from the normal cadence again.
                        returningToFastLane = false
                        onDetour = false
                        detourConfig = null
                        fastLaneGapMs = FAST_LANE_MIN_GAP_MS
                        stopFastLaneWatch()
                    } else if (onDetour) {
                        startFastLaneWatch()
                    }
                    VpnRuntime.set(VpnConnectionState.CONNECTED)
                    updateNotification("Connected")
                    startTunnelHealthMonitor()
                }
            }
        }
        thread.name = "ptunnel-verify"
        thread.isDaemon = true
        verificationThread = thread
        thread.start()
    }

    private fun startConnectionTimeoutWatchdog() {
        Thread {
            while (!stopRequested && System.currentTimeMillis() < connectDeadline) {
                try {
                    Thread.sleep(200L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (!stopRequested && !tunnelVerified &&
                VpnRuntime.state.value != VpnConnectionState.CONNECTED
            ) {
                if (reconnecting) {
                    Log.w(TAG, "Recovery attempt did not come up in time")
                    onAttemptFailed()
                } else {
                    // A first connect that did not verify is not the user's failure to retry: it goes
                    // into the same ladder a dead tunnel would, and that ladder does not stop. Before
                    // this, the ordinary "connection timed out, please try again" screen was the most
                    // common way the VPN switched itself off and stayed off.
                    Log.w(TAG, "First connect did not verify; starting the recovery ladder")
                    handleTunnelLost()
                }
            }
        }.apply {
            name = "ptunnel-timeout"
            isDaemon = true
        }.start()
    }

    /**
     * A verified tunnel is not a permanent one: on Iranian paths a route can die mid-session
     * while the core still reports "running". Keep probing and rebuild the tunnel instead of
     * leaving the user on a dead link. Rebuffing only happens on repeated, consecutive misses,
     * so a single flaky moment never tears the connection down.
     */
    /**
     * One tunnel health probe, hard-bounded by wall clock.
     *
     * The core's delay test is a blocking call with its own ~14 s budget, and a coroutine timeout
     * cannot interrupt a blocked thread — which is exactly why detection used to take ~30 s. The call
     * is therefore made on a worker thread and abandoned after [timeoutMs]; an abandoned test runs to
     * its own end in the background and the next round gets a fresh thread.
     */
    /**
     * Device-wide byte counter, used only for its *delta*. Reading it costs nothing and needs no
     * permission; it is how the health loop learns whether the tunnel was moving the user's traffic
     * while its own probe was waiting for a slot.
     */
    private fun tunnelBytes(): Long = try {
        TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
    } catch (_: Throwable) {
        0L
    }

    private fun probeLatencyBounded(timeoutMs: Long): Long {
        val core = coreController ?: return -1L
        val future = probeExecutor.submit(
            java.util.concurrent.Callable { core.measureDelay(GENERATE_204_URL) ?: -1L }
        )
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(false)
            -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun startTunnelHealthMonitor() {
        healthMonitorThread?.interrupt()
        val thread = Thread {
            var consecutiveFailures = 0
            var silentRounds = 0
            // The probes that decide whether the tunnel is alive also tell us what it costs to use it,
            // for free. The first HEALTH_BASELINE_SAMPLES fix the session's own round trip and then the
            // baseline stops moving — a session that got worse has to be compared with how it started,
            // or it would slowly agree to being worse.
            val samples = mutableListOf<Int>()
            var baseline = 0
            // Bytes moved on the device, sampled once per round, so a probe that times out while the
            // user is downloading something can be told apart from a tunnel that stopped carrying them.
            var bytesAt = System.currentTimeMillis()
            var bytesSeen = tunnelBytes()
            var busyLogged = false
            while (!stopRequested && !explicitStop) {
                try {
                    // Probing costs the user real data — every probe is a fresh TLS handshake through
                    // the tunnel — so the interval is tight only while things are fragile.
                    Thread.sleep(
                        if (System.currentTimeMillis() < healthFastUntil) {
                            HEALTH_INTERVAL_FAST_MS
                        } else {
                            HEALTH_INTERVAL_STEADY_MS
                        }
                    )
                } catch (_: InterruptedException) {
                    break
                }
                if (stopRequested || explicitStop) break
                if (VpnRuntime.state.value != VpnConnectionState.CONNECTED) break

                if (linkReportedDown) {
                    // The antenna is gone. Nothing to learn and nothing worth paying for: the
                    // network callback will wake us when it is back.
                    silentRounds = 0
                    consecutiveFailures = 0
                    continue
                }

                val now = System.currentTimeMillis()
                val bytesNow = tunnelBytes()
                val moved = (bytesNow - bytesSeen).coerceAtLeast(0L)
                val window = now - bytesAt
                bytesSeen = bytesNow
                bytesAt = now
                val busy = PathLadder.linkBusy(moved, window)
                if (busy && !busyLogged) {
                    Log.i(TAG, "Link busy (${moved / 1024}KB in ${window}ms): holding health judgements")
                    busyLogged = true
                } else if (!busy) {
                    busyLogged = false
                }

                val latency = probeLatencyBounded(HEALTH_PROBE_TIMEOUT_MS)

                if (latency >= 0L) {
                    if (consecutiveFailures > 0 || silentRounds > 0) {
                        Log.i(
                            TAG,
                            "Health probe recovered (${latency}ms) after " +
                                "$consecutiveFailures failure(s) and $silentRounds excused round(s)"
                        )
                    }
                    consecutiveFailures = 0
                    silentRounds = 0

                    // A probe that ran while the user was saturating the tunnel measures the queue,
                    // not the path: feeding it into the baseline would teach the app to accept a
                    // congested second, and comparing against it would later call a healthy session
                    // degraded.
                    if (!busy) {
                        samples.add(latency.toInt())
                        if (samples.size > HEALTH_SAMPLE_MEMORY) samples.removeAt(0)
                        if (baseline == 0 && samples.size >= HEALTH_BASELINE_SAMPLES) {
                            baseline = PathLadder.baselineMs(samples)
                            Log.i(TAG, "Health baseline set to ${baseline}ms over ${samples.size} probes")
                        } else if (baseline > 0 && samples.size > HEALTH_BASELINE_SAMPLES &&
                            System.currentTimeMillis() >= nextPathSearchAt &&
                            PathLadder.isDegraded(samples.takeLast(PathLadder.DEGRADED_ROUNDS), baseline)
                        ) {
                            Log.w(
                                TAG,
                                "Tunnel degraded: last ${PathLadder.DEGRADED_ROUNDS} probes " +
                                    "${samples.takeLast(PathLadder.DEGRADED_ROUNDS)}ms against ${baseline}ms baseline"
                            )
                            if (tryFasterPath()) break
                        }
                    }
                } else if (busy) {
                    // The user is moving bytes through it, so the tunnel is not dead: the probe simply
                    // could not get a slot. Counting this as a failure used to tear a working session
                    // down at the exact moment the user was busiest — twice in a row was enough.
                    Log.i(TAG, "Health probe starved while the link is busy ($moved bytes): excused")
                } else if (silentRounds < MAX_SILENT_ROUNDS && !deviceNetworkUp()) {
                    // The tunnel is not the only thing that can be down. Tearing it apart while
                    // the device's own link is gone only burns attempts, so hold for a while —
                    // but never indefinitely, in case the control probe is itself the blocked one.
                    silentRounds++
                    Log.w(TAG, "Health probe failed while the device network is down ($silentRounds)")
                } else {
                    consecutiveFailures++
                    Log.w(TAG, "Health probe failed ($consecutiveFailures/$HEALTH_FAILURE_LIMIT)")
                    if (consecutiveFailures >= HEALTH_FAILURE_LIMIT) {
                        handleTunnelLost()
                        break
                    }
                }
            }
        }
        thread.name = "ptunnel-health"
        thread.isDaemon = true
        healthMonitorThread = thread
        thread.start()
    }

    /**
     * A control probe that does not use the tunnel, so a failure here means the device's own path
     * is gone rather than ours being filtered. The app's sockets sit outside its own TUN, so this
     * really does test the underlying network. Several neutral hosts are tried because any one of
     * them can be filtered on Iranian paths while the user's link is fine.
     */
    private fun deviceNetworkUp(): Boolean =
        PathLadder.NEUTRAL_HOSTS.any { host -> probeHostAnswers(host) }

    private fun probeHostAnswers(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(host, NEUTRAL_PROBE_PORT),
                NEUTRAL_PROBE_TIMEOUT_MS
            )
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * A verified tunnel is not a permanent one: on Iranian paths a route can die mid-session while
     * the core still reports "running". Walk the recovery ladder instead of leaving the user on a
     * dead link.
     */
    private fun handleTunnelLost() {
        if (explicitStop || stopRequested) return
        // Retry what the core is actually running, which may be a detour the UI never chose.
        val liveConfig = activeConfig ?: prefs().getString(PREF_CONFIG, null)
        if (liveConfig.isNullOrBlank()) {
            VpnRuntime.set(
                VpnConnectionState.DISCONNECTED,
                "Connection lost. Tap connect to try again."
            )
            stopVpn()
            return
        }

        Log.w(TAG, "Tunnel lost; starting the recovery ladder")
        healthMonitorThread?.interrupt()
        healthMonitorThread = null
        // The detour that just died is no longer a place to go back to; it is restarted on success.
        stopFastLaneWatch()
        beginEpisode(reconnect = true)
        VpnRuntime.set(VpnConnectionState.CONNECTING)
        updateNotification("Reconnecting...")
        requestRecoveryStep()
    }

    /**
     * Moves a live but slowing session onto the box that answers faster, if there is one.
     *
     * This is the difference between "connected" and "connected well": the tunnel can stay up for
     * hours on a path whose round trip has doubled, and nothing in the ladder fires because nothing
     * actually failed. Called from the health thread, which is its own thread precisely so that a
     * blocking search costs nothing else, and it stops that thread when it dials — the new tunnel
     * gets a new monitor and a new baseline.
     *
     * @return true when the tunnel is being rebuilt elsewhere.
     */
    private fun tryFasterPath(): Boolean {
        val recovery = recovery ?: return false
        if (!recovery.automaticMode()) {
            Log.i(TAG, "Path search skipped: the user chose this country")
            nextPathSearchAt = System.currentTimeMillis() + PATH_SEARCH_INTERVAL_MS
            return false
        }
        nextPathSearchAt = System.currentTimeMillis() + PATH_SEARCH_INTERVAL_MS
        val liveConfig = activeConfig ?: prefs().getString(PREF_CONFIG, null).orEmpty()
        val profile = try {
            runBlocking { recovery.fasterPath(liveConfig) }
        } catch (t: Throwable) {
            Log.w(TAG, "Path search failed: ${t.message}")
            null
        } ?: return false

        if (stopRequested || explicitStop) return false
        Log.i(TAG, "Faster box found; rebuilding the tunnel onto it")
        healthMonitorThread?.interrupt()
        beginEpisode(reconnect = true)
        VpnRuntime.set(VpnConnectionState.CONNECTING)
        updateNotification("Reconnecting...")
        teardownTunnel()
        startVpn(profile, reconnect = true)
        return true
    }

    /**
     * Prepares the next rung of the ladder. Runs off the main thread because a step may talk to
     * the backend (fresh profiles, the out-of-band endpoint list, Cloudflare edge scores).
     */
    private fun requestRecoveryStep() {
        val liveConfig = activeConfig ?: prefs().getString(PREF_CONFIG, null).orEmpty()
        val attempt = recoveryAttempt
        recoveryJob?.cancel()
        recoveryJob = recoveryScope.launch {
            val backoff = PathLadder.backoffMs(attempt)
            if (backoff > 0L) delay(backoff)
            if (stopRequested || explicitStop) return@launch

            teardownTunnel()

            val outcome = runCatching { recovery?.next(liveConfig, attempt) }
                .getOrNull()
                ?: RecoveryOutcome.GiveUp("Connection lost. Tap connect to try again.")
            if (stopRequested || explicitStop) return@launch

            when (outcome) {
                is RecoveryOutcome.Dial -> {
                    onDetour = outcome.detour
                    startVpn(outcome.config, reconnect = true)
                }
                RecoveryOutcome.WaitForNetwork -> {
                    waitingForNetwork = true
                    Log.w(TAG, "Device network is down; holding recovery until it returns")
                    VpnRuntime.set(
                        VpnConnectionState.CONNECTING,
                        "Your internet connection is down."
                    )
                    updateNotification("Waiting for network...")
                    // Held for a bounded time, then re-tried — not abandoned. Holding forever would
                    // leave no way out of CONNECTING if the callback that wakes us never comes, and
                    // handing the session back to the user is the thing this app must not do.
                    recoveryScope.launch {
                        delay(WAIT_FOR_NETWORK_TIMEOUT_MS)
                        if (waitingForNetwork && !stopRequested && !explicitStop) {
                            keepRetrying("Your internet connection is down.")
                        }
                    }
                }
                is RecoveryOutcome.GiveUp -> {
                    keepRetrying(outcome.reason)
                }
            }
        }
    }

    /** The current rung did not come up. Advance, or give up and leave the user in charge. */
    private fun onAttemptFailed() {
        if (stopRequested || explicitStop) return

        // A comeback onto the direct path is not a rung of the ladder: it is an improvement we
        // chose to attempt, so a failure just means going back to what was working.
        if (returningToFastLane) {
            returningToFastLane = false
            // A TCP handshake says the path answers, not that the tunnel works. When a probe lies —
            // common enough here, where a handshake can be reset after the hello — back off instead
            // of interrupting a working session again at the same rate.
            fastLaneGapMs = (fastLaneGapMs * 2).coerceAtMost(FAST_LANE_MAX_GAP_MS)
            val detour = detourConfig
            if (!detour.isNullOrBlank()) {
                Log.w(TAG, "Direct path still will not carry the tunnel; returning to the detour")
                startVpn(detour, reconnect = true)
                return
            }
        }

        recoveryAttempt++
        if (recoveryAttempt >= PathLadder.MAX_ATTEMPTS) {
            Log.w(TAG, "Ladder exhausted after $recoveryAttempt attempts")
            keepRetrying("Connection lost. Looking for another path.")
            return
        }
        VpnRuntime.set(VpnConnectionState.CONNECTING)
        updateNotification("Reconnecting...")
        requestRecoveryStep()
    }

    /**
     * Ends the *ladder*, never the session.
     *
     * Reaching the bottom of the ladder used to mean `stopVpn()` and a request for the user to tap
     * the switch again. On an Iranian line that is the worst possible moment to stop: an address can
     * stay unreachable for twenty minutes and then come back on its own, and a session that gave up
     * in between leaves the user unprotected without telling them. So the rungs are walked, then
     * started again after [RETRY_FOREVER_INTERVAL_MS] — a new diagnosis each time, because the reason
     * for the failure may have changed while we were waiting.
     *
     * Only a stop the user asked for ends it.
     */
    private fun keepRetrying(reason: String) {
        if (stopRequested || explicitStop) return
        Log.w(TAG, "Recovery will keep trying: $reason")
        waitingForNetwork = false
        VpnRuntime.set(VpnConnectionState.CONNECTING, reason)
        updateNotification("Reconnecting...")
        recoveryJob?.cancel()
        recoveryJob = recoveryScope.launch {
            delay(RETRY_FOREVER_INTERVAL_MS)
            if (stopRequested || explicitStop) return@launch
            recoveryAttempt = 0
            recovery?.reset()
            requestRecoveryStep()
        }
    }

    /**
     * Watches for the fast path coming back.
     *
     * Two cheap probes have to agree before a working session is disturbed, the check runs at most
     * every few minutes, and a failed comeback drops straight back onto the detour. The cost is a
     * couple of seconds of interruption; the benefit is never sitting on a slower path for good
     * because of one unlucky moment during a block.
     */
    private fun startFastLaneWatch() {
        if (fastLaneJob?.isActive == true) return
        fastLaneJob = recoveryScope.launch {
            delay(FAST_LANE_FIRST_CHECK_MS)
            while (!stopRequested && !explicitStop) {
                val due = System.currentTimeMillis() - lastFastLaneReturnAt >= fastLaneGapMs
                if (onDetour && !returningToFastLane && due &&
                    VpnRuntime.state.value == VpnConnectionState.CONNECTED
                ) {
                    tryReturnToFastLane()
                }
                delay(FAST_LANE_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun stopFastLaneWatch() {
        fastLaneJob?.cancel()
        fastLaneJob = null
    }

    private suspend fun tryReturnToFastLane() {
        val recovery = recovery ?: return
        if (!recovery.directPathAnswers() || !recovery.directPathAnswers()) return

        val profile = recovery.directPathProfile() ?: return
        if (stopRequested || explicitStop) return

        Log.i(TAG, "Direct path answers again; returning to the fast lane")
        lastFastLaneReturnAt = System.currentTimeMillis()
        detourConfig = activeConfig
        returningToFastLane = true
        teardownTunnel()
        startVpn(profile, reconnect = true)
    }

    /** Stops the core and closes the TUN without tearing the service down. */
    private fun teardownTunnel() {
        try {
            coreController?.stopLoop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopLoop failed during teardown: ${t.message}")
        } finally {
            coreLoading = false
        }
        // startLoop() refuses to run while the previous loop still counts as running, so wait it
        // out here rather than racing it from the next attempt.
        var waited = 0
        while (coreController?.isRunning == true && waited < CORE_STOP_TIMEOUT_MS) {
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                break
            }
            waited += 100
        }
        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        }
        tunInterface = null
        verificationThread?.interrupt()
        verificationThread = null
        verificationStarted.set(false)
    }

    private fun initCoreEnvironment() {
        if (initialized) return
        Seq.setContext(applicationContext)
        val assetPath = getDir("assets", MODE_PRIVATE).absolutePath
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ptunnel"
        val base64Key = Base64.encodeToString(
            androidId.toByteArray(Charsets.UTF_8).copyOf(32),
            Base64.NO_PADDING or Base64.URL_SAFE
        )
        Libv2ray.initCoreEnv(assetPath, base64Key)
        initialized = true
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active VPN tunnel status"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification("Connecting...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShieldVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("PTunnel VPN")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(status))
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun stopVpn() {
        stopRequested = true
        recoveryJob?.cancel()
        recoveryJob = null
        stopFastLaneWatch()
        recovery?.reset()
        recoveryAttempt = 0
        waitingForNetwork = false
        reconnecting = false
        returningToFastLane = false
        onDetour = false
        detourConfig = null
        verificationThread?.interrupt()
        verificationThread = null
        healthMonitorThread?.interrupt()
        healthMonitorThread = null
        VpnRuntime.set(VpnConnectionState.DISCONNECTED, null)
        try {
            coreController?.stopLoop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopLoop failed: ${t.message}")
        }
        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        } finally {
            tunInterface = null
        }
        updateNotification("Disconnected")
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        explicitStop = true
        prefs().edit().remove(PREF_CONFIG).apply()
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopRequested = true
        recoveryJob?.cancel()
        recoveryJob = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        connectivityManager = null
        recoveryScope.cancel()
        verificationThread?.interrupt()
        verificationThread = null
        healthMonitorThread?.interrupt()
        healthMonitorThread = null
        try {
            coreController?.stopLoop()
        } catch (_: Throwable) {
        }
        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        }
        tunInterface = null
        VpnRuntime.set(VpnConnectionState.DISCONNECTED, null)
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        private const val TAG = "PTunnelVpnService"
        private const val CHANNEL_ID = "ptunnel_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "ptunnel_vpn"
        private const val PREF_CONFIG = "last_config"
        private const val GENERATE_204_URL = "https://www.gstatic.com/generate_204"

        /** The connection ceremony plays for this long before the UI may call it CONNECTED. */
        private const val CEREMONY_MIN_MS = 4_200L

        private const val CONNECT_TIMEOUT_MS = 20_000L

        /**
         * A rebuild of a live session is not a ceremony, but it is not a fast lane either.
         *
         * 12 s was measured against the link the complaints are about: an HSPA cell where the tunnel
         * builds and then the first probe through it takes longer than that — `Recovery attempt did
         * not come up in time` at 12 s, core stopped, rebuilt, same at 17:58:12 and 17:58:29, while
         * the very same session had already verified once in 1 s. Tearing down a tunnel that was one
         * round trip away from answering is how "it connects and then stops connecting" feels to the
         * user, so a rebuild gets the same leash as a first connect.
         */
        private const val RECONNECT_TIMEOUT_MS = 20_000L

        /** How long startLoop is given to release the previous core before a rebuild starts. */
        private const val CORE_STOP_TIMEOUT_MS = 3_000

        /** Delay between the two probes that decide whether a network change killed the tunnel. */
        private const val NETWORK_CHANGE_REPROBE_DELAY_MS = 1_500L

        // Post-connect health watchdog. The interval is adaptive: see HEALTH_INTERVAL_FAST_MS.
        // The old fixed 15s x 3 meant a dead path could go unnoticed for 45 seconds; with the probe
        // itself bounded, two fast rounds now detect a dead tunnel in ~6 s instead of ~30 s.
        private const val HEALTH_INTERVAL_FAST_MS = 3_000L

        /**
         * Upper bound for one health probe. The core's delay test has its own ~14 s budget, which
         * dominated detection time; a healthy probe answers in a few hundred milliseconds, so
         * anything past this is already a path worth rebuilding.
         */
        /**
         * How long one health probe may take.
         *
         * 3 s was measured against the link it has to survive: the same session verified with
         * `core latency 2997ms` on a congested HSPA cell, so a healthy tunnel was failing the probe
         * twice in a row and being torn down (`Tunnel lost; starting the recovery ladder` at
         * 18:54:08, rebuilt, verified again 9.5 s later). That is the "it disconnects on its own"
         * report. 6 s leaves the deadline above the latency the line actually shows.
         */
        private const val HEALTH_PROBE_TIMEOUT_MS = 6_000L

        /**
         * Steady-state interval. Every probe is a fresh TLS handshake through the tunnel, so a tight
         * interval for ever would cost the user tens of megabytes a day for no extra safety.
         */
        private const val HEALTH_INTERVAL_STEADY_MS = 12_000L

        /** How long after a connect or a network change the fast interval applies. */
        private const val HEALTH_FAST_WINDOW_MS = 90_000L

        /**
         * Consecutive failed probes before the tunnel is declared dead.
         *
         * Three, not two, because the deadline is now 6 s and one congested HSPA second can still
         * produce two slow probes in a row on a tunnel that is carrying traffic. Worst case to notice
         * a real death is `PathLadder.detectionWorstCaseMs` — 27 s in the fast window, which the
         * ladder then retries forever anyway.
         */
        private const val HEALTH_FAILURE_LIMIT = 3

        /** Probes that fix the session's own round trip before any later one is called a degradation. */
        private const val HEALTH_BASELINE_SAMPLES = 6

        /** How many recent probes are remembered. The baseline itself is frozen once and for all. */
        private const val HEALTH_SAMPLE_MEMORY = 40

        /** Shortest spacing between two searches for a faster box, so a search cannot thrash. */
        private const val PATH_SEARCH_INTERVAL_MS = 120_000L

        /**
         * How many health rounds may be excused while the device's own network looks down.
         * Bounded so a control probe that is itself filtered can never mask a dead tunnel.
         */
        private const val MAX_SILENT_ROUNDS = 4

        /** How long recovery waits for the device's own network before starting the ladder again. */
        private const val WAIT_FOR_NETWORK_TIMEOUT_MS = 60_000L

        /**
         * The gap between one exhausted ladder and the next. Short enough that an address which comes
         * back is used again within a breath, cheap enough to sit out a long outage: one diagnosis per
         * interval, and a diagnosis is a handful of TCP handshakes.
         */
        private const val RETRY_FOREVER_INTERVAL_MS = 15_000L

        /** Grace period before the first attempt to leave a detour, so a fresh block is not poked. */
        private const val FAST_LANE_FIRST_CHECK_MS = 120_000L

        private const val FAST_LANE_CHECK_INTERVAL_MS = 300_000L

        /** At most one comeback every so often, so a flapping direct path cannot make the app thrash. */
        private const val FAST_LANE_MIN_GAP_MS = 900_000L

        /** Ceiling for the grown-back-off cadence: one attempt every few hours is enough by then. */
        private const val FAST_LANE_MAX_GAP_MS = 4 * 60 * 60 * 1000L

        private const val NEUTRAL_PROBE_PORT = 443
        private const val NEUTRAL_PROBE_TIMEOUT_MS = 2_000

        @Volatile
        private var initialized = false

        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_XRAY_CONFIG = "extra_xray_config"
    }
}
