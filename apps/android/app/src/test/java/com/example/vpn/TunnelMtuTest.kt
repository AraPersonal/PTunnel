package com.example.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MTU the tunnel is allowed to hand out.
 *
 * Every case here is a shape of link that has been seen on a real connection. The old constant was
 * 1420, and the operator's LTE line — measured, not assumed — reports 1358 with every larger packet
 * discarded outright: a 1378-byte packet is 100% loss, 1358 passes. A tunnel MTU above that number
 * does not slow the path down, it removes packets from it, and the recovery for a lost packet on a
 * 200 ms round trip is measured in tens of seconds.
 */
class TunnelMtuTest {

    @Test
    fun `the measured cellular link leaves room for the encapsulation instead of overshooting it`() {
        val mtu = TunnelMtu.forLink(1358)
        assertTrue("mtu $mtu must fit inside the link", mtu + TunnelMtu.ENCAPSULATION_BYTES <= 1358)
        assertEquals(1238, mtu)
    }

    @Test
    fun `an ordinary ethernet link is capped below its own size, because the far hops are unknown`() {
        val mtu = TunnelMtu.forLink(1500)
        assertEquals(1380, mtu)
        assertTrue(mtu <= TunnelMtu.MAXIMUM)
    }

    @Test
    fun `an unknown link gets the safe default rather than the ethernet number`() {
        assertEquals(TunnelMtu.FALLBACK, TunnelMtu.forLink(0))
        assertEquals(TunnelMtu.FALLBACK, TunnelMtu.forLink(-1))
    }

    @Test
    fun `a tight link is never asked to go below the floor a TLS record needs`() {
        assertEquals(TunnelMtu.HARD_FLOOR, TunnelMtu.forLink(1200))
        assertEquals(TunnelMtu.HARD_FLOOR, TunnelMtu.forLink(900))
    }

    @Test
    fun `the fallback itself would have fragmented the link we measured`() {
        // Recorded so that nobody raises FALLBACK back towards 1420 without reading the measurement
        // above. 1280 + the encapsulation it is expected to carry stays inside 1358; 1420 did not.
        assertTrue(TunnelMtu.FALLBACK + TunnelMtu.ENCAPSULATION_BYTES <= 1358)
    }
}
