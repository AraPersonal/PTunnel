package com.example.vpn.core

/**
 * The events a tunnel engine reports upward. The service only ever needs these three, which is why the
 * contract is small: startup begins verification, shutdown is logged, and status lines are shown.
 */
interface TunnelCoreListener {
    fun onCoreStarted()
    fun onCoreStopped()
    fun onCoreStatus(code: Long, message: String?)
}

/**
 * The one contract every engine satisfies. libv2ray is gone (ADR-0005); the Go Agent in apps/agent will
 * implement this with Xray for Reality/mKCP and Hysteria2 for QUIC, and until that lands the service talks
 * to [NoopTunnelCore], which refuses honestly instead of pretending a tunnel is up.
 *
 * Keeping the shape of the old calls (startLoop/stopLoop/isRunning/measureDelay) is deliberate: the
 * ladder, health probes and verification logic in ShieldVpnService are measured and tested, and they
 * should not have to be rewritten when the engine behind them changes.
 */
interface TunnelCore {
    val isRunning: Boolean

    /** Starts the engine on the given TUN file descriptor with [config] (a JSON profile). */
    fun startLoop(config: String, tunFd: Int)

    /** Stops the engine; must return only when the loop has released the previous session. */
    fun stopLoop()

    /** Latency in milliseconds for a single probe, or -1 when the engine cannot answer. */
    fun measureDelay(url: String): Long

    fun uplinkBytes(): Long
    fun downlinkBytes(): Long
}

/**
 * Placeholder engine: reports itself as never running so the ladder advances instead of hanging, and
 * throws on start so the failure path in the service logs a message that says what is missing.
 */
class NoopTunnelCore(private val listener: TunnelCoreListener) : TunnelCore {
    override val isRunning: Boolean
        get() = false

    override fun startLoop(config: String, tunFd: Int) {
        listener.onCoreStatus(
            1,
            "Tunnel core is not implemented yet: the Agent in apps/agent replaces libv2ray (ADR-0005)"
        )
        throw UnsupportedOperationException(
            "TunnelCore not implemented; build the Agent in apps/agent first"
        )
    }

    override fun stopLoop() = Unit

    override fun measureDelay(url: String): Long = -1L

    override fun uplinkBytes(): Long = -1L

    override fun downlinkBytes(): Long = -1L
}
