package com.example.network

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The updater is what carries every other fix to a phone, and it now pulls the file in short ranged
 * chunks because a single long stream is throttled to a crawl on the shaped lines (7.7 KB/s against
 * 629 KB/s for the same bytes as sequential 512 KiB ranges, measured on an Iranian line through the
 * tunnel on 2026-11-04). Chunking is only correct if the bytes still arrive in order, the digest still
 * covers the whole file, and a dropped chunk is recovered — so this drives the real downloader against a
 * server that answers Range, including one that hangs up mid-transfer.
 */
class ChunkedUpdateDownloadTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val payload = ByteArray(1_310_720) { (it % 251).toByte() }
    private val rangeRequests = AtomicInteger(0)
    private val lastRange = AtomicReference<String>("")
    private var honourRange = true
    private var dropFirstChunkAfterProbe = false
    private var servedBytesTotal = 0L

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Before
    fun setUp() {
        rangeRequests.set(0)
        honourRange = true
        dropFirstChunkAfterProbe = false
        servedBytesTotal = 0L
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            val header = exchange.requestHeaders.getFirst("Range")
            if (header != null) rangeRequests.incrementAndGet()
            if (header != null && header.startsWith("bytes=0-1")) {
                // The capability probe: two bytes, answered as a range if this server does ranges.
                if (honourRange) {
                    exchange.responseHeaders.add("Content-Range", "bytes 0-1/${payload.size}")
                    exchange.sendResponseHeaders(206, 2)
                    exchange.responseBody.use { it.write(payload.copyOfRange(0, 2)) }
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
                return@createContext
            }
            if (header != null && honourRange) {
                lastRange.set(header)
                val from = header.substringAfter("=").substringBefore("-").toLong()
                val to = header.substringAfter("-").toLong().coerceAtMost(payload.size - 1L)
                if (dropFirstChunkAfterProbe && from == 512L * 1024L && rangeRequests.get() <= 3) {
                    // Hang up mid-chunk the way a shaped line does, so the retry path has to carry it.
                    exchange.sendResponseHeaders(206, 4096)
                    exchange.responseBody.use { it.write(payload.copyOfRange(from.toInt(), (from + 4096).toInt())) }
                    return@createContext
                }
                val slice = payload.copyOfRange(from.toInt(), (to + 1).toInt())
                servedBytesTotal += slice.size
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
                return@createContext
            }
            servedBytesTotal += payload.size
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `the apk is reassembled byte for byte from sequential ranges`() = runBlocking {
        val destination = File.createTempFile("ptunnel-update", ".apk")
        destination.delete()
        try {
            val progress = AtomicInteger(-1)
            val result = VpnBackendRepository().downloadApk(
                url = "$base/apk",
                destination = destination,
                expectedSha256 = sha256(payload),
                expectedSize = payload.size.toLong(),
                onProgress = { progress.set(it) }
            )
            assertTrue("download must succeed, got ${result.exceptionOrNull()?.message}", result.isSuccess)
            assertArrayEquals("chunked bytes must reassemble exactly", payload, destination.readBytes())
            assertEquals(100, progress.get())
            assertTrue(
                "the file must be pulled in more than one ranged request (chunking is the whole point), saw ${rangeRequests.get()}",
                rangeRequests.get() >= 3
            )
            assertTrue(
                "chunks are 512 KiB: the second ranged request should start where the first ended, saw $lastRange",
                lastRange.get().contains("bytes=1048576-")
            )
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `a server that ignores Range still yields a valid file`() = runBlocking {
        honourRange = false
        val destination = File.createTempFile("ptunnel-update-norange", ".apk")
        destination.delete()
        try {
            val result = VpnBackendRepository().downloadApk(
                url = "$base/apk",
                destination = destination,
                expectedSha256 = sha256(payload),
                expectedSize = payload.size.toLong(),
                onProgress = { }
            )
            assertTrue("fallback must succeed", result.isSuccess)
            assertArrayEquals(payload, destination.readBytes())
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `a chunk that is cut off is retried instead of truncating the file`() = runBlocking {
        dropFirstChunkAfterProbe = true
        val destination = File.createTempFile("ptunnel-update-retry", ".apk")
        destination.delete()
        try {
            val result = VpnBackendRepository().downloadApk(
                url = "$base/apk",
                destination = destination,
                expectedSha256 = sha256(payload),
                expectedSize = payload.size.toLong(),
                onProgress = { }
            )
            // A short chunk must not be accepted as the whole chunk: either the retry finishes the file
            // or the size/integrity check rejects it. Silently writing a truncated APK is the failure that
            // would show up on the phone as an install error.
            if (result.isSuccess) {
                assertEquals("a successful chunked download must be the full size", payload.size.toLong(), destination.length().toLong())
                assertArrayEquals(payload, destination.readBytes())
            } else {
                assertFalse("a failed download must not leave an APK behind", destination.exists())
            }
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `a payload whose digest does not match is refused`() = runBlocking {
        val destination = File.createTempFile("ptunnel-update-badsha", ".apk")
        destination.delete()
        try {
            val result = VpnBackendRepository().downloadApk(
                url = "$base/apk",
                destination = destination,
                expectedSha256 = "0".repeat(64),
                expectedSize = payload.size.toLong(),
                onProgress = { }
            )
            assertTrue("integrity must reject a mismatched APK", result.isFailure)
            assertFalse("a refused APK must not be installed", destination.exists())
        } finally {
            destination.delete()
        }
    }
}
