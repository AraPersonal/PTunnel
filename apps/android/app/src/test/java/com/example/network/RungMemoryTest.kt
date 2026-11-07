package com.example.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The remembered rung may only change the *order* of the ladder.
 *
 * Every rung has to stay reachable, because a line that starts refusing the path that worked yesterday
 * must still be walked down the rest of the plan — otherwise remembering a rung turns a temporary block
 * into a permanent failure. The ordering itself is the fix: one Iranian /24 pulled the CDN profile 202
 * times in a day and established nothing, because every reconnect began again at rung zero.
 */
class RungMemoryTest {

    private val fullPlan = listOf(
        RecoveryStep.SAME_CONFIG,
        RecoveryStep.OTHER_BOX,
        RecoveryStep.FRESH_MULTI,
        RecoveryStep.REALITY_ONLY,
        RecoveryStep.CACHED_CDN,
        RecoveryStep.FRESH_CDN
    )

    @Test
    fun `the rung that worked becomes the first choice after the live profile`() {
        val planned = PathLadder.planWithRememberedRung(fullPlan, RecoveryStep.FRESH_CDN)
        assertEquals(RecoveryStep.SAME_CONFIG, planned[0])
        assertEquals(
            "a proven rung is tried before re-diagnosing the whole ladder",
            RecoveryStep.FRESH_CDN,
            planned[1]
        )
        assertEquals("no rung is lost", fullPlan.size, planned.size)
        assertEquals("no rung is duplicated", fullPlan.toSet(), planned.toSet())
    }

    @Test
    fun `nothing is remembered means the plan is exactly as diagnosed`() {
        assertSame(fullPlan, PathLadder.planWithRememberedRung(fullPlan, null))
    }

    @Test
    fun `the live profile carrying the session is not a fact worth re-trying first`() {
        assertSame(
            "SAME_CONFIG is already rung zero, so promoting it must not shuffle anything",
            fullPlan,
            PathLadder.planWithRememberedRung(fullPlan, RecoveryStep.SAME_CONFIG)
        )
    }

    @Test
    fun `a rung that is not in this plan cannot be promoted`() {
        val short = listOf(RecoveryStep.SAME_CONFIG, RecoveryStep.FRESH_MULTI, RecoveryStep.REALITY_ONLY)
        assertSame(
            "a stale memory from a longer plan must not invent a rung",
            short,
            PathLadder.planWithRememberedRung(short, RecoveryStep.FRESH_CDN)
        )
    }

    @Test
    fun `a rung that is already first-choice is not moved`() {
        assertSame(
            "no churn when the memory already matches the order",
            fullPlan,
            PathLadder.planWithRememberedRung(fullPlan, RecoveryStep.OTHER_BOX)
        )
    }
}
