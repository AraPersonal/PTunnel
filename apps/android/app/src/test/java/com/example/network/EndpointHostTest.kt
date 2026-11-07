package com.example.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The name a control-plane endpoint is filed under in the fleet report.
 *
 * The server counts, per box, how many distinct networks reached it. That count is only worth
 * reading if every build writes the same shape of name here, so a URL that parses into nothing
 * silently drops the witness instead of inventing a second box that does not exist.
 */
class EndpointHostTest {

    @Test
    fun `a full url with a scheme, a port and a path keeps only the host`() {
        assertEquals(
            "86-54-42-124.nip.io",
            endpointHost("https://86-54-42-124.nip.io:8443/v1/user/profile")
        )
    }

    @Test
    fun `a host without a scheme is taken as it stands`() {
        assertEquals("206-1-101-67.sslip.io", endpointHost("206-1-101-67.sslip.io"))
        assertEquals("206-1-101-67.sslip.io", endpointHost(" 206-1-101-67.sslip.io/v1/ "))
    }

    @Test
    fun `nothing to name is null rather than an empty box`() {
        assertNull(endpointHost(null))
        assertNull(endpointHost(""))
        assertNull(endpointHost("   "))
        assertNull(endpointHost("https:///v1/"))
    }
}
