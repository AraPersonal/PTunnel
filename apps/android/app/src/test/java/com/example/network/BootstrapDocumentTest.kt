package com.example.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bootstrap document is what the app falls back to when every endpoint it was built with is
 * blocked, so the shape of that payload is load-bearing: if it changes and the parsing does not
 * follow, the rescue path silently stops replacing `baseUrls` and `hostPins` — which is exactly the
 * failure mode that leaves a user with no way back in.
 *
 * Robolectric is used only because this parsing is written against org.json, the Android flavour;
 * nothing here touches a device, a network of its own, or the pinned production addresses.
 */
@RunWith(RobolectricTestRunner::class)
class BootstrapDocumentTest {

    private var server: HttpServer? = null
    private var received = 0

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    private fun payload(api: String, extra: String = ""): JSONObject {
        // Joined rather than interpolated: with `extra` empty, a naive template leaves a trailing
        // comma and org.json rejects it, which reads like a parser bug and is not one.
        val body = listOf("\"v\":1", api.trimEnd(','), extra.trimEnd(','))
            .filter { it.isNotBlank() }
            .joinToString(",")
        return JSONObject("{$body}")
    }

    @Test
    fun `a payload with no api array is rejected and the fallbacks stay put`() {
        val repository = VpnBackendRepository()
        val before = repository.serverProbeHosts()
        assertFalse(repository.applyBootstrap(JSONObject("""{"v":1,"mirrors":["https://x/y"]}""")))
        assertEquals(before, repository.serverProbeHosts())
        assertFalse(repository.applyBootstrap(JSONObject("""{"v":1,"api":[]}""")))
    }

    @Test
    fun `pins from the payload are merged with the ones built into the app`() {
        val repository = VpnBackendRepository()
        val ok = repository.applyBootstrap(
            payload(
                api = """"api":["https://box-a.example/v1/"],""",
                extra = """"pins":{"spare.example":"9.9.9.9"}"""
            )
        )
        assertTrue(ok)
        val probes = repository.serverProbeHosts()
        // The new address must be probed by the ladder...
        assertTrue("missing the published pin", probes.contains("9.9.9.9"))
        // ...and the addresses the app was built with must survive the merge.
        assertTrue("built-in pins were dropped", probes.contains("86.54.42.124"))
        assertTrue("built-in pins were dropped", probes.contains("206.1.101.67"))
    }

    @Test
    fun `the first api in the payload is the one a report actually goes to`() {
        val listener = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = listener
        listener.createContext("/v1/reachability") { exchange ->
            received++
            val bytes = """{"ok":true}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        listener.start()
        val local = "http://127.0.0.1:${listener.address.port}/v1/"

        val repository = VpnBackendRepository()
        assertTrue(repository.applyBootstrap(payload(api = """"api":["$local","https://later.example/v1/"],""")))

        val reported = runBlocking { repository.reportReachability(REACHABILITY_TUNNEL_FAILED) }

        assertTrue("the reachable endpoint did not carry the report", reported)
        assertEquals("a candidate behind the first one was contacted", 1, received)
        // Endpoint order is what keeps a filtered hostname from costing a timeout before the healthy
        // one: the published order, not the order the app was built with, now leads.
        assertEquals(local, repository.servedEndpoint)
    }
}
