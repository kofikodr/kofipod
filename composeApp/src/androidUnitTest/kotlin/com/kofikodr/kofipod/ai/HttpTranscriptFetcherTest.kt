// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract for [HttpTranscriptFetcher]. The fetcher reads attacker-influenced URLs
 * (RSS-feed metadata), so we pin: success cases keep working, oversized declared
 * bodies are rejected before streaming, oversized actual bodies are rejected
 * mid-stream, and every error funnels through [AiError.TranscriptUnavailable] so
 * callers see one consistent failure mode.
 *
 * Tests inject a small `maxBytes` so cap-trip scenarios don't need 16 MB payloads.
 * The production default (`DEFAULT_MAX_TRANSCRIPT_BYTES = 16 MB`) is pinned by the
 * single `defaultCap_isSixteenMegabytes` test below — that's the load-bearing
 * constant the security gate enforces in production.
 */
class HttpTranscriptFetcherTest {
    @Test
    fun defaultCap_isSixteenMegabytes() {
        // Pin the production cap so a refactor of the constructor default fails the
        // test instead of silently shipping a different limit.
        assertEquals(16L * 1024L * 1024L, HttpTranscriptFetcher.DEFAULT_MAX_TRANSCRIPT_BYTES)
    }

    @Test
    fun fetch_smallBody_returnsContent() =
        runTest {
            val fetcher = fetcherWithBody("hello world".encodeToByteArray())
            val result = fetcher.fetch("https://example/x.vtt").getOrThrow()
            assertEquals("hello world", result)
        }

    @Test
    fun fetch_emptyBody_returnsEmptyString() =
        runTest {
            val fetcher = fetcherWithBody(ByteArray(0))
            val result = fetcher.fetch("https://example/x.vtt").getOrThrow()
            assertEquals("", result)
        }

    @Test
    fun fetch_nonSuccessStatus_failsWithTranscriptUnavailable() =
        runTest {
            val fetcher =
                HttpTranscriptFetcher(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }))
            assertTranscriptUnavailable(fetcher.fetch("https://example/x.vtt"))
        }

    @Test
    fun fetch_contentLengthAboveCap_isRejected() =
        runTest {
            // The server *declares* a body larger than the cap. The early-reject must
            // fire. Note: with `MockEngine` we can pin the *outcome* but not "before
            // reading the body" — Ktor's `readAvailable` is a public extension over
            // an `@InternalAPI` member, so a delegating spy channel can't intercept
            // reads. The streaming gate (`fetch_streamExceedsCap_isRejectedMidStream`)
            // covers the second-line defence if the early gate ever regressed.
            val cap = 100L
            val payload = "tiny actual body".encodeToByteArray()
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = ByteReadChannel(payload),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, (cap + 1L).toString()),
                        )
                    },
                )
            val fetcher = HttpTranscriptFetcher(client, maxBytes = cap)
            assertTranscriptUnavailable(fetcher.fetch("https://example/x.vtt"))
        }

    @Test
    fun fetch_streamExceedsCap_isRejectedMidStream() =
        runTest {
            // Server omits Content-Length and sends more than the cap. The streaming
            // counter must trip and surface TranscriptUnavailable. Using a small
            // injected cap keeps the test payload tiny.
            val cap = 100L
            val oversized = ByteArray(cap.toInt() + 1) { 'a'.code.toByte() }
            val fetcher =
                HttpTranscriptFetcher(
                    HttpClient(
                        MockEngine {
                            respond(
                                content = ByteReadChannel(oversized),
                                status = HttpStatusCode.OK,
                            )
                        },
                    ),
                    maxBytes = cap,
                )
            assertTranscriptUnavailable(fetcher.fetch("https://example/x.vtt"))
        }

    @Test
    fun fetch_atCap_succeeds() =
        runTest {
            // Exact-cap body must succeed — the comparison is `> max`, not `>=`.
            // Spot-check content (first/last) so a regression that returns a string
            // of the right length but wrong content is caught.
            val cap = 100L
            val capBody = ByteArray(cap.toInt()) { 'a'.code.toByte() }
            val fetcher = HttpTranscriptFetcher(simpleClient(capBody), maxBytes = cap)
            val result = fetcher.fetch("https://example/x.vtt").getOrThrow()
            assertEquals(cap.toInt(), result.length)
            assertEquals('a', result.first())
            assertEquals('a', result.last())
        }

    @Test
    fun fetch_multibyteUtf8_decodesAcrossReadBoundaries() =
        runTest {
            // A multi-byte UTF-8 sequence ("é" = 0xC3 0xA9) that lands across an
            // 8 KiB read boundary must decode correctly because we accumulate bytes
            // and decode once at the end, not per-chunk. The 8 KiB matches the
            // implementation's READ_BUFFER_SIZE; MockEngine fragments long bodies
            // via ByteReadChannel, so the boundary actually exercises.
            val prefix = ByteArray(8191) { 'a'.code.toByte() }
            val splitChar = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
            val suffix = ByteArray(50) { 'b'.code.toByte() }
            val combined = prefix + splitChar + suffix
            val fetcher = fetcherWithBody(combined)
            val result = fetcher.fetch("https://example/x.vtt").getOrThrow()
            assertTrue("é" in result, "decoded text must contain the split multibyte char")
            assertEquals(8191 + 1 + 50, result.length) // 1 char per "é"
        }

    @Test
    fun fetch_networkException_failsWithTranscriptUnavailable_IllegalState() =
        runTest {
            val fetcher = HttpTranscriptFetcher(HttpClient(MockEngine { error("socket reset") }))
            assertTranscriptUnavailable(fetcher.fetch("https://example/x.vtt"))
        }

    @Test
    fun fetch_networkException_failsWithTranscriptUnavailable_runtime() =
        runTest {
            // Cover a second exception type to confirm the recoverCatching path is
            // type-agnostic, not happenstance-matching on IllegalStateException.
            val fetcher =
                HttpTranscriptFetcher(
                    HttpClient(
                        MockEngine {
                            throw RuntimeException("connect timeout")
                        },
                    ),
                )
            assertTranscriptUnavailable(fetcher.fetch("https://example/x.vtt"))
        }

    private fun simpleClient(body: ByteArray): HttpClient =
        HttpClient(
            MockEngine {
                respond(content = ByteReadChannel(body), status = HttpStatusCode.OK)
            },
        )

    private fun fetcherWithBody(body: ByteArray): HttpTranscriptFetcher = HttpTranscriptFetcher(simpleClient(body))

    private fun assertTranscriptUnavailable(result: Result<String>) {
        val ex =
            result.exceptionOrNull() as? AiErrorException
                ?: fail("expected AiErrorException, got ${result.exceptionOrNull()}")
        assertEquals(AiError.TranscriptUnavailable, ex.error)
    }
}
