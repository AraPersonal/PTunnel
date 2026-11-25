package com.example.network.discovery

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages split-tunneling for Iranian domestic destinations (.ir domains,
 * domestic suffixes discovered from /v1/operators, and domestic GeoIP).
 * All domestic destinations pass directly through the domestic physical network interface.
 */
object DomesticSplitTunnel {
    private const val TAG = "DomesticSplitTunnel"

    private val domesticSuffixes: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        domesticSuffixes.add(".ir")
        listOf(
            ".aparat.com", ".digikala.com", ".snapp.ir", ".snapp.cab", ".snapp.doctor",
            ".snapp.market", ".tapsi.ir", ".tapsi.cab", ".divar.ir", ".divar.cloud",
            ".rubika.ir", ".eitaa.com", ".bale.ai", ".telewebion.com", ".shaparak.ir"
        ).forEach { domesticSuffixes.add(it) }
    }

    /**
     * Updates domestic suffixes received from GET /v1/operators.
     */
    fun updateDomesticRules(suffixes: List<String>?) {
        if (!suffixes.isNullOrEmpty()) {
            suffixes.forEach { suffix ->
                val clean = suffix.trim().lowercase()
                if (clean.startsWith(".") && clean.length >= 3) {
                    domesticSuffixes.add(clean)
                }
            }
            Log.i(TAG, "Domestic suffixes updated: $domesticSuffixes")
        }
    }

    fun getDomesticSuffixes(): Set<String> = domesticSuffixes.toSet()

    /**
     * Checks if a target host or IP is domestic (Iranian local).
     * Fail-closed: returns false if uncertain (traffic stays in tunnel).
     */
    fun isDomesticHost(hostOrIp: String?): Boolean {
        if (hostOrIp.isNullOrBlank()) return false
        val clean = hostOrIp.trim().lowercase()

        // 1. Suffix match (e.g. .ir or custom Iranian suffixes)
        for (suffix in domesticSuffixes) {
            if (clean.endsWith(suffix) || clean == suffix.removePrefix(".")) {
                return true
            }
        }

        // 2. Private LAN IPs
        if (isPrivateIp(clean)) {
            return true
        }

        return false
    }

    private fun isPrivateIp(ip: String): Boolean {
        return try {
            val addr = InetAddress.getByName(ip)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Injects domestic split-tunneling routing rules into an Xray JSON config.
     * Ensures Iranian domestic traffic routes to the "direct" freedom outbound.
     */
    fun injectDomesticRouting(configJson: String): String {
        return try {
            val root = JSONObject(configJson)

            // Ensure outbound "direct" exists
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
            var hasDirect = false
            var hasDnsOut = false
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i)
                val tag = outbound?.optString("tag")
                if (tag == "direct") {
                    hasDirect = true
                }
                if (tag == "dns-out") {
                    hasDnsOut = true
                }
            }
            if (!hasDirect) {
                val directOutbound = JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                }
                outbounds.put(directOutbound)
            }
            if (!hasDnsOut) {
                val dnsOutbound = JSONObject().apply {
                    put("tag", "dns-out")
                    put("protocol", "dns")
                }
                outbounds.put(dnsOutbound)
            }

            // Ensure DNS config exists with robust DoH resolvers
            val dnsObj = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
            dnsObj.put("queryStrategy", "UseIPv4")
            if (!dnsObj.has("servers") || dnsObj.optJSONArray("servers")?.length() == 0) {
                dnsObj.put("servers", JSONArray().apply {
                    put("1.1.1.1")
                    put("8.8.8.8")
                    put("https://1.1.1.1/dns-query")
                })
            }

            // Routing configuration
            val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
            if (!routing.has("domainStrategy")) {
                routing.put("domainStrategy", "IPIfNonMatch")
            }

            val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }

            // Build domestic domain list
            val domainList = JSONArray().apply {
                put("domain:ir")
                put("regexp:.*\\.ir$")
                domesticSuffixes.forEach { suffix ->
                    if (suffix != ".ir") {
                        val cleaned = suffix.removePrefix(".")
                        put("domain:$cleaned")
                        put("regexp:.*\\.$cleaned$")
                    }
                }
            }

            val domesticDomainRule = JSONObject().apply {
                put("type", "field")
                put("domain", domainList)
                put("outboundTag", "direct")
            }

            val domesticIpRule = JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().apply {
                    put("geoip:ir")
                    put("geoip:private")
                    put("127.0.0.0/8")
                    put("10.0.0.0/8")
                    put("172.16.0.0/12")
                    put("192.168.0.0/16")
                    put("100.64.0.0/10")
                    put("fc00::/7")
                    put("fe80::/10")
                })
                put("outboundTag", "direct")
            }

            val dnsRule = JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().apply { put("tun") })
                put("port", "53")
                put("network", "udp,tcp")
                put("outboundTag", "dns-out")
            }

            // Prepend domestic rules and DNS hijack rule so they take priority
            val newRules = JSONArray()
            newRules.put(domesticDomainRule)
            newRules.put(domesticIpRule)
            newRules.put(dnsRule)
            for (i in 0 until rules.length()) {
                val existing = rules.optJSONObject(i) ?: continue
                val outboundTag = existing.optString("outboundTag")
                val isOldDirect = outboundTag == "direct" && existing.optJSONArray("ip")?.let { arr ->
                    (0 until arr.length()).any { arr.optString(it) == "geoip:ir" }
                } == true
                val isOldDns = existing.optString("port") == "53" || outboundTag == "dns-out"
                if (!isOldDirect && !isOldDns) {
                    newRules.put(existing)
                }
            }
            routing.put("rules", newRules)

            root.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to inject domestic routing into config: ${t.message}")
            configJson
        }
    }
}
