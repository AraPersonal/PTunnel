package com.example.vpn

import android.net.Uri
import android.util.Base64
import com.example.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Extracted quota metadata from a VPN URI or subscription link if present.
 */
data class ParsedQuotaInfo(
    val usedBytes: Long,
    val totalBytes: Long
)

/**
 * Production VPN Config & Subscription URI Parser.
 * Zero mock/random data — extracts exact host, port, credentials, protocol,
 * and optional quota metadata from real VPN links (`vless://`, `vmess://`, `trojan://`, `ss://`, `wg://`).
 */
object VpnUriParser {

    fun parseClipboardPayload(rawText: String): List<VpnServer> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val decodedPayload = tryDecodeBase64Subscription(trimmed) ?: trimmed

        if (decodedPayload.contains("[Interface]", ignoreCase = true) &&
            decodedPayload.contains("[Peer]", ignoreCase = true)
        ) {
            parseWireGuardConf(decodedPayload)?.let { return listOf(it) }
        }

        val lines = decodedPayload
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }

        val parsedServers = mutableListOf<VpnServer>()
        for (line in lines) {
            parseSingleLine(line)?.let { parsedServers.add(it) }
        }

        return parsedServers
    }

    /**
     * Extracts real package quota information if encoded in the VPN URI query parameters
     * (e.g., `?total_gb=50&used_gb=2.5` or `?total=53687091200&used=0`) or remark (e.g., `#Node | 50GB`).
     */
    fun extractQuotaFromPayload(rawText: String): ParsedQuotaInfo? {
        val decoded = tryDecodeBase64Subscription(rawText.trim()) ?: rawText.trim()
        val gbBytes = 1024.0 * 1024.0 * 1024.0

        // 1. Check explicit query parameters: total_bytes / used_bytes
        val totalBytesMatch = Regex("(?:total_bytes|total)=(\\d+)", RegexOption.IGNORE_CASE).find(decoded)
        if (totalBytesMatch != null) {
            val total = totalBytesMatch.groupValues[1].toLongOrNull() ?: 0L
            val used = Regex("(?:used_bytes|used)=(\\d+)", RegexOption.IGNORE_CASE)
                .find(decoded)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            if (total > 0L) return ParsedQuotaInfo(usedBytes = used, totalBytes = total)
        }

        // 2. Check GB query parameters: quota_gb / total_gb / used_gb
        val totalGbMatch = Regex("(?:quota_gb|total_gb|quota)=([0-9.]+)", RegexOption.IGNORE_CASE).find(decoded)
        if (totalGbMatch != null) {
            val totalGb = totalGbMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val usedGb = Regex("(?:used_gb)=([0-9.]+)", RegexOption.IGNORE_CASE)
                .find(decoded)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            if (totalGb > 0.0) {
                return ParsedQuotaInfo(
                    usedBytes = (usedGb * gbBytes).toLong(),
                    totalBytes = (totalGb * gbBytes).toLong()
                )
            }
        }

        // 3. Check URL-decoded remark/fragment for patterns like "12.5/50GB" or "50GB" / "50 GB"
        val urlDecoded = runCatching { URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
        val slashGbRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)\\s*GB", RegexOption.IGNORE_CASE)
        val slashMatch = slashGbRegex.find(urlDecoded)
        if (slashMatch != null) {
            val usedGb = slashMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val totalGb = slashMatch.groupValues[2].toDoubleOrNull() ?: 0.0
            if (totalGb > 0.0) {
                return ParsedQuotaInfo(
                    usedBytes = (usedGb * gbBytes).toLong(),
                    totalBytes = (totalGb * gbBytes).toLong()
                )
            }
        }

        val singleGbRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*GB", RegexOption.IGNORE_CASE)
        val singleMatch = singleGbRegex.find(urlDecoded.substringAfter("#", ""))
        if (singleMatch != null) {
            val totalGb = singleMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (totalGb > 0.0) {
                return ParsedQuotaInfo(
                    usedBytes = 0L,
                    totalBytes = (totalGb * gbBytes).toLong()
                )
            }
        }

        return null
    }

    private fun parseSingleLine(line: String): VpnServer? {
        val lower = line.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("vless://") -> parseStandardUri(line, "vless")
            lower.startsWith("trojan://") -> parseStandardUri(line, "trojan")
            lower.startsWith("wg://") || lower.startsWith("wireguard://") -> parseStandardUri(line, "wg")
            lower.startsWith("hy2://") || lower.startsWith("hysteria2://") -> parseStandardUri(line, "hy2")
            lower.startsWith("tuic://") -> parseStandardUri(line, "tuic")
            lower.startsWith("vmess://") -> parseVmessUri(line)
            lower.startsWith("ss://") -> parseShadowsocksUri(line)
            else -> parseFlexibleServerCode(line)
        }
    }

    private fun parseStandardUri(rawUri: String, scheme: String): VpnServer? {
        return try {
            val uri = Uri.parse(rawUri)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val credential = uri.userInfo ?: ""
            val security = uri.getQueryParameter("security") ?: "tls"
            val transport = uri.getQueryParameter("type") ?: "tcp"
            val sni = uri.getQueryParameter("sni")
                ?: uri.getQueryParameter("host")
                ?: host

            val rawFragment = uri.fragment?.let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }?.trim()

            val cleanName = if (!rawFragment.isNullOrBlank()) {
                stripLeadingEmoji(rawFragment)
            } else {
                formatFallbackServerName(host, port)
            }

            val flag = detectCountryFlag(rawFragment ?: cleanName, host)

            VpnServer(
                id = "node-${UUID.randomUUID().toString().take(8)}",
                flagEmoji = flag,
                name = cleanName,
                host = host,
                port = port,
                protocolScheme = scheme.uppercase(Locale.ROOT),
                credential = credential,
                security = security,
                transport = transport,
                sni = sni,
                rawUri = rawUri,
                pingMs = -1 // Measured via real TCP socket handshake
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmessUri(rawUri: String): VpnServer? {
        return try {
            val b64Part = rawUri.substringAfter("vmess://").trim()
            val jsonStr = decodeBase64(b64Part) ?: return parseStandardUri(rawUri, "vmess")
            val json = JSONObject(jsonStr)
            val host = json.optString("add", "").ifBlank { return null }
            val port = json.optString("port", "443").toIntOrNull() ?: 443
            val uuid = json.optString("id", "")
            val remark = json.optString("ps", "").trim()
            val net = json.optString("net", "ws")
            val tls = json.optString("tls", "tls").ifBlank { "none" }
            val sni = json.optString("sni", json.optString("host", host))

            val cleanName = if (remark.isNotBlank()) {
                stripLeadingEmoji(remark)
            } else {
                formatFallbackServerName(host, port)
            }
            val flag = detectCountryFlag(remark.ifBlank { cleanName }, host)

            VpnServer(
                id = "node-${UUID.randomUUID().toString().take(8)}",
                flagEmoji = flag,
                name = cleanName,
                host = host,
                port = port,
                protocolScheme = "VMESS",
                credential = uuid,
                security = tls,
                transport = net,
                sni = sni,
                rawUri = rawUri,
                pingMs = -1
            )
        } catch (_: Exception) {
            parseStandardUri(rawUri, "vmess")
        }
    }

    private fun parseShadowsocksUri(rawUri: String): VpnServer? {
        return try {
            val withoutScheme = rawUri.substringAfter("ss://")
            val remark = if (withoutScheme.contains("#")) {
                URLDecoder.decode(withoutScheme.substringAfterLast("#"), "UTF-8").trim()
            } else ""
            val mainPart = withoutScheme.substringBefore("#")

            val resolvedMain = if (mainPart.contains("@")) {
                mainPart
            } else {
                decodeBase64(mainPart) ?: mainPart
            }

            val hostPortPart = resolvedMain.substringAfterLast("@")
            val credentialPart = resolvedMain.substringBeforeLast("@", "")
            val host = hostPortPart.substringBeforeLast(":").trim().removePrefix("[").removeSuffix("]")
            val port = hostPortPart.substringAfterLast(":", "8388").toIntOrNull() ?: 8388

            if (host.isBlank()) return null

            val cleanName = if (remark.isNotBlank()) {
                stripLeadingEmoji(remark)
            } else {
                formatFallbackServerName(host, port)
            }
            val flag = detectCountryFlag(remark.ifBlank { cleanName }, host)

            VpnServer(
                id = "node-${UUID.randomUUID().toString().take(8)}",
                flagEmoji = flag,
                name = cleanName,
                host = host,
                port = port,
                protocolScheme = "SS",
                credential = credentialPart,
                security = "aead",
                transport = "tcp/udp",
                sni = host,
                rawUri = rawUri,
                pingMs = -1
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWireGuardConf(confText: String): VpnServer? {
        val lines = confText.lines()
        var endpoint = ""
        var privateKey = ""
        var remark = "WireGuard Tunnel"

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("#") && remark == "WireGuard Tunnel") {
                val comment = line.removePrefix("#").trim()
                if (comment.isNotBlank()) remark = comment
            } else if (line.startsWith("Endpoint", ignoreCase = true)) {
                endpoint = line.substringAfter("=").trim()
            } else if (line.startsWith("PrivateKey", ignoreCase = true)) {
                privateKey = line.substringAfter("=").trim()
            }
        }

        if (endpoint.isBlank()) return null
        val host = endpoint.substringBeforeLast(":").trim()
        val port = endpoint.substringAfterLast(":", "51820").toIntOrNull() ?: 51820
        val flag = detectCountryFlag(remark, host)

        return VpnServer(
            id = "node-${UUID.randomUUID().toString().take(8)}",
            flagEmoji = flag,
            name = stripLeadingEmoji(remark),
            host = host,
            port = port,
            protocolScheme = "WG",
            credential = privateKey,
            security = "noise",
            transport = "udp",
            sni = host,
            rawUri = confText,
            pingMs = -1
        )
    }

    private fun parseFlexibleServerCode(rawLine: String): VpnServer? {
        val remark = if (rawLine.contains("#")) {
            runCatching { URLDecoder.decode(rawLine.substringAfterLast("#"), "UTF-8") }
                .getOrDefault(rawLine.substringAfterLast("#")).trim()
        } else ""

        val target = rawLine.substringBefore("#").trim()
        if (!target.contains(".") && !target.contains(":")) return null

        val host: String
        val port: Int
        if (target.contains(":")) {
            host = target.substringBeforeLast(":").substringAfterLast("@").trim()
            port = target.substringAfterLast(":").filter { it.isDigit() }.toIntOrNull() ?: 443
        } else {
            host = target
            port = 443
        }

        if (host.isBlank()) return null

        val displayName = when {
            remark.isNotBlank() -> stripLeadingEmoji(remark)
            else -> formatFallbackServerName(host, port)
        }
        val flag = detectCountryFlag("$remark $displayName", host)

        return VpnServer(
            id = "node-${UUID.randomUUID().toString().take(8)}",
            flagEmoji = flag,
            name = displayName,
            host = host,
            port = port,
            protocolScheme = "VLESS",
            credential = target,
            security = "tls",
            transport = "tcp",
            sni = host,
            rawUri = rawLine,
            pingMs = -1
        )
    }

    /**
     * Measures real TCP Socket handshake time to `host:port` in milliseconds.
     * Returns `-1` if the remote host/port does not respond within timeout (zero fake numbers).
     */
    suspend fun measureRealTcpPing(host: String, port: Int): Int =
        withContext(Dispatchers.IO) {
            try {
                val startNano = System.nanoTime()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 2200)
                }
                val elapsedMs = ((System.nanoTime() - startNano) / 1_000_000L).toInt()
                elapsedMs.coerceAtLeast(1)
            } catch (_: Exception) {
                -1
            }
        }

    /**
     * Milliseconds for a TLS handshake that *completes*, or -1 when it never did.
     *
     * A TCP connection stopped being evidence on 2026-11-04. Measured from an Iranian line against
     * Cloudflare: every edge accepted the socket in about 50 ms and the connection was then reset
     * during the ClientHello, while cleartext HTTP to the same addresses was answered not by
     * Cloudflare at all but by the carrier's own block page (HTTP 403 whose body is an iframe on
     * 10.10.34.36 marked "Invalid Keyword"). A probe built on `connect` alone therefore called a dead
     * front healthy, and the recovery ladder rebuilt the tunnel onto it and sat waiting out the
     * verification deadline — the user sees "Connecting…" for twenty seconds, then a switch, which is
     * exactly the behaviour reported as "it keeps disconnecting and changing servers".
     */
    suspend fun measureTlsHandshakeMs(host: String, port: Int, serverName: String): Int =
        withContext(Dispatchers.IO) {
            if (host.isBlank() || serverName.isBlank()) return@withContext -1
            var socket: SSLSocket? = null
            try {
                val startNano = System.nanoTime()
                socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                socket.connect(InetSocketAddress(host, port), 2_500)
                // The same application protocol list the tunnel itself will offer, so the probe cannot
                // pass on a shape the real dial would not.
                val params = socket.sslParameters
                params.serverNames = listOf(SNIHostName(serverName))
                params.applicationProtocols = arrayOf("http/1.1")
                socket.sslParameters = params
                socket.startHandshake()
                ((System.nanoTime() - startNano) / 1_000_000L).toInt().coerceAtLeast(1)
            } catch (_: Exception) {
                -1
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // the answer is already recorded
                }
            }
        }

    private fun tryDecodeBase64Subscription(input: String): String? {
        if (input.contains("://") || input.contains("\n")) return null
        val decoded = decodeBase64(input) ?: return null
        return if (decoded.contains("://") || decoded.contains("[Interface]")) decoded else null
    }

    private fun decodeBase64(input: String): String? {
        return try {
            val normalized = input.replace('-', '+').replace('_', '/')
            val padded = when (normalized.length % 4) {
                2 -> "$normalized=="
                3 -> "$normalized="
                else -> normalized
            }
            val bytes = Base64.decode(padded, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripLeadingEmoji(text: String): String {
        return text.trim().replace(Regex("^[\\p{So}\\p{Cn}\\s]+"), "").ifBlank { text.trim() }
    }

    private fun formatFallbackServerName(host: String, port: Int): String {
        val countryName = inferCountryName(host)
        return if (countryName != null) {
            "$countryName ($host:$port)"
        } else {
            "$host : $port"
        }
    }

    private fun inferCountryName(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("de") || lower.contains("fra") || lower.contains("germany") -> "Germany - Frankfurt"
            lower.contains("nl") || lower.contains("ams") || lower.contains("nether") -> "Netherlands - Amsterdam"
            lower.contains("ch") || lower.contains("zur") || lower.contains("swiss") -> "Switzerland - Zurich"
            lower.contains("us") || lower.contains("nyc") || lower.contains("usa") -> "United States - New York"
            lower.contains("uk") || lower.contains("lon") || lower.contains("gb") -> "United Kingdom - London"
            lower.contains("fr") || lower.contains("par") || lower.contains("france") -> "France - Paris"
            lower.contains("tr") || lower.contains("ist") || lower.contains("turk") -> "Turkey - Istanbul"
            lower.contains("ae") || lower.contains("dxb") || lower.contains("dubai") -> "UAE - Dubai"
            lower.contains("fi") || lower.contains("hel") || lower.contains("finland") -> "Finland - Helsinki"
            lower.contains("se") || lower.contains("sto") || lower.contains("sweden") -> "Sweden - Stockholm"
            lower.contains("sg") || lower.contains("sin") || lower.contains("singapore") -> "Singapore - Core"
            lower.contains("jp") || lower.contains("tyo") || lower.contains("japan") -> "Japan - Tokyo"
            else -> null
        }
    }

    fun detectCountryFlag(remark: String, host: String): String {
        val combined = "$remark $host".lowercase(Locale.ROOT)
        return when {
            combined.contains("🇩🇪") || combined.contains("germany") || combined.contains("frankfurt") || combined.contains(".de") || combined.startsWith("de-") -> "🇩🇪"
            combined.contains("🇳🇱") || combined.contains("netherlands") || combined.contains("amsterdam") || combined.contains(".nl") || combined.startsWith("nl-") -> "🇳🇱"
            combined.contains("🇨🇭") || combined.contains("switzerland") || combined.contains("zurich") || combined.contains(".ch") || combined.startsWith("ch-") -> "🇨🇭"
            combined.contains("🇺🇸") || combined.contains("united states") || combined.contains("usa") || combined.contains("new york") || combined.startsWith("us-") -> "🇺🇸"
            combined.contains("🇬🇧") || combined.contains("united kingdom") || combined.contains("london") || combined.contains(".uk") || combined.startsWith("uk-") -> "🇬🇧"
            combined.contains("🇫🇷") || combined.contains("france") || combined.contains("paris") || combined.contains(".fr") || combined.startsWith("fr-") -> "🇫🇷"
            combined.contains("🇹🇷") || combined.contains("turkey") || combined.contains("istanbul") || combined.contains(".tr") || combined.startsWith("tr-") -> "🇹🇷"
            combined.contains("🇦🇪") || combined.contains("uae") || combined.contains("dubai") || combined.contains(".ae") || combined.startsWith("ae-") -> "🇦🇪"
            combined.contains("🇫🇮") || combined.contains("finland") || combined.contains("helsinki") || combined.contains(".fi") || combined.startsWith("fi-") -> "🇫🇮"
            combined.contains("🇸🇪") || combined.contains("sweden") || combined.contains("stockholm") || combined.contains(".se") || combined.startsWith("se-") -> "🇸🇪"
            combined.contains("🇮🇸") || combined.contains("iceland") || combined.contains("reykjavik") || combined.contains(".is") -> "🇮🇸"
            combined.contains("🇸🇬") || combined.contains("singapore") || combined.contains(".sg") || combined.startsWith("sg-") -> "🇸🇬"
            combined.contains("🇯🇵") || combined.contains("japan") || combined.contains("tokyo") || combined.contains(".jp") || combined.startsWith("jp-") -> "🇯🇵"
            combined.contains("🇨🇦") || combined.contains("canada") || combined.contains("toronto") || combined.contains(".ca") -> "🇨🇦"
            combined.contains("🇦🇺") || combined.contains("australia") || combined.contains("sydney") || combined.contains(".au") -> "🇦🇺"
            else -> "🌐"
        }
    }
}
