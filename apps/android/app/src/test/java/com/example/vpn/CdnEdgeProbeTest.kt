package com.example.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * The CDN edge probe has to be able to tell "the socket opened" from "the path works".
 *
 * Measured on an Iranian line on 2026-11-04: every Cloudflare edge answered a TCP connect in about
 * 50 ms and the connection was then reset during the TLS ClientHello. A probe built on connect alone
 * therefore called a dead front healthy, the recovery ladder rebuilt the tunnel onto it, and the user
 * watched "Connecting…" for twenty-four seconds before another switch. A plain ServerSocket that never
 * speaks TLS is exactly what that line behaves like, so it is what this test stands in for.
 */
class CdnEdgeProbeTest {

    @Test
    fun `a host that accepts the socket but never completes TLS is not a live edge`() = runBlocking {
        val server = ServerSocket(0)
        try {
            val acceptors = Thread {
                repeat(4) {
                    try {
                        // Accept, then hold the connection open without ever answering the handshake.
                        val peer = server.accept()
                        Thread.sleep(1_500)
                        peer.close()
                    } catch (_: Exception) {
                        return@repeat
                    }
                }
            }
            acceptors.isDaemon = true
            acceptors.start()

            val connectSays = VpnUriParser.measureRealTcpPing("127.0.0.1", server.localPort)
            val handshakeSays =
                VpnUriParser.measureTlsHandshakeMs("127.0.0.1", server.localPort, "cdn.ptu.beauty")

            assertTrue(
                "the connect probe is the one that lies: it must report a number, got $connectSays",
                connectSays >= 0
            )
            assertEquals(
                "a TLS handshake that never completed must be reported as a dead edge",
                -1,
                handshakeSays
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `an unreachable host is reported dead by both probes`() = runBlocking {
        // Port 1 on loopback is refused immediately, so this only checks the failure contract.
        assertEquals(-1, VpnUriParser.measureRealTcpPing("127.0.0.1", 1))
        assertEquals(-1, VpnUriParser.measureTlsHandshakeMs("127.0.0.1", 1, "cdn.ptu.beauty"))
    }

    @Test
    fun `a probe with no name to offer does not invent one`() = runBlocking {
        val server = ServerSocket(0)
        try {
            assertEquals(-1, VpnUriParser.measureTlsHandshakeMs("127.0.0.1", server.localPort, ""))
            assertEquals(-1, VpnUriParser.measureTlsHandshakeMs("", 443, "cdn.ptu.beauty"))
        } finally {
            server.close()
        }
    }
}
