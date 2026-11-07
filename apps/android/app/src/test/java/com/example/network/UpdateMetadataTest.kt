package com.example.network

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture was produced by `tools/sign-release.mjs` with the real release key, so this test
 * proves Java verifies what Node produced — not merely that one JVM talking to itself.
 *
 * The negative cases are derived from the fixture by mutation rather than written out by hand:
 * a hand-typed hex literal that is one character short made the first version of this test fail on
 * its own fixture, which is exactly the class of mistake this structure cannot make.
 */
class UpdateMetadataTest {

    private val publicKey = Base64.getDecoder().decode(UpdateMetadata.PUBLIC_KEY_B64)
    private val payloadText =
        "https://86-54-42-124.nip.io/dl|deadbeef0f0fcafe00000000000000000000000000000000000000000000000000000|0"
    private val signature = Base64.getDecoder().decode(
        "MEQCIBMii0jpZ64TkxsfLu2nOtAPLWs+aK2lpPRhK/8rGngcAiBT1JHP0/TjGHaAoAZWL0UH/IOihbt9qHFgkzwVDfhQ2Q=="
    )

    private val parts = payloadText.split("|")

    private fun verifyText(text: String) =
        UpdateMetadata.verify(text.toByteArray(Charsets.UTF_8), signature, publicKey)

    @Test
    fun `the fixture splits into url, sha and version and rebuilds exactly`() {
        assertTrue(parts.size == 3)
        val rebuilt = UpdateMetadata.payload(parts[0], parts[1], parts[2].toLong()).decodeToString()
        assertTrue(rebuilt == payloadText)
    }

    @Test
    fun `a signature produced by the release key verifies`() {
        assertTrue(verifyText(payloadText))
    }

    @Test
    fun `a different apk hash is rejected`() {
        val otherSha = parts[1].dropLast(1) + if (parts[1].last() == '0') '1' else '0'
        assertFalse(verifyText("${parts[0]}|$otherSha|${parts[2]}"))
    }

    @Test
    fun `a different download url is rejected`() {
        assertFalse(verifyText("https://evil.example/dl|${parts[1]}|${parts[2]}"))
    }

    @Test
    fun `a different version code is rejected`() {
        assertFalse(verifyText("${parts[0]}|${parts[1]}|${parts[2].toLong() + 1}"))
    }

    @Test
    fun `a missing or garbage signature is rejected instead of throwing`() {
        assertFalse(UpdateMetadata.verify(payloadText.toByteArray(Charsets.UTF_8), ByteArray(0), publicKey))
        assertFalse(UpdateMetadata.verify(payloadText.toByteArray(Charsets.UTF_8), ByteArray(72) { 7 }, publicKey))
    }
}
