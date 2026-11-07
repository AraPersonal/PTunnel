package com.example.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app is allowed to leave the direct path for the Cloudflare-fronted one.
 *
 * The two signals are deliberately asymmetric: a server address that does not answer at all is
 * acted on immediately, because that is the block the fallback actually fixes and waiting would
 * leave the first users cut off. A server that answers while the tunnel still fails is ambiguous
 * from one device, so it only counts when the fleet corroborated it from other networks.
 */
class CdnFallbackDecisionTest {

    @Test
    fun `an address that does not answer falls back immediately, without corroboration`() {
        assertTrue(
            shouldFallBackToCdn(REACHABILITY_SERVER_UNREACHABLE, fleetVerdict = null)
        )
    }

    @Test
    fun `a failing tunnel falls back once the fleet corroborates it`() {
        assertTrue(
            shouldFallBackToCdn(
                REACHABILITY_TUNNEL_FAILED,
                fleetVerdict = REACHABILITY_SERVER_UNREACHABLE
            )
        )
    }

    @Test
    fun `a failing tunnel on its own is not enough to change paths`() {
        assertFalse(shouldFallBackToCdn(REACHABILITY_TUNNEL_FAILED, fleetVerdict = null))
        assertFalse(shouldFallBackToCdn(REACHABILITY_TUNNEL_FAILED, fleetVerdict = "ok"))
        assertFalse(
            shouldFallBackToCdn(REACHABILITY_TUNNEL_FAILED, fleetVerdict = "insufficient_data")
        )
    }

    @Test
    fun `a device whose own network is broken is never pushed onto the fallback`() {
        assertFalse(shouldFallBackToCdn(REACHABILITY_DEVICE_NETWORK, fleetVerdict = null))
        assertFalse(
            shouldFallBackToCdn(
                REACHABILITY_DEVICE_NETWORK,
                fleetVerdict = REACHABILITY_SERVER_UNREACHABLE
            )
        )
    }

    @Test
    fun `an explicit server answer never switches paths`() {
        assertFalse(shouldFallBackToCdn(null, fleetVerdict = REACHABILITY_SERVER_UNREACHABLE))
    }
}
