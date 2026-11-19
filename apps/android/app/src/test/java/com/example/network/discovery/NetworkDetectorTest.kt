package com.example.network.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowTelephonyManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkDetectorTest {

    private lateinit var context: Context
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var shadowTelephonyManager: ShadowTelephonyManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var shadowConnectivityManager: ShadowConnectivityManager
    private lateinit var networkDetector: NetworkDetector

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowTelephonyManager = Shadows.shadowOf(telephonyManager)
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowConnectivityManager = Shadows.shadowOf(connectivityManager)
        networkDetector = NetworkDetector(context)
    }

    private fun setCellularActive() {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowConnectivityManager.setNetworkCapabilities(connectivityManager.activeNetwork, caps)
    }

    private fun setWifiActive() {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowConnectivityManager.setNetworkCapabilities(connectivityManager.activeNetwork, caps)
    }

    @Test
    fun `detects MCI carrier from MCC-MNC 43211`() {
        setCellularActive()
        shadowTelephonyManager.setSimOperator("43211")
        shadowTelephonyManager.setSimOperatorName("MCI")

        val identity = networkDetector.detect(serverProvince = "teh")
        assertEquals("m.432.11", identity.operatorKey)
        assertEquals("mci", identity.operatorTag)
        assertEquals("همراه اول", identity.displayName)
        assertEquals("cellular", identity.networkType)
        assertEquals("c.432.11.teh", identity.networkKey)
        assertEquals("high", identity.confidence)
        assertFalse(identity.isNewOperator)
    }

    @Test
    fun `detects Irancell carrier from MCC-MNC 43235`() {
        setCellularActive()
        shadowTelephonyManager.setSimOperator("43235")
        shadowTelephonyManager.setSimOperatorName("MTN Irancell")

        val identity = networkDetector.detect(serverProvince = "isf")
        assertEquals("m.432.35", identity.operatorKey)
        assertEquals("irancell", identity.operatorTag)
        assertEquals("ایرانسل", identity.displayName)
        assertEquals("c.432.35.isf", identity.networkKey)
        assertEquals("high", identity.confidence)
        assertFalse(identity.isNewOperator)
    }

    @Test
    fun `detects Samantel carrier from MCC-MNC 43210`() {
        setCellularActive()
        shadowTelephonyManager.setSimOperator("43210")
        shadowTelephonyManager.setSimOperatorName("Samantel")

        val identity = networkDetector.detect(serverProvince = "thr")
        assertEquals("m.432.10", identity.operatorKey)
        assertEquals("samantel", identity.operatorTag)
        assertEquals("سامانتل", identity.displayName)
        assertEquals("high", identity.confidence)
        assertFalse(identity.isNewOperator)
    }

    @Test
    fun `detects Shatel Mobile carrier from MCC-MNC 43208`() {
        setCellularActive()
        shadowTelephonyManager.setSimOperator("43208")
        shadowTelephonyManager.setSimOperatorName("Shatel Mobile")

        val identity = networkDetector.detect()
        assertEquals("m.432.8", identity.operatorKey)
        assertEquals("shatel_mobile", identity.operatorTag)
        assertEquals("شاتل موبایل", identity.displayName)
        assertFalse(identity.isNewOperator)
    }

    @Test
    fun `detects unknown cellular carrier with open tag and low confidence`() {
        setCellularActive()
        shadowTelephonyManager.setSimOperator("43299")
        shadowTelephonyManager.setSimOperatorName("BrandNew Tel")

        val identity = networkDetector.detect(serverProvince = "shz")
        assertEquals("m.432.99", identity.operatorKey)
        assertEquals("auto:m.432.99", identity.operatorTag)
        assertEquals("BrandNew Tel", identity.displayName)
        assertEquals("c.432.99.shz", identity.networkKey)
        assertEquals("low", identity.confidence)
        assertTrue(identity.isNewOperator)
    }

    @Test
    fun `detects Shatel fixed broadband from ASN 12880`() {
        setWifiActive()
        val identity = networkDetector.detect(
            serverAsn = 12880,
            serverIspOrg = "Shatel",
            serverPrefix24 = "185.143.232",
            serverProvince = "teh"
        )
        assertEquals("a.12880", identity.operatorKey)
        assertEquals("shatel_fixed", identity.operatorTag)
        assertEquals("شاتل ثابت", identity.displayName)
        assertEquals("fixed", identity.networkType)
        assertEquals("f.12880.185.143.232", identity.networkKey)
        assertEquals("high", identity.confidence)
    }

    @Test
    fun `detects Mokhaberat fixed broadband from ASN 58224`() {
        setWifiActive()
        val identity = networkDetector.detect(
            serverAsn = 58224,
            serverIspOrg = "Telecommunication Company of Iran",
            serverPrefix24 = "2.180.10",
            serverProvince = "teh"
        )
        assertEquals("a.58224", identity.operatorKey)
        assertEquals("mokhaberat_fixed", identity.operatorTag)
        assertEquals("مخابرات ثابت", identity.displayName)
        assertEquals("f.58224.2.180.10", identity.networkKey)
    }
}
