package com.example.network.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkProbeEngineTest {

    private lateinit var context: Context
    private lateinit var engine: NetworkProbeEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val apiClient = DiscoveryApiClient()
        engine = NetworkProbeEngine(context, apiClient)
    }

    @Test
    fun `throughput gate threshold is strictly 500 KBps`() {
        assertEquals(500.0, NetworkProbeEngine.THROUGHPUT_GATE_KBPS, 0.001)
    }

    @Test
    fun `scoreCandidate properly weights throughput death rate and latency`() {
        // Zero throughput => 0 score
        val zeroScore = engine.scoreCandidate(0.0, 100, 0.0)
        assertEquals(0.0, zeroScore, 0.001)

        // 1000 KB/s with 0 death rate
        val perfectScore = engine.scoreCandidate(1000.0, 100, 0.0)
        assertTrue(perfectScore > 1000.0)

        // 50% death rate cuts throughput score in half
        val halfDeathScore = engine.scoreCandidate(1000.0, 100, 0.5)
        assertTrue(halfDeathScore < perfectScore)
        assertTrue(halfDeathScore >= 500.0)

        // Lower latency yields higher bonus
        val lowLatency = engine.scoreCandidate(1000.0, 50, 0.0)
        val highLatency = engine.scoreCandidate(1000.0, 400, 0.0)
        assertTrue("Lower latency candidate must score higher", lowLatency > highLatency)
    }

    @Test
    fun `buildMatrixCandidates generates fragment and port hop candidates`() {
        val candidates = engine.buildMatrixCandidates()
        assertNotNull(candidates)
        assertTrue("Matrix candidates should not be empty", candidates.isNotEmpty())

        val hasFragment = candidates.any { it.engine == "xray" && it.params.containsKey("fragment") }
        assertTrue("Matrix should include fragment candidates", hasFragment)

        val hasHy2 = candidates.any { it.engine == "hysteria" && it.transport == "hy2" }
        assertTrue("Matrix should include hysteria 2 candidates", hasHy2)

        val hasPort443 = candidates.any { it.params["port"] == 443 }
        assertTrue("Matrix should test standard port 443", hasPort443)
    }

    @Test
    fun `chunked probe result verifies gate pass or fail correctly`() {
        val passingProbe = ChunkedProbeResult(
            totalBytes = 2 * 1024 * 1024L,
            durationSeconds = 2.0,
            throughputKBps = 1024.0,
            latencyMs = 120,
            verdict = "ok"
        )
        assertTrue("1024 KB/s passes gate", passingProbe.throughputKBps >= NetworkProbeEngine.THROUGHPUT_GATE_KBPS)

        val failingProbe = ChunkedProbeResult(
            totalBytes = 512 * 1024L,
            durationSeconds = 2.0,
            throughputKBps = 256.0,
            latencyMs = 300,
            verdict = "slow"
        )
        assertTrue("256 KB/s fails gate", failingProbe.throughputKBps < NetworkProbeEngine.THROUGHPUT_GATE_KBPS)
    }
}
