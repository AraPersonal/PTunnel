package com.example.network

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Integrity check for the update metadata.
 *
 * `latest.json` lives on a server we control, so whoever reaches that server could otherwise point
 * every installed app at a different file. The signed payload is `url|sha256|versionCode`: what file
 * is offered, what it must contain, and which version it is — the three things that decide what ends
 * up installed. The private half of the key never leaves the release machine (it is not on either
 * box and not in git), so taking the server does not let anyone mint metadata this app accepts.
 *
 * ECDSA P-256 with SHA-256 rather than Ed25519: `SHA256withECDSA` and `X509EncodedKeySpec` exist on
 * every Android version this app supports (minSdk 24), while Ed25519 keys only arrive at API 33.
 */
object UpdateMetadata {

    /** SPKI (SubjectPublicKeyInfo) of the release key, base64. */
    const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENDTX9rZ2KUoKaeZx+BI3KL+BcqZ8N1wMuW2Fa76hn" +
            "+ubDLjcBZCeSwYRh6DUJe9dtXOhSXqu9pIZYP5Xtf5d5w=="

    /** Exactly what the release key signs. */
    fun payload(url: String, sha256: String, versionCode: Long): ByteArray =
        "$url|$sha256|$versionCode".toByteArray(Charsets.UTF_8)

    fun verify(payload: ByteArray, signatureDer: ByteArray, publicKeyDer: ByteArray): Boolean {
        if (signatureDer.isEmpty() || publicKeyDer.isEmpty()) return false
        return try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(payload)
            verifier.verify(signatureDer)
        } catch (_: Throwable) {
            false
        }
    }
}
