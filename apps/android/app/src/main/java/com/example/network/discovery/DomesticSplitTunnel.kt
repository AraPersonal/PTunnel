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
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i)
                if (outbound?.optString("tag") == "direct") {
                    hasDirect = true
                    break
                }
            }
            if (!hasDirect) {
                val directOutbound = JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                }
                outbounds.put(directOutbound)
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
                val ipList = JSONArray().apply {
                    put("127.0.0.0/8")
                    put("10.0.0.0/8")
                    put("172.16.0.0/12")
                    put("192.168.0.0/16")
                    put("100.64.0.0/10")
                    put("fc00::/7")
                    put("fe80::/10")
                }
                val hasGeoip = try {
                    java.io.File("/data/user/0/com.ptunnel/app_assets/geoip.dat").exists() ||
                    java.io.File("/data/data/com.ptunnel/app_assets/geoip.dat").exists()
                } catch (_: Throwable) { false }
                if (hasGeoip) {
                    ipList.put("geoip:ir")
                    ipList.put("geoip:private")
                }
                put("ip", ipList)
                put("outboundTag", "direct")
            }

            // Prepend domestic rules so they take priority
            val newRules = JSONArray()
            newRules.put(domesticDomainRule)
            newRules.put(domesticIpRule)
            for (i in 0 until rules.length()) {
                val existing = rules.optJSONObject(i) ?: continue
                // Don't duplicate if already exists
                val outboundTag = existing.optString("outboundTag")
                val isOldDirect = outboundTag == "direct" && existing.optJSONArray("ip")?.let { arr ->
                    (0 until arr.length()).any { arr.optString(it) == "geoip:ir" }
                } == true
                if (!isOldDirect) {
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
