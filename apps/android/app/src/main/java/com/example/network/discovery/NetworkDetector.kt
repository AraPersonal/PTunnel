package com.example.network.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.security.MessageDigest

/**
 * Discovers network identity, cellular SIM operator, and privacy-preserving network_key.
 * Complies with docs/design/0001:
 * - Fixed: f.<asn>.<prefix24>
 * - Cellular: c.<mcc>.<mnc>.<province>
 * - Open operator registry: auto-detects brand-new operators without hardcoding.
 */
data class NetworkIdentity(
    val operatorKey: String,
    val operatorTag: String,
    val displayName: String,
    val networkType: String, // "cellular" | "fixed" | "unknown"
    val networkKey: String,
    val simDetails: String,
    val region: String,
    val confidence: String, // "high" | "medium" | "low"
    val isNewOperator: Boolean,
    val evidence: OperatorEvidenceDto,
    val localFingerprint: String
)

class NetworkDetector(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    companion object {
        private const val TAG = "NetworkDetector"

        // Known seed operators mapped by MCC-MNC
        private val KNOWN_OPERATORS = mapOf(
            "43211" to Pair("mci", "همراه اول"),
            "43235" to Pair("irancell", "ایرانسل"),
            "43220" to Pair("rightel", "رایتل"),
            "43208" to Pair("shatel_mobile", "شاتل موبایل"),
            "43210" to Pair("samantel", "سامانتل")
        )
    }

    /**
     * Detects the current network and returns its NetworkIdentity.
     */
    fun detect(
        serverAsn: Int? = null,
        serverIspOrg: String? = null,
        serverPrefix24: String? = null,
        serverProvince: String? = null
    ): NetworkIdentity {
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (isCellular) {
            return detectCellular(serverProvince)
        } else if (isWifi) {
            return detectWifi(serverAsn, serverIspOrg, serverPrefix24, serverProvince)
        }

        // Fallback when network type is unclear or disconnected
        val defaultEvidence = OperatorEvidenceDto()
        return NetworkIdentity(
            operatorKey = "unknown",
            operatorTag = "unknown",
            displayName = "نامشخص",
            networkType = "unknown",
            networkKey = "unk.unk.unk",
            simDetails = "بدون شبکه",
            region = "unk",
            confidence = "low",
            isNewOperator = false,
            evidence = defaultEvidence,
            localFingerprint = "offline"
        )
    }

    private fun detectCellular(serverProvince: String?): NetworkIdentity {
        var mcc: Int? = null
        var mnc: Int? = null
        var rawOperatorCode = telephonyManager?.simOperator.orEmpty()
        if (rawOperatorCode.length < 5) {
            rawOperatorCode = telephonyManager?.networkOperator.orEmpty()
        }

        if (rawOperatorCode.length >= 5) {
            mcc = rawOperatorCode.substring(0, 3).toIntOrNull()
            mnc = rawOperatorCode.substring(3).toIntOrNull()
        }

        val simName = telephonyManager?.simOperatorName?.trim().orEmpty()
        val networkName = telephonyManager?.networkOperatorName?.trim().orEmpty()
        val suggestedLabel = simName.ifBlank { networkName }

        val operatorKey = if (mcc != null && mnc != null) "m.$mcc.$mnc" else "m.432.11"
        val known = KNOWN_OPERATORS[rawOperatorCode]

        val operatorTag: String
        val displayName: String
        val isNew: Boolean
        val confidence: String

        if (known != null) {
            operatorTag = known.first
            displayName = known.second
            isNew = false
            confidence = "high"
        } else if (mcc != null && mnc != null) {
            operatorTag = "auto:$operatorKey"
            displayName = suggestedLabel.ifBlank { "اپراتور ناشناس ($rawOperatorCode)" }
            isNew = true
            confidence = "low"
        } else {
            operatorTag = "mci"
            displayName = "همراه اول"
            isNew = false
            confidence = "medium"
        }

        val province = (serverProvince ?: "teh").lowercase().trim()
        val networkKey = "c.${mcc ?: 432}.${mnc ?: 11}.$province"
        val simDetails = if (mcc != null && mnc != null) "MCC $mcc / MNC $mnc" else "Cellular SIM"

        val evidence = OperatorEvidenceDto(
            mcc = mcc ?: 432,
            mnc = mnc ?: 11,
            simOperatorName = suggestedLabel.ifBlank { null }
        )

        return NetworkIdentity(
            operatorKey = operatorKey,
            operatorTag = operatorTag,
            displayName = displayName,
            networkType = "cellular",
            networkKey = networkKey,
            simDetails = simDetails,
            region = province,
            confidence = confidence,
            isNewOperator = isNew,
            evidence = evidence,
            localFingerprint = "cell:$rawOperatorCode"
        )
    }

    private fun detectWifi(
        serverAsn: Int?,
        serverIspOrg: String?,
        serverPrefix24: String?,
        serverProvince: String?
    ): NetworkIdentity {
        val asn = serverAsn ?: 12880 // default fallback
        val ispOrg = serverIspOrg ?: "Shatel"
        val prefix = serverPrefix24 ?: "192.168.1"
        val province = serverProvince ?: "teh"

        val operatorKey = "a.$asn"
        val operatorTag = mapAsnToTag(asn, ispOrg)
        val displayName = mapAsnToDisplayName(asn, ispOrg)
        val networkKey = "f.$asn.$prefix"
        val simDetails = "ASN $asn · $displayName"

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = wifiManager?.connectionInfo?.ssid.orEmpty().replace("\"", "")
        val localHash = hashSalted(ssid.ifBlank { prefix })

        val evidence = OperatorEvidenceDto(
            asn = asn,
            ispOrg = ispOrg
        )

        return NetworkIdentity(
            operatorKey = operatorKey,
            operatorTag = operatorTag,
            displayName = displayName,
            networkType = "fixed",
            networkKey = networkKey,
            simDetails = simDetails,
            region = province,
            confidence = if (serverAsn != null) "high" else "medium",
            isNewOperator = false,
            evidence = evidence,
            localFingerprint = "wifi:$localHash"
        )
    }

    private fun mapAsnToTag(asn: Int, ispOrg: String): String {
        return when (asn) {
            12880 -> "shatel_fixed"
            58224 -> "mokhaberat_fixed"
            16322 -> "pishgaman_fixed"
            43754 -> "asiatech_fixed"
            206065 -> "zitel"
            else -> "auto:a.$asn"
        }
    }

    private fun mapAsnToDisplayName(asn: Int, ispOrg: String): String {
        return when (asn) {
            12880 -> "شاتل ثابت"
            58224 -> "مخابرات ثابت"
            16322 -> "پیشگامان ثابت"
            43754 -> "آسیاتک ثابت"
            206065 -> "زیتل"
            else -> ispOrg.ifBlank { "اینترنت ثابت ($asn)" }
        }
    }

    private fun hashSalted(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(("ptunnel-salt-$input").toByteArray())
            bytes.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            input.hashCode().toString()
        }
    }
}
