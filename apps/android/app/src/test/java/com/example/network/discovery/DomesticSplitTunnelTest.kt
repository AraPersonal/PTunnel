package com.example.network.discovery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DomesticSplitTunnelTest {

    @Before
    fun setUp() {
        // Reset or initialize default domestic rules
        DomesticSplitTunnel.updateDomesticRules(listOf(".ir"))
    }

    @Test
    fun `default rules match ir domains and reject foreign domains`() {
        assertTrue("digikala.ir should be domestic", DomesticSplitTunnel.isDomesticHost("digikala.ir"))
        assertTrue("sub.bank.ir should be domestic", DomesticSplitTunnel.isDomesticHost("sub.bank.ir"))
        assertTrue("varzesh3.ir should be domestic", DomesticSplitTunnel.isDomesticHost("varzesh3.ir"))
        assertTrue("IR uppercase should be domestic", DomesticSplitTunnel.isDomesticHost("SNAPP.IR"))

        assertFalse("google.com must not be domestic", DomesticSplitTunnel.isDomesticHost("google.com"))
        assertFalse("cloudflare.com must not be domestic", DomesticSplitTunnel.isDomesticHost("cloudflare.com"))
        assertFalse("github.com must not be domestic", DomesticSplitTunnel.isDomesticHost("github.com"))
        assertFalse("empty host must not be domestic", DomesticSplitTunnel.isDomesticHost(""))
        assertFalse("null host must not be domestic", DomesticSplitTunnel.isDomesticHost(null))
    }

    @Test
    fun `private LAN and loopback IPs are treated as domestic direct`() {
        assertTrue("127.0.0.1 loopback must be direct", DomesticSplitTunnel.isDomesticHost("127.0.0.1"))
        assertTrue("192.168.1.1 private subnet must be direct", DomesticSplitTunnel.isDomesticHost("192.168.1.1"))
        assertTrue("10.0.0.5 private subnet must be direct", DomesticSplitTunnel.isDomesticHost("10.0.0.5"))

        assertFalse("1.1.1.1 public IP must not be domestic by default", DomesticSplitTunnel.isDomesticHost("1.1.1.1"))
        assertFalse("8.8.8.8 public IP must not be domestic by default", DomesticSplitTunnel.isDomesticHost("8.8.8.8"))
    }

    @Test
    fun `custom domestic suffixes from operators update dynamically`() {
        DomesticSplitTunnel.updateDomesticRules(listOf(".telecom", ".mci.local"))

        assertTrue("telecom suffix should be domestic", DomesticSplitTunnel.isDomesticHost("portal.telecom"))
        assertTrue("mci.local suffix should be domestic", DomesticSplitTunnel.isDomesticHost("auth.mci.local"))
        assertTrue("standard ir should still be domestic", DomesticSplitTunnel.isDomesticHost("test.ir"))
        assertFalse("foreign domain still not domestic", DomesticSplitTunnel.isDomesticHost("example.org"))
    }

    @Test
    fun `injectDomesticRouting adds direct outbound and domestic routing rules to xray config`() {
        val baseConfig = """
            {
                "outbounds": [
                    {
                        "tag": "proxy",
                        "protocol": "vless"
                    }
                ],
                "routing": {
                    "rules": []
                }
            }
        """.trimIndent()

        val injected = DomesticSplitTunnel.injectDomesticRouting(baseConfig)
        val root = JSONObject(injected)

        // Check direct outbound exists
        val outbounds = root.getJSONArray("outbounds")
        var hasDirect = false
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.getJSONObject(i)
            if (ob.getString("tag") == "direct" && ob.getString("protocol") == "freedom") {
                hasDirect = true
                break
            }
        }
        assertTrue("Direct freedom outbound must be present in config", hasDirect)

        // Check routing rules
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertTrue("At least two domestic rules should be prepended", rules.length() >= 2)

        val firstRule = rules.getJSONObject(0)
        assertEquals("direct", firstRule.getString("outboundTag"))
        assertTrue(firstRule.has("domain"))
        val domains = firstRule.getJSONArray("domain")
        var hasDomainIr = false
        for (i in 0 until domains.length()) {
            if (domains.getString(i) == "domain:ir") {
                hasDomainIr = true
                break
            }
        }
        assertTrue("domain:ir must be in first domestic routing rule", hasDomainIr)

        val secondRule = rules.getJSONObject(1)
        assertEquals("direct", secondRule.getString("outboundTag"))
        assertTrue(secondRule.has("ip"))
        val ips = secondRule.getJSONArray("ip")
        var hasGeoipIr = false
        for (i in 0 until ips.length()) {
            if (ips.getString(i) == "geoip:ir") {
                hasGeoipIr = true
                break
            }
        }
        assertTrue("geoip:ir must be in second domestic routing rule", hasGeoipIr)
    }

    @Test
    fun `injectDomesticRouting fails closed on invalid json`() {
        val malformed = "not valid json {{{["
        val result = DomesticSplitTunnel.injectDomesticRouting(malformed)
        assertEquals("Malformed config should return unmodified string", malformed, result)
    }
}
