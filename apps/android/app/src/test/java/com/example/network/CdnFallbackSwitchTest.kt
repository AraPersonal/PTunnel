package com.example.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The auto-switch step: once the server IP is judged blocked, the app re-points the `cdn` outbound
 * at one specific Cloudflare edge address while keeping the hostname the edge and our origin both
 * expect. Everything else in the profile has to stay untouched, or a blocked IP would be traded
 * for a broken tunnel.
 *
 * Robolectric is only here for a working `org.json` on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class CdnFallbackSwitchTest {

    private val profile = """
        {
          "outbounds": [
            { "tag": "reality", "settings": { "vnext": [ { "address": "86.54.42.124", "port": 443 } ] } },
            { "tag": "cdn", "settings": { "vnext": [ { "address": "cdn.ptu.beauty", "port": 2083 } ] } }
          ]
        }
    """.trimIndent()

    private fun vnext(patched: String, index: Int): JSONObject = JSONObject(patched)
        .getJSONArray("outbounds")
        .getJSONObject(index)
        .getJSONObject("settings")
        .getJSONArray("vnext")
        .getJSONObject(0)

    @Test
    fun `the chosen edge is written into the cdn outbound with its port intact`() {
        val patched = VpnBackendRepository().withCdnAddress(profile, "172.64.80.1")

        assertEquals("172.64.80.1", vnext(patched, 1).getString("address"))
        assertEquals(2083, vnext(patched, 1).getInt("port"))
    }

    @Test
    fun `the tunnel outbounds keep dialling our own server`() {
        val patched = VpnBackendRepository().withCdnAddress(profile, "172.64.80.1")

        assertEquals("86.54.42.124", vnext(patched, 0).getString("address"))
        assertEquals(443, vnext(patched, 0).getInt("port"))
    }
}
