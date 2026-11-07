package com.example.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The addresses and routes the tunnel claims from Android.
 *
 * On 2026-11-04 a Shatel SIM fell back from LTE to HSPA, which carries no IPv6 at all. The builder
 * still claimed `fd00::1/126` and a `::/0` route, `establish()` threw out of `jniSetAddresses`, the
 * service logged "No network to build the tunnel on" and retried the identical claim forever — while
 * `Active default network` was a validated mobile network the whole time. These two shapes are the
 * whole difference between a session that comes back and one that never does.
 */
class TunnelInterfaceTest {

    @Test
    fun `an IPv4-only link gets an IPv4-only tunnel`() {
        assertEquals(listOf("172.19.0.1" to 30), TunnelInterface.addresses(ipv6Allowed = false))
        assertEquals(listOf("0.0.0.0"), TunnelInterface.routes(ipv6Allowed = false))
    }

    @Test
    fun `a link that carries IPv6 keeps both halves`() {
        assertEquals(
            listOf("172.19.0.1" to 30, "fd00::1" to 126),
            TunnelInterface.addresses(ipv6Allowed = true)
        )
        assertEquals(listOf("0.0.0.0", "::"), TunnelInterface.routes(ipv6Allowed = true))
    }
}
