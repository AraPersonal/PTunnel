package com.example.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

/**
 * The decision table behind "is our server blocked, or is this device simply offline?".
 *
 * This is the branch that decides whether the app blames the network, blames the server, or
 * switches to the fallback path, so it needs coverage that does not depend on a phone. The probe
 * targets are injected: a loopback listener stands in for a reachable host and a just-closed
 * loopback port stands in for an unreachable one. The code path under test is the real one.
 */
class ReachabilityDetectionTest {

    /** A listening socket that answers the TCP handshake; never accepted, which is enough. */
    private fun listening(): ServerSocket = ServerSocket(0)

    /** A port that is guaranteed to refuse connections: bind, read the number, release it. */
    private fun refusing(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `server answers but the control host does not, so the device network is blamed`() =
        runBlocking {
            val server = listening()
            try {
                val verdict = VpnBackendRepository().classifyReachability(
                    serverHost = LOOPBACK,
                    serverPort = server.localPort,
                    neutralHost = LOOPBACK,
                    neutralPort = refusing()
                )
                assertEquals(REACHABILITY_DEVICE_NETWORK, verdict)
            } finally {
                server.close()
            }
        }

    @Test
    fun `both the control host and the server answer, so the tunnel itself failed`() =
        runBlocking {
            val server = listening()
            val control = listening()
            try {
                val verdict = VpnBackendRepository().classifyReachability(
                    serverHost = LOOPBACK,
                    serverPort = server.localPort,
                    neutralHost = LOOPBACK,
                    neutralPort = control.localPort
                )
                assertEquals(REACHABILITY_TUNNEL_FAILED, verdict)
            } finally {
                server.close()
                control.close()
            }
        }

    @Test
    fun `the control host answers but the server refuses, so the server is unreachable`() =
        runBlocking {
            val control = listening()
            try {
                val verdict = VpnBackendRepository().classifyReachability(
                    serverHost = LOOPBACK,
                    serverPort = refusing(),
                    neutralHost = LOOPBACK,
                    neutralPort = control.localPort
                )
                assertEquals(REACHABILITY_SERVER_UNREACHABLE, verdict)
            } finally {
                control.close()
            }
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
