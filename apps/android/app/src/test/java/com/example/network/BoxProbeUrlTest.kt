package com.example.network

import com.example.model.VpnLocation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which URL a box is measured through before the app is allowed to move a session onto it.
 *
 * This mattered because the two names a box publishes are not equally reachable: Turkey's
 * `api_base` is a `nip.io` name — the one suffix measured working on Iranian lines — while its
 * `domain` field still carries the `sslip.io` name that some of those lines drop. Probing the
 * second and connecting through the first would call a healthy server unreachable, which is exactly
 * what the ranking is supposed to prevent.
 */
class BoxProbeUrlTest {

    private val repository = VpnBackendRepository()

    @Test
    fun `a box is probed on the same name its API is served on`() {
        val turkey = VpnLocation(
            id = "srv_turkey",
            name = "Turkey",
            country = "Turkey",
            host = "206.1.101.67",
            domain = "206-1-101-67.sslip.io",
            apiBase = "https://206-1-101-67.nip.io/v1/"
        )

        assertEquals("https://206-1-101-67.nip.io/s", repository.boxProbeUrl(turkey))
    }

    @Test
    fun `the status document is asked for at the origin, not under the api path`() {
        // nginx serves /s at the root; /v1/s would 404 on a healthy box and read as a dead one.
        val germany = VpnLocation(
            id = "srv_main", name = "Germany", country = "Germany", host = "86.54.42.124",
            domain = "ptu.beauty", apiBase = "https://86-54-42-124.nip.io/v1/"
        )

        assertEquals("https://86-54-42-124.nip.io/s", repository.boxProbeUrl(germany))
    }

    @Test
    fun `a location with no published api base falls back to its own name`() {
        val bare = VpnLocation(
            id = "srv_bare", name = "Bare", country = "Bare", host = "198.51.100.9",
            domain = "198-51-100-9.nip.io", apiBase = ""
        )

        assertEquals("https://198-51-100-9.nip.io/s", repository.boxProbeUrl(bare))
    }

    @Test
    fun `with neither name the host itself is still tried`() {
        val hostOnly = VpnLocation(
            id = "srv_host", name = "Host", country = "Host", host = "198.51.100.10",
            domain = "", apiBase = ""
        )

        assertEquals("https://198.51.100.10/s", repository.boxProbeUrl(hostOnly))
    }
}
