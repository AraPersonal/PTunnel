package com.example.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Which failures are allowed to end a session.
 *
 * The regression this guards: a blocked server makes the profile refresh throw a connect timeout
 * whose text contains the device's own ephemeral source port. When that port happens to read
 * "40106", matching the message for "401" logs the user out mid-outage and deletes the cached
 * fallback profile — measured on a real phone.
 */
class SessionFailureTest {

    @Test
    fun `a connect timeout that mentions a port containing 401 is not a session expiry`() {
        val timeout = SocketTimeoutException(
            "failed to connect to /86.54.42.124 (port 443) from /172.20.66.131 (port 40106) after 8000ms"
        )

        assertFalse(shouldEndSession(timeout))
    }

    @Test
    fun `a refused token ends the session`() {
        assertTrue(shouldEndSession(failureForRefreshCode(401)))
    }

    @Test
    fun `other server answers keep the session`() {
        assertFalse(shouldEndSession(failureForRefreshCode(500)))
        assertFalse(shouldEndSession(failureForRefreshCode(403)))
        assertFalse(shouldEndSession(null))
    }

    @Test
    fun `a session expiry is still a definitive failure so it is never retried elsewhere`() {
        assertTrue(failureForRefreshCode(401) is DefinitiveFailure)
    }
}
