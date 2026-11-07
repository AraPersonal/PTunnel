package com.example.model

enum class VpnConnectionState(val displayLabel: String) {
    DISCONNECTED("DISCONNECTED"),
    CONNECTING("CONNECTING..."),
    CONNECTED("CONNECTED")
}

enum class VpnProtocol(val displayName: String, val portInfo: String) {
    VLESS_REALITY("VLESS • XTLS-Reality", "TCP/gRPC 443 • TLS 1.3"),
    VMESS_WS("VMess • WebSocket", "WSS 443 / 2053 • AEAD"),
    TROJAN_GFW("Trojan • TLS", "TCP 443 • Multiplexed"),
    WIREGUARD("WireGuard • Kernel/Go", "UDP 51820 • ChaCha20-Poly1305"),
    SHADOWSOCKS("Shadowsocks • AEAD-2022", "TCP/UDP 8388 • 2022-blake3")
}

enum class GlassRefractionLevel(val displayName: String, val description: String) {
    SUBTLE("Subtle Frost", "Minimal edge highlight & soft glass rim"),
    BALANCED("Liquid Standard", "Balanced inner white glow & refraction"),
    ULTRA("Deep Liquid Glass", "Enhanced specular rim & optical depth")
}

/**
 * Complete VPN Node model containing all connection, port, and credential parameters
 * required by the backend/core tunnel engine (Xray/V2Ray, Sing-box, WireGuard, OpenVPN).
 */
data class VpnServer(
    val id: String,
    val flagEmoji: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocolScheme: String, // e.g., "vless", "vmess", "trojan", "ss", "wg"
    val credential: String,     // UUID, Password, or Private/Public Key
    val security: String = "tls",
    val transport: String = "tcp",
    val sni: String = "",
    val rawUri: String,         // Full original URI/Config for direct core engine consumption
    val pingMs: Int = 42
)

data class VpnLocation(
    val id: String,
    val name: String,
    val country: String,
    val host: String,
    val domain: String,
    val flag: String = "",
    /**
     * The control channel that serves *this* box. Selecting a country means talking to that box,
     * which is also what makes the tunnel profile carry that box's address and keys.
     */
    val apiBase: String = ""
)

enum class LocationMode { AUTO, MANUAL }

/**
 * Server-verified User Profile & Data Package Quota model.
 * Tracks the user's purchased data package (`totalBytes`) and real-time usage (`usedBytes`).
 */
data class UserProfile(
    val uid: String,
    val displayName: String,
    val email: String,
    val authToken: String,
    val planTitle: String,
    val usedBytes: Long,
    val totalBytes: Long,
    val expiryDaysRemaining: Int = 30,
    val subscriptionUrl: String = "",
    val lastSyncedMillis: Long = System.currentTimeMillis(),
    val username: String = "",
    val status: String = "active",
    val expiresAt: String? = null
) {
    val usageFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    val remainingBytes: Long
        get() = (totalBytes - usedBytes).coerceAtLeast(0L)
}

/**
 * Backend & Tunnel Core configuration (managed automatically in backend, not exposed to end-users).
 */
data class BackendEndpointConfig(
    /**
     * The host every call tries first. It is deliberately not `ptu.beauty`: on a measured Iranian
     * line any handshake whose SNI ends in the public domain is dropped, and the requests that do
     * not walk the endpoint list — the writes, which must never be retried elsewhere — would then
     * fail for the users who need them most. This name is pinned to the server address, so it needs
     * no DNS to work.
     */
    val apiBaseUrl: String = "https://86-54-42-124.nip.io/v1/",
    val defaultPort: Int = 443,
    val localSocksPort: Int = 10808,
    val tunnelMtu: Int = 1500,
    val primaryDns: String = "1.1.1.1"
)

data class VpnSettings(
    // Backend & Core auto-managed parameters (preserved for backend integration)
    val protocol: VpnProtocol = VpnProtocol.VLESS_REALITY,
    val killSwitchEnabled: Boolean = true,
    val splitTunnelingEnabled: Boolean = false,
    val zeroKnowledgeDnsEnabled: Boolean = true,
    val backendConfig: BackendEndpointConfig = BackendEndpointConfig(),
    // User-facing Appearance & Interface preferences (preserving the fixed dark green palette)
    val glassRefractionLevel: GlassRefractionLevel = GlassRefractionLevel.BALANCED,
    val showConnectionTimer: Boolean = true,
    val showLiveSpeed: Boolean = true,
    val showServerPing: Boolean = true,
    val compactServerList: Boolean = false,
    val airdropWaveAnimationEnabled: Boolean = true
)
