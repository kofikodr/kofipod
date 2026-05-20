// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract for the OPML import read helpers. SAF picker returns provider-controlled
 * content; a hostile or misconfigured provider can serve a multi-GB stream that
 * would OOM the app if read unconditionally. We pin: small bodies round-trip,
 * cap-trips return [OpmlReadResult.TooLarge] without leaking the partial bytes,
 * mid-stream errors funnel through [OpmlReadResult.Unreadable], and the production
 * 4 MB default is the single load-bearing constant.
 *
 * The `readCappedFromUri` path also calls `ContentResolver.query` for an early
 * `OpenableColumns.SIZE` reject — that branch needs an Android test runner so it's
 * not exercised here. The streaming gate below is the second-line defence and is
 * itself sufficient to close the kode-review finding.
 */
class OpmlImportReadTest {
    @Test
    fun defaultCap_isFourMegabytes() {
        assertEquals(4L * 1024L * 1024L, DEFAULT_MAX_OPML_BYTES)
    }

    @Test
    fun readCappedStream_smallBody_returnsOk() {
        val body = "<opml><head/><body/></opml>".encodeToByteArray()
        val result = readCappedStream(ByteArrayInputStream(body))
        val ok = result as? OpmlReadResult.Ok ?: fail("expected Ok, got $result")
        assertTrue(body.contentEquals(ok.bytes))
    }

    @Test
    fun readCappedStream_emptyBody_returnsOkWithEmptyBytes() {
        val result = readCappedStream(ByteArrayInputStream(ByteArray(0)))
        val ok = result as? OpmlReadResult.Ok ?: fail("expected Ok, got $result")
        assertEquals(0, ok.bytes.size)
    }

    @Test
    fun readCappedStream_atCap_returnsOk() {
        // Exact-cap body must succeed — the gate is `> max`, not `>=`. Spot-check
        // the first/last bytes so a regression that returns a correctly-sized
        // but wrong-content payload would be caught.
        val cap = 100L
        val body = ByteArray(cap.toInt()) { 'a'.code.toByte() }
        val result = readCappedStream(ByteArrayInputStream(body), maxBytes = cap)
        val ok = result as? OpmlReadResult.Ok ?: fail("expected Ok, got $result")
        assertEquals(cap.toInt(), ok.bytes.size)
        assertEquals('a'.code.toByte(), ok.bytes.first())
        assertEquals('a'.code.toByte(), ok.bytes.last())
    }

    @Test
    fun readCappedStream_exceedsCap_returnsTooLarge() {
        val cap = 100L
        val body = ByteArray((cap + 1L).toInt()) { 'a'.code.toByte() }
        val result = readCappedStream(ByteArrayInputStream(body), maxBytes = cap)
        val tooLarge = result as? OpmlReadResult.TooLarge ?: fail("expected TooLarge, got $result")
        assertEquals(cap, tooLarge.cap)
    }

    @Test
    fun readCappedStream_largeBody_returnsTooLarge() {
        // Many buffer iterations past the cap — exercises the loop's repeated
        // counter increment, not just a single-shot exceed.
        val cap = 100L
        val body = ByteArray(10_000) { 'b'.code.toByte() }
        val result = readCappedStream(ByteArrayInputStream(body), maxBytes = cap)
        assertTrue(result is OpmlReadResult.TooLarge, "expected TooLarge, got $result")
    }

    @Test
    fun readCappedStream_streamThrowsMidRead_returnsUnreadable() {
        // Override the three-arg `read(buf, off, len)` directly — that's the form
        // the implementation calls. Overriding only the single-byte form would
        // silently stop throwing if a future refactor switched away from the
        // base `InputStream`'s default delegation.
        val flaky =
            object : InputStream() {
                private var bytesYielded = 0

                override fun read(): Int = error("unused")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (bytesYielded >= 16) throw IOException("simulated network drop")
                    val give = minOf(len, 8)
                    repeat(give) { i -> b[off + i] = 'x'.code.toByte() }
                    bytesYielded += give
                    return give
                }
            }
        val result = readCappedStream(flaky, maxBytes = 1024)
        assertTrue(result is OpmlReadResult.Unreadable, "expected Unreadable, got $result")
    }

    @Test
    fun readCappedStream_capEqualsBufferSize_returnsOk() {
        // BUFFER_SIZE = 8 KB internally. A body of exactly the buffer width must
        // pass — guards against an off-by-one regression that flipped `>` to `>=`.
        val cap = 8192L
        val body = ByteArray(cap.toInt()) { 'c'.code.toByte() }
        val result = readCappedStream(ByteArrayInputStream(body), maxBytes = cap)
        val ok = result as? OpmlReadResult.Ok ?: fail("expected Ok, got $result")
        assertEquals(cap.toInt(), ok.bytes.size)
    }

    @Test
    fun readCappedStream_capOneBelowBufferSize_returnsTooLarge() {
        // Pair with the test above: the same body that just-fits at cap=8192 must
        // overflow at cap=8191. Locks the boundary in both directions.
        val body = ByteArray(8192) { 'c'.code.toByte() }
        val result = readCappedStream(ByteArrayInputStream(body), maxBytes = 8191L)
        assertTrue(result is OpmlReadResult.TooLarge, "expected TooLarge, got $result")
    }

    @Test
    fun readCappedStream_maxBytesZero_rejectsAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            readCappedStream(ByteArrayInputStream(ByteArray(0)), maxBytes = 0L)
        }
    }

    @Test
    fun readCappedStream_maxBytesAboveIntMax_rejectsAtConstruction() {
        // The accumulator allocates a single ByteArray via ByteArrayOutputStream.
        // Caller-supplied caps above Int.MAX_VALUE would overflow silently — pin
        // the constraint up-front so future maintainers can't trip it.
        assertFailsWith<IllegalArgumentException> {
            readCappedStream(ByteArrayInputStream(ByteArray(0)), maxBytes = Int.MAX_VALUE.toLong() + 1L)
        }
    }
}
