package com.example.vpn

/**
 * What addresses and routes a tunnel may claim from the platform.
 *
 * Android refuses to install an IPv6 default route on a network that has no IPv6 address, and it does
 * not say so: `establish()` throws out of `jniSetAddresses`, the app classifies that as "no network to
 * build the tunnel on", and every retry fails the same way while a validated mobile network is up.
 * Measured 2026-11-04 on a Shatel SIM that fell back from LTE to HSPA: three attempts in 56 seconds,
 * each dead in ~70 ms, `Active default network: 753` the whole time. That is the "the VPN went off and
 * never came back, on some operators" report — the tunnel has to claim IPv6 only when the link has it.
 */
object TunnelInterface {

    const val IPV4_ADDRESS = "172.19.0.1"
    const val IPV4_PREFIX = 30
    const val IPV6_ADDRESS = "fd00::1"
    const val IPV6_PREFIX = 126

    /** Addresses to claim. Without an IPv6 on the link, the IPv6 claim is what kills `establish()`. */
    fun addresses(ipv6Allowed: Boolean): List<Pair<String, Int>> =
        if (ipv6Allowed) listOf(IPV4_ADDRESS to IPV4_PREFIX, IPV6_ADDRESS to IPV6_PREFIX)
        else listOf(IPV4_ADDRESS to IPV4_PREFIX)

    /** Default routes to claim, under the same rule. */
    fun routes(ipv6Allowed: Boolean): List<String> =
        if (ipv6Allowed) listOf("0.0.0.0", "::") else listOf("0.0.0.0")
}
