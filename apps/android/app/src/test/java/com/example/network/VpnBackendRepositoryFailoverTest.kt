package com.example.network

import com.example.model.BackendEndpointConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Failover behaviour of the control channel, without touching production hosts.
 *
 * The scenario these tests exist for is the one measured on the phone: a filtered hostname sits in
 * front of a healthy one and used to cost a full timeout per request, and after the preference fix an
 * explicit choice was still being outranked by the endpoint that happened to answer last. A dead
 * endpoint is `127.0.0.1:1`, which refuses instantly instead of waiting, so a mistake here shows up
 * as an assertion failure rather than a slow test.
 */
class VpnBackendRepositoryFailoverTest {

    private lateinit var server: HttpServer
    private var requests = 0
    private lateinit var liveBase: String
    private val deadBase = "http://127.0.0.1:1/v1/"

    @Before
    fun setUp() {
        requests = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/locations") { exchange ->
            requests++
            val body = """
                {"locations":[{"id":"srv_local","name":"Local","country":"Nowhere","host":"127.0.0.1",
                "domain":"localhost","flag":"x","api_base":"https://86-54-42-124.nip.io/v1/"}]}
            """.trimIndent()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        liveBase = "http://127.0.0.1:${server.address.port}/v1/"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `a refused endpoint is skipped and the one after it serves`() {
        val repository = VpnBackendRepository()
        // Preferred endpoint leads and it is the dead one: failover has to walk past it.
        repository.preferEndpoint(deadBase)
        val config = BackendEndpointConfig(apiBaseUrl = liveBase)

        val result = runBlocking { repository.fetchLocations(config) }

        assertTrue("expected a successful fetch, got $result", result.isSuccess)
        val locations = result.getOrNull().orEmpty()
        assertEquals(1, locations.size)
        assertEquals("Nowhere", locations[0].country)
        // The field is written as api_base on the wire; the model must pick it up.
        assertEquals("https://86-54-42-124.nip.io/v1/", locations[0].apiBase)
        // And the repository must remember which endpoint carried it.
        assertEquals(liveBase, repository.servedEndpoint)
        assertEquals(1, requests)
    }

    @Test
    fun `a remembered healthy endpoint is tried first and nothing else is asked`() {
        val repository = VpnBackendRepository()
        repository.preferEndpoint(liveBase)
        val config = BackendEndpointConfig(apiBaseUrl = deadBase)

        val result = runBlocking { repository.fetchLocations(config) }

        assertTrue(result.isSuccess)
        assertEquals(liveBase, repository.servedEndpoint)
        assertEquals("the preferred endpoint answered first, so no candidate behind it was contacted", 1, requests)
    }

    @Test
    fun `a broken remembered endpoint is forgotten instead of blocking the next attempt`() {
        val repository = VpnBackendRepository()
        repository.preferEndpoint(deadBase)
        assertEquals(deadBase, repository.servedEndpoint)
        runBlocking { repository.fetchLocations(BackendEndpointConfig(apiBaseUrl = liveBase)) }
        assertEquals(liveBase, repository.servedEndpoint)
    }

    @Test
    fun `an empty preference leaves the configured order untouched`() {
        val repository = VpnBackendRepository()
        repository.preferEndpoint(null)
        repository.preferEndpoint("   ")
        assertEquals(null, repository.servedEndpoint)
    }
}
