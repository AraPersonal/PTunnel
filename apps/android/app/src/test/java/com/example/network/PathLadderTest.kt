package com.example.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order in which a dead tunnel is retried.
 *
 * The same set of steps is always offered; only the order changes, and that is the whole point:
 * when our own address stopped answering, every direct path is dead at once — they all dial it —
 * so the Cloudflare-fronted routes have to be tried first.
 */
class PathLadderTest {

    @Test
    fun `a blocked address puts the cdn routes before the direct ones`() {
        val plan = PathLadder.plan(serverUnreachable = true)

        assertTrue(plan.indexOf(RecoveryStep.CACHED_CDN) < plan.indexOf(RecoveryStep.FRESH_MULTI))
        assertTrue(plan.indexOf(RecoveryStep.FRESH_CDN) < plan.indexOf(RecoveryStep.REALITY_ONLY))
    }

    @Test
    fun `an unclear failure keeps the direct rebuilds first and the cdn last`() {
        val plan = PathLadder.plan(serverUnreachable = false)

        assertTrue(plan.indexOf(RecoveryStep.FRESH_MULTI) < plan.indexOf(RecoveryStep.CACHED_CDN))
        assertTrue(plan.indexOf(RecoveryStep.REALITY_ONLY) < plan.indexOf(RecoveryStep.FRESH_CDN))
    }

    @Test
    fun `the live profile is always retried first, so a blip costs nothing`() {
        assertEquals(
            RecoveryStep.SAME_CONFIG,
            PathLadder.plan(serverUnreachable = true).first()
        )
        assertEquals(
            RecoveryStep.SAME_CONFIG,
            PathLadder.plan(serverUnreachable = false).first()
        )
    }

    @Test
    fun `a path only wins over the live one when it is clearly faster`() {
        val cooldown = PathLadder.SWITCH_COOLDOWN_MS

        // 23% better on the measured scale: Germany at 300 ms against a 230 ms answer.
        assertTrue(PathLadder.fasterPathWins(300, 230, cooldown))
        // 10% better is not worth interrupting a download for.
        assertFalse(PathLadder.fasterPathWins(300, 270, cooldown))
        // Exactly the margin is a tie, and a tie does not move a session.
        assertFalse(PathLadder.fasterPathWins(300, 240, cooldown))
    }

    @Test
    fun `the cooldown stops two switches from chaining into a flap`() {
        assertFalse(PathLadder.fasterPathWins(300, 100, PathLadder.SWITCH_COOLDOWN_MS - 1))
        assertTrue(PathLadder.fasterPathWins(300, 100, PathLadder.SWITCH_COOLDOWN_MS))
    }

    @Test
    fun `a path that was not measured cannot be preferred over one that was`() {
        val cooldown = PathLadder.SWITCH_COOLDOWN_MS
        assertFalse(PathLadder.fasterPathWins(300, -1, cooldown))   // the candidate never answered
        assertFalse(PathLadder.fasterPathWins(-1, 120, cooldown))    // we do not know our own cost
        assertFalse(PathLadder.fasterPathWins(0, 120, cooldown))
    }

    @Test
    fun `degradation needs a run of slow probes, not one unlucky sample`() {
        assertFalse(PathLadder.isDegraded(listOf(600), 200))
        assertFalse(PathLadder.isDegraded(listOf(600, 210, 640), 200))
        assertTrue(PathLadder.isDegraded(listOf(600, 640, 610), 200))
        // Only the last rounds count: a session that started slow and got better is not degrading.
        assertTrue(PathLadder.isDegraded(listOf(200, 205, 610, 640, 605), 200))
    }

    @Test
    fun `a missed probe is not a slow probe`() {
        // -1 means the request never came back. That is the ladder's problem, not a reason to move
        // country, and treating it as "slow" would make every outage a migration.
        assertFalse(PathLadder.isDegraded(listOf(-1, -1, -1), 200))
        assertFalse(PathLadder.isDegraded(listOf(600, 0, 600), 200))
    }

    @Test
    fun `a baseline of zero judges nothing`() {
        assertFalse(PathLadder.isDegraded(listOf(900, 900, 900), 0))
        assertEquals(0, PathLadder.baselineMs(emptyList()))
        assertEquals(0, PathLadder.baselineMs(listOf(-1, -1)))
    }

    @Test
    fun `the baseline is the middle of the early probes, not the luckiest one`() {
        assertEquals(200, PathLadder.baselineMs(listOf(600, 120, 200, 210, 190)))
        assertEquals(200, PathLadder.baselineMs(listOf(200, -1, 250, 180)))
    }

    @Test
    fun `both plans spend the same, bounded number of attempts`() {
        assertEquals(PathLadder.MAX_ATTEMPTS, PathLadder.plan(false).size)
        assertEquals(PathLadder.MAX_ATTEMPTS, PathLadder.plan(true).size)
    }

    @Test
    fun `every plan ends able to reach a different address than our own`() {
        for (unreachable in listOf(true, false)) {
            val plan = PathLadder.plan(unreachable)
            assertTrue(
                "plan for serverUnreachable=$unreachable never tries a cdn route",
                plan.any { it == RecoveryStep.CACHED_CDN || it == RecoveryStep.FRESH_CDN }
            )
            assertTrue(
                "plan for serverUnreachable=$unreachable never tries the udp transport",
                plan.contains(RecoveryStep.REALITY_ONLY)
            )
        }
    }

    @Test
    fun `another box is tried second, in both orders`() {
        // The failure this exists for: automatic mode chose a box that never built a tunnel, and the
        // ladder only ever rebuilt the path to that same box — so the session came back every 15 s to
        // the address that was the problem, and never to the other country that works.
        for (unreachable in listOf(true, false)) {
            val plan = PathLadder.plan(unreachable)
            assertEquals(
                "serverUnreachable=$unreachable must try a different box before rebuilding the same one",
                RecoveryStep.OTHER_BOX,
                plan[1]
            )
            assertTrue(
                "serverUnreachable=$unreachable escapes to another box before paying for a detour",
                plan.indexOf(RecoveryStep.OTHER_BOX) < plan.indexOf(RecoveryStep.CACHED_CDN)
            )
        }
    }

    @Test
    fun `the uncorroborated plan still tries another box before it stops`() {
        val plan = PathLadder.plan(serverUnreachable = false, fleetCorroborates = false)
        assertTrue(plan.contains(RecoveryStep.OTHER_BOX))
        assertFalse(plan.contains(RecoveryStep.CACHED_CDN))
        assertFalse(plan.contains(RecoveryStep.FRESH_CDN))
    }

    @Test
    fun `the first retry is immediate and the delays then grow`() {
        assertEquals(0L, PathLadder.backoffMs(0))
        assertEquals(1_000L, PathLadder.backoffMs(1))
        assertEquals(2_000L, PathLadder.backoffMs(2))
        assertEquals(4_000L, PathLadder.backoffMs(3))
        assertEquals(8_000L, PathLadder.backoffMs(4))
        assertEquals(8_000L, PathLadder.backoffMs(99))
        assertEquals(0L, PathLadder.backoffMs(-1))
    }

    @Test
    fun `a parallel round puts the answering candidates first, cheapest first`() {
        val ranked = PathLadder.rank(
            listOf(
                PathProbe("86.54.42.124", 443, -1),
                PathProbe("172.64.80.1", 2083, 220),
                PathProbe("172.64.80.2", 2083, 90)
            )
        )

        assertEquals(listOf("172.64.80.2", "172.64.80.1", "86.54.42.124"), ranked.map { it.host })
    }

    @Test
    fun `candidates that never answered keep the order they were given`() {
        val ranked = PathLadder.rank(
            listOf(
                PathProbe("86.54.42.124", 443, -1),
                PathProbe("86.54.42.124", 2083, -1),
                PathProbe("86.54.42.124", 2053, -1)
            )
        )

        assertEquals(listOf(443, 2083, 2053), ranked.map { it.port })
    }

    @Test
    fun `a silent round is told apart from a round where something answered`() {
        val silent = listOf(
            PathProbe("86.54.42.124", 443, -1),
            PathProbe("86.54.42.124", 2083, -1)
        )
        assertFalse(PathLadder.anyAnswered(silent))

        // One open port is enough: a block on 443 alone must not look like the whole address is gone.
        assertTrue(PathLadder.anyAnswered(silent + PathProbe("86.54.42.124", 2053, 310)))
    }

    @Test
    fun `one reachable neutral host is enough to keep blaming the tunnel, not the user`() {
        // Measured on a live Iranian line: 1.1.1.1:443 was filtered while 1.0.0.1 and 8.8.8.8
        // answered, and the single-target version waited ~210 s before starting the ladder.
        assertTrue(PathLadder.deviceNetworkUp(listOf(-1, 41, 38)))
        assertFalse(PathLadder.deviceNetworkUp(listOf(-1, -1, -1)))
        assertTrue(PathLadder.NEUTRAL_HOSTS.size >= 2)
    }

    @Test
    fun `an uncorroborated tunnel failure never offers the cdn rungs`() {
        val plan = PathLadder.plan(serverUnreachable = false, fleetCorroborates = false)

        assertFalse(plan.contains(RecoveryStep.CACHED_CDN))
        assertFalse(plan.contains(RecoveryStep.FRESH_CDN))
        // Another box stays in the uncorroborated plan, and that is deliberate: the gate is about
        // moving the user onto a *slower detour* on one device's word. A second server we own is not
        // a detour — it is the same cost by a different road, and the only rung that survives the
        // failure where the address itself is the problem.
        assertEquals(
            listOf(
                RecoveryStep.SAME_CONFIG,
                RecoveryStep.OTHER_BOX,
                RecoveryStep.FRESH_MULTI,
                RecoveryStep.REALITY_ONLY
            ),
            plan
        )
    }

    @Test
    fun `a corroborated tunnel failure still reaches the cdn`() {
        val plan = PathLadder.plan(serverUnreachable = false, fleetCorroborates = true)

        assertTrue(plan.contains(RecoveryStep.CACHED_CDN))
        assertTrue(plan.indexOf(RecoveryStep.REALITY_ONLY) < plan.indexOf(RecoveryStep.CACHED_CDN))
    }

    @Test
    fun `a blocked address ignores the fleet because the device measured the block itself`() {
        assertEquals(
            PathLadder.plan(serverUnreachable = true, fleetCorroborates = false),
            PathLadder.plan(serverUnreachable = true, fleetCorroborates = true)
        )
    }

    @Test
    fun `the endpoint that answered last goes first`() {
        // Measured on a live device: the profile call walked ptu.beauty (read timeout) and sslip.io
        // (read timeout) before nip.io answered in 103 ms — 12 s burned per request on that line.
        val rest = listOf("https://ptu.beauty/v1/", "https://86-54-42-124.sslip.io/v1/")
        assertEquals(
            listOf("https://86-54-42-124.nip.io/v1/", "https://86-54-42-124.sslip.io/v1/", "https://ptu.beauty/v1/"),
            PathLadder.orderedEndpoints(
                preferred = "https://86-54-42-124.nip.io/v1/",
                first = "https://86-54-42-124.sslip.io/v1/",
                rest = rest
            )
        )
    }

    @Test
    fun `without a remembered endpoint the configured order is kept`() {
        val ordered = PathLadder.orderedEndpoints(
            preferred = null,
            first = "https://86-54-42-124.nip.io/v1/",
            rest = listOf("https://ptu.beauty/v1/", "https://206-1-101-67.nip.io/v1/")
        )
        assertEquals(
            listOf(
                "https://86-54-42-124.nip.io/v1/",
                "https://ptu.beauty/v1/",
                "https://206-1-101-67.nip.io/v1/"
            ),
            ordered
        )
    }

    @Test
    fun `a preferred endpoint never duplicates itself and never drops the others`() {
        val all = listOf("https://a/v1/", "https://b/v1/", "https://c/v1/")
        assertEquals(
            listOf("https://b/v1/", "https://a/v1/", "https://c/v1/"),
            PathLadder.orderedEndpoints(preferred = "https://b/v1/", first = "https://a/v1/", rest = all)
        )
        assertEquals(
            listOf("https://blank", "https://a/v1/", "https://b/v1/"),
            PathLadder.orderedEndpoints(preferred = "https://blank", first = "https://a/v1/", rest = listOf("https://b/v1/"))
        )
        assertEquals(
            listOf("https://a/v1/", "https://b/v1/"),
            PathLadder.orderedEndpoints(preferred = "   ", first = "https://a/v1/", rest = listOf("https://b/v1/"))
        )
    }

    @Test
    fun `a link that is moving the user's bytes is not a dead tunnel`() {
        val window = 12_000L
        // The download that used to kill the session: 39 MB finished, then two starved probes later
        // the app tore the tunnel down as `Tunnel lost; starting the recovery ladder`.
        assertTrue(PathLadder.linkBusy(39L * 1024L * 1024L, window))
        // A throttled transport (5-7 KB/s measured against 1-3 MB/s on the working one) must NOT be
        // excused — noticing that is precisely what the ladder is for.
        assertFalse(PathLadder.linkBusy(7L * 1024L * 12L, window))
        // Idle is not busy either.
        assertFalse(PathLadder.linkBusy(0L, window))
        // A window shorter than a second says nothing about a rate.
        assertFalse(PathLadder.linkBusy(10L * 1024L * 1024L, 400L))
    }

    @Test
    fun `a longer probe deadline still notices a dead tunnel quickly`() {
        // The deadline moved 3 s → 6 s because a healthy HSPA tunnel was failing it at 2997 ms. That is
        // only allowed while death detection stays inside these bounds.
        val fast = PathLadder.detectionWorstCaseMs(intervalMs = 3_000L, deadlineMs = 6_000L, limit = 3)
        val steady = PathLadder.detectionWorstCaseMs(intervalMs = 12_000L, deadlineMs = 6_000L, limit = 3)
        assertTrue("fast window detection took $fast ms", fast <= 30_000L)
        assertTrue("steady detection took $steady ms", steady <= 60_000L)
    }

    @Test
    fun `a live session is rebuilt only when the server really served something else`() {
        // The transport rotation lives in the profile, so a server-side fix reaches a device only when it
        // refetches — and a connected session never does. Measured from the server afterwards: Iranian
        // /24s were still dialling trojan and vmess (2 KB/s on this path) thousands of times after the
        // rotation was fixed, purely holding the profile they had. A failed fetch must not be read as
        // "the profile changed", or an outage would tear down working tunnels.
        assertTrue(PathLadder.profileDiffers("""{"a":1}""", """{"a":2}"""))
        assertFalse(PathLadder.profileDiffers("""{"a":1}""", """{"a":1}"""))
        assertFalse(PathLadder.profileDiffers("""{"a":1}""", ""))
        assertFalse(PathLadder.profileDiffers("", """{"a":2}"""))
    }

    @Test
    fun `a detour is only dialled onto an edge that answered`() {
        // Measured again on 2026-11-04 from an Iranian line: three Cloudflare edges refused the
        // connection in under 0.11 s each, while the CDN rung kept rebuilding a full tunnel onto an
        // unprobed address and waiting out the verification deadline. Refusing to dial what cannot be
        // reached is the difference between a four-second no and a twenty-four-second spinner.
        val probes = listOf(
            PathProbe("188.114.96.3", 2083, -1),
            PathProbe("104.26.0.1", 2083, -1),
            PathProbe("172.64.80.1", 2083, 90)
        )
        assertEquals(listOf("172.64.80.1"), PathLadder.liveEdges(probes, emptySet(), 3))
    }

    @Test
    fun `an edge that already failed this episode is not dialled again`() {
        val probes = listOf(
            PathProbe("188.114.96.3", 2083, 80),
            PathProbe("104.26.0.1", 2083, 120)
        )
        assertEquals(
            listOf("104.26.0.1"),
            PathLadder.liveEdges(probes, setOf("188.114.96.3"), 3)
        )
        assertTrue(PathLadder.liveEdges(probes, setOf("188.114.96.3", "104.26.0.1"), 3).isEmpty())
    }

    @Test
    fun `one rung cannot spend the whole recovery on a filtered family of addresses`() {
        val ten = (1..10).map { PathProbe("104.18.$it.1", 2083, it) }
        assertEquals(3, PathLadder.liveEdges(ten, emptySet(), 3).size)
        assertTrue(PathLadder.liveEdges(emptyList(), emptySet(), 3).isEmpty())
    }
}
