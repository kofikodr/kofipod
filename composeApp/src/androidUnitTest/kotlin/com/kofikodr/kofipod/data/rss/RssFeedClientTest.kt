// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.rss

import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.testing.inMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [RssFeedClient]'s contract:
 *  - First fetch (no cache row) → 200 with ETag → returns [RssFetchResult.Fresh], cache
 *    row is written with the publisher's ETag and Last-Modified.
 *  - Subsequent fetch → request carries `If-None-Match`/`If-Modified-Since` from the
 *    cache row → 304 returns [RssFetchResult.NotModified] and `lastFetchedAt` is
 *    refreshed (but ETag/lastModified preserved).
 *  - 401/403 → [RssFetchResult.Unauthorized], cache untouched.
 *  - Non-2xx, transport error → [RssFetchResult.NetworkError], cache untouched.
 *  - Blank feed URL → [RssFetchResult.NetworkError], no HTTP at all.
 *  - Host returns only `Last-Modified` (no ETag) → If-Modified-Since still sent on the
 *    next request.
 *
 * MockEngine captures every request so the assertions read the actual outbound header
 * shape, not just the response interpretation.
 */
class RssFeedClientTest {
    @Test
    fun firstFetch_noCacheRow_writesCacheAndReturnsFresh() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val db = inMemoryDatabase()
            val client =
                clientReturning(
                    captured = captured,
                    status = HttpStatusCode.OK,
                    body = SAMPLE_RSS,
                    responseHeaders =
                        headersOf(
                            "Content-Type" to listOf("application/rss+xml"),
                            "ETag" to listOf("\"abc-123\""),
                            "Last-Modified" to listOf("Wed, 22 May 2026 09:00:00 GMT"),
                        ),
                    db = db,
                )

            // Capture a lower-bound clock so the lastFetchedAt assertion is precise
            // rather than merely > 0 (which would pass for a stubbed `1L` write).
            val beforeMs = Clock.System.now().toEpochMilliseconds()
            val result = client.fetch(FEED_URL)
            assertIs<RssFetchResult.Fresh>(result)
            assertEquals("Sample", result.channel.title)

            // Cache row written verbatim — no normalisation of ETag (the publisher's
            // quotes survive) and the Last-Modified is stored as the raw HTTP date
            // string, not parsed-and-reformatted.
            val row = db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull()
            assertNotNull(row)
            assertEquals("\"abc-123\"", row.etag)
            assertEquals("Wed, 22 May 2026 09:00:00 GMT", row.lastModified)
            assertTrue(row.lastFetchedAt >= beforeMs, "lastFetchedAt must be at or after the fetch start")

            // First request must NOT carry conditional headers — there's nothing to compare against yet.
            val req = captured.single()
            assertNull(req.headers["If-None-Match"])
            assertNull(req.headers["If-Modified-Since"])
        }

    @Test
    fun secondFetch_sendsConditionalHeadersFromCache() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val db = inMemoryDatabase()
            // Seed the cache as if we'd fetched once.
            db.rssFeedCacheQueries.upsertCache(
                feedUrl = FEED_URL,
                etag = "\"abc-123\"",
                lastModified = "Wed, 22 May 2026 09:00:00 GMT",
                lastFetchedAt = 1_700_000_000_000L,
            )
            val client =
                clientReturning(
                    captured = captured,
                    status = HttpStatusCode.NotModified,
                    body = "",
                    responseHeaders = headersOf(),
                    db = db,
                )

            val result = client.fetch(FEED_URL)
            assertIs<RssFetchResult.NotModified>(result)

            val req = captured.single()
            assertEquals("\"abc-123\"", req.headers["If-None-Match"])
            assertEquals("Wed, 22 May 2026 09:00:00 GMT", req.headers["If-Modified-Since"])
        }

    @Test
    fun notModified_refreshesLastFetchedAt_preservesEtagAndLastModified() =
        runTest {
            val seededAt = 1_700_000_000_000L
            val fixedNow = 1_799_999_999_999L
            val db = inMemoryDatabase()
            db.rssFeedCacheQueries.upsertCache(
                feedUrl = FEED_URL,
                etag = "\"keep-me\"",
                lastModified = "Tue, 20 May 2026 00:00:00 GMT",
                lastFetchedAt = seededAt,
            )
            val client =
                clientReturning(
                    captured = mutableListOf(),
                    status = HttpStatusCode.NotModified,
                    body = "",
                    responseHeaders = headersOf(),
                    db = db,
                    clock = FixedClock(Instant.fromEpochMilliseconds(fixedNow)),
                )

            val result = client.fetch(FEED_URL)
            // Pin the return-type contract here too, so the canonical 304 test isn't
            // implicitly relying on a sibling test to catch a Fresh-on-304 regression.
            assertIs<RssFetchResult.NotModified>(result)
            val row = db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOne()
            assertEquals("\"keep-me\"", row.etag, "ETag must NOT be cleared on 304")
            assertEquals("Tue, 20 May 2026 00:00:00 GMT", row.lastModified, "Last-Modified must NOT be cleared on 304")
            assertEquals(fixedNow, row.lastFetchedAt, "lastFetchedAt must advance to current time")
        }

    @Test
    fun unauthorized_403_returnsUnauthorized_doesNotWriteCache() =
        runTest {
            val db = inMemoryDatabase()
            val client =
                clientReturning(
                    captured = mutableListOf(),
                    status = HttpStatusCode.Forbidden,
                    body = "Forbidden",
                    responseHeaders = headersOf(),
                    db = db,
                )

            val result = client.fetch(FEED_URL)
            val unauthorized = assertIs<RssFetchResult.Unauthorized>(result)
            assertEquals(403, unauthorized.statusCode)
            // 403 is a real signal from the server, but writing a cache entry would later
            // cause us to send a stale If-None-Match. Leave the cache untouched.
            assertNull(db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull())
        }

    @Test
    fun unauthorized_401_returnsUnauthorized_doesNotWriteCache() =
        runTest {
            val db = inMemoryDatabase()
            val client =
                clientReturning(
                    captured = mutableListOf(),
                    status = HttpStatusCode.Unauthorized,
                    body = "",
                    responseHeaders = headersOf(),
                    db = db,
                )
            val result = client.fetch(FEED_URL)
            val unauthorized = assertIs<RssFetchResult.Unauthorized>(result)
            assertEquals(401, unauthorized.statusCode)
            // Symmetry with the 403 test — if a future refactor split the cases and
            // accidentally introduced a cache write on 401, this catches it.
            assertNull(db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull())
        }

    @Test
    fun networkFailure_returnsNetworkError_doesNotWriteCache() =
        runTest {
            val db = inMemoryDatabase()
            val engine =
                MockEngine { _ ->
                    respondError(HttpStatusCode.InternalServerError, "boom")
                }
            val client = RssFeedClient(client = HttpClient(engine), db = db)
            val result = client.fetch(FEED_URL)
            assertIs<RssFetchResult.NetworkError>(result)
            assertNull(db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull())
        }

    @Test
    fun blankFeedUrl_returnsNetworkError_makesNoHttpCall() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val db = inMemoryDatabase()
            val client =
                clientReturning(
                    captured = captured,
                    status = HttpStatusCode.OK,
                    body = SAMPLE_RSS,
                    responseHeaders = headersOf(),
                    db = db,
                )
            val result = client.fetch("   ")
            assertIs<RssFetchResult.NetworkError>(result)
            assertEquals(0, captured.size, "Blank URL must short-circuit before any HTTP call")
        }

    @Test
    fun lastModifiedOnly_noEtag_stillSendsIfModifiedSinceOnRefetch() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val db = inMemoryDatabase()
            // Seed as if the first response had only Last-Modified (some smaller hosts
            // skip ETag entirely).
            db.rssFeedCacheQueries.upsertCache(
                feedUrl = FEED_URL,
                etag = null,
                lastModified = "Wed, 22 May 2026 09:00:00 GMT",
                lastFetchedAt = 1L,
            )
            val client =
                clientReturning(
                    captured = captured,
                    status = HttpStatusCode.NotModified,
                    body = "",
                    responseHeaders = headersOf(),
                    db = db,
                )

            client.fetch(FEED_URL)
            val req = captured.single()
            assertNull(req.headers["If-None-Match"], "Must NOT fabricate an ETag from null")
            assertEquals("Wed, 22 May 2026 09:00:00 GMT", req.headers["If-Modified-Since"])
        }

    @Test
    fun freshRefetch_replacesEtagWithLatest() =
        runTest {
            val db = inMemoryDatabase()
            db.rssFeedCacheQueries.upsertCache(
                feedUrl = FEED_URL,
                etag = "\"old-etag\"",
                lastModified = "Mon, 01 May 2026 00:00:00 GMT",
                lastFetchedAt = 1L,
            )
            val client =
                clientReturning(
                    captured = mutableListOf(),
                    status = HttpStatusCode.OK,
                    body = SAMPLE_RSS,
                    responseHeaders =
                        headersOf(
                            "ETag" to listOf("\"new-etag\""),
                            "Last-Modified" to listOf("Wed, 22 May 2026 09:00:00 GMT"),
                        ),
                    db = db,
                )

            val result = client.fetch(FEED_URL)
            assertIs<RssFetchResult.Fresh>(result)
            val row = db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOne()
            assertEquals(
                "\"new-etag\"",
                row.etag,
                "Fresh 200 must overwrite the stale ETag — sending the old one next time would be a no-op",
            )
            assertEquals("Wed, 22 May 2026 09:00:00 GMT", row.lastModified)
        }

    @Test
    fun notModified_withNoCacheRow_returnsNetworkError() =
        runTest {
            // A 304 reply when we sent no conditional headers is a server bug
            // (misbehaving CDN or proxy). We have nothing cached to use, so the
            // honest response is "this fetch is unusable" — not NotModified, which
            // would mislead callers into thinking they had episodes to render.
            val db = inMemoryDatabase()
            val client =
                clientReturning(
                    captured = mutableListOf(),
                    status = HttpStatusCode.NotModified,
                    body = "",
                    responseHeaders = headersOf(),
                    db = db,
                )
            val result = client.fetch(FEED_URL)
            val err = assertIs<RssFetchResult.NetworkError>(result)
            assertTrue(
                err.reason.contains("304", ignoreCase = true) ||
                    err.reason.contains("no cached", ignoreCase = true),
                "Reason should mention the misbehaving 304; got: ${err.reason}",
            )
            // No cache row should be written (we have nothing to write).
            assertNull(db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull())
        }

    @Test
    fun bodyExceedingDeclaredContentLengthCap_returnsNetworkError() =
        runTest {
            // Server's own Content-Length declares more than the cap. We must
            // refuse before reading the body at all — a malicious or buggy host
            // sending 1 GB of XML should not be able to fill the heap.
            val db = inMemoryDatabase()
            val client =
                clientReturning(
                    captured = mutableListOf(),
                    status = HttpStatusCode.OK,
                    body = SAMPLE_RSS,
                    responseHeaders =
                        headersOf(
                            "Content-Length" to listOf((RssFeedClient.MAX_BODY_BYTES + 1).toString()),
                        ),
                    db = db,
                )
            val result = client.fetch(FEED_URL)
            val err = assertIs<RssFetchResult.NetworkError>(result)
            assertTrue(
                err.reason.contains("bytes") || err.reason.contains("cap"),
                "Reason must mention the size cap; got: ${err.reason}",
            )
            assertNull(
                db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull(),
                "Oversized fetch must NOT write a cache row — next attempt should retry fresh",
            )
        }

    @Test
    fun chunkedTransferOversizedBody_isCappedDuringRead() =
        runTest {
            // The Content-Length pre-check can't catch chunked-transfer-encoded
            // responses that omit Content-Length entirely. The streaming read with
            // an `MAX + 1`-byte ceiling is what stops a malicious chunked sender
            // from filling memory. We exercise that path by serving a body larger
            // than the cap with no Content-Length header. (MockEngine doesn't add
            // Content-Length automatically when we don't set it explicitly.)
            val db = inMemoryDatabase()
            val oversize = ByteArray((RssFeedClient.MAX_BODY_BYTES + 100).toInt()) { 'a'.code.toByte() }
            val engine =
                MockEngine { _ ->
                    respond(
                        content = oversize,
                        status = HttpStatusCode.OK,
                        headers = headersOf(),
                    )
                }
            val client = RssFeedClient(client = HttpClient(engine), db = db)
            val result = client.fetch(FEED_URL)
            val err = assertIs<RssFetchResult.NetworkError>(result)
            assertTrue(
                err.reason.contains("exceeds") || err.reason.contains("cap"),
                "Reason must indicate the body cap was hit during the streamed read; got: ${err.reason}",
            )
            assertNull(db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull())
        }

    @Test
    fun timeout_returnsNetworkError_doesNotWriteCache() =
        runTest {
            val db = inMemoryDatabase()
            // Configure the client with a 50ms budget and have the engine sleep
            // through it. Using an injected short timeout keeps the test under a
            // second instead of the 15s+ a wall-clock production timeout would
            // require, while still exercising the real `withTimeout` →
            // `TimeoutCancellationException` → `NetworkError` plumbing end-to-end.
            val engine =
                MockEngine { _ ->
                    kotlinx.coroutines.delay(2_000L)
                    respond("", HttpStatusCode.OK)
                }
            val client =
                RssFeedClient(
                    client = HttpClient(engine),
                    db = db,
                    timeoutMs = 50L,
                )
            val result = client.fetch(FEED_URL)
            val err = assertIs<RssFetchResult.NetworkError>(result)
            assertTrue(
                err.reason.contains("timed out", ignoreCase = true),
                "Timeout reason must be human-readable; got: ${err.reason}",
            )
            assertNull(
                db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull(),
                "A timed-out fetch must not write a cache row — next attempt should retry fresh",
            )
        }

    @Test
    fun upstreamCancellation_propagatesAsCancellation() =
        runTest {
            // Real cancellation must NOT be silently converted to a NetworkError —
            // otherwise a parent that cancelled its child would see "the fetch failed"
            // and possibly retry, defeating structured concurrency.
            val db = inMemoryDatabase()
            val engine =
                MockEngine { _ ->
                    // Throw a generic CancellationException (not Timeout) inside the
                    // engine to simulate an upstream cancel that reaches the client.
                    throw kotlinx.coroutines.CancellationException("parent cancelled")
                }
            val client = RssFeedClient(client = HttpClient(engine), db = db)
            try {
                client.fetch(FEED_URL)
                error("Expected CancellationException to propagate, but fetch returned normally")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // expected — the contract is "rethrow CancellationException verbatim"
                assertEquals("parent cancelled", e.message)
            }
        }

    @Test
    fun okWithNoConditionalHeaders_writesNullCacheRow_nextFetchIsUnconditional() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val db = inMemoryDatabase()
            // Engine returns 200 but with neither ETag nor Last-Modified — some smaller
            // hosters do this. The client must still write a (null, null) cache row so
            // it has a record of having fetched; the next fetch then sends no
            // conditional headers (an unconditional GET, correct for these hosts).
            val engine =
                MockEngine { request ->
                    captured.add(request)
                    respond(
                        content = SAMPLE_RSS,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/rss+xml"),
                    )
                }
            val client = RssFeedClient(client = HttpClient(engine), db = db)

            assertIs<RssFetchResult.Fresh>(client.fetch(FEED_URL))
            val row = db.rssFeedCacheQueries.selectByFeedUrl(FEED_URL).executeAsOneOrNull()
            assertNotNull(row, "Cache row must be written even when the response carries no conditional headers")
            assertNull(row.etag)
            assertNull(row.lastModified)

            // Second fetch: ensure neither If-None-Match nor If-Modified-Since is sent,
            // i.e. we don't fabricate empty-string conditional headers off the null row.
            assertIs<RssFetchResult.Fresh>(client.fetch(FEED_URL))
            assertEquals(2, captured.size)
            assertNull(captured[1].headers["If-None-Match"])
            assertNull(captured[1].headers["If-Modified-Since"])
        }

    private fun clientReturning(
        captured: MutableList<HttpRequestData>,
        status: HttpStatusCode,
        body: String,
        responseHeaders: io.ktor.http.Headers,
        db: KofipodDatabase,
        clock: Clock = Clock.System,
    ): RssFeedClient {
        val engine =
            MockEngine { request ->
                captured.add(request)
                respond(
                    content = body,
                    status = status,
                    headers = responseHeaders,
                )
            }
        return RssFeedClient(client = HttpClient(engine), db = db, clock = clock)
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    companion object {
        private const val FEED_URL = "https://feeds.example.com/sample"
        private val SAMPLE_RSS =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Sample</title>
                <description>Test feed</description>
                <link>https://example.com/</link>
                <item>
                  <title>Episode 1</title>
                  <guid>ep1</guid>
                  <pubDate>Wed, 22 May 2026 09:00:00 GMT</pubDate>
                  <enclosure url="https://audio.example.com/1.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
            """.trimIndent()
    }
}
