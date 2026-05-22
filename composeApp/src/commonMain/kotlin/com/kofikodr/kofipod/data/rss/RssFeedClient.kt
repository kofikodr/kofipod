// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.rss

import com.kofikodr.kofipod.db.KofipodDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.io.readByteArray

/**
 * HTTP-conditional-GET wrapper around publisher RSS feeds (Slice B.2 — see
 * `docs/superpowers/plans/2026-05-23-itunes-rss-slice-b.md`).
 *
 * The point of this client is to defeat Podcast Index's crawl lag without burning
 * bandwidth on every refresh. On the first fetch we record the publisher's `ETag`
 * and `Last-Modified` headers; on subsequent fetches we echo them back via
 * `If-None-Match` / `If-Modified-Since` so polite hosts (most major hosts —
 * Libsyn, Megaphone, Acast, Simplecast, Spotify) return a ~200-byte 304 instead of
 * the full 10–20 MB feed body. For niche shows on most days that's the
 * overwhelmingly common case.
 *
 * The shared [HttpClient] from `buildHttpClient` follows redirects by default, which
 * is what we want — many publisher feeds go through ad-tracking prefix services
 * (`op3.dev`, `chartable.com`, `pdst.fm`) that 301 to the real CDN.
 *
 * Cancellation: regular structured cancellation rules apply. We rethrow
 * [CancellationException] verbatim and never let timeouts masquerade as fetch
 * failures.
 *
 * @see RssParser for the parse step; this wrapper only owns HTTP + cache state.
 */
class RssFeedClient(
    private val client: HttpClient,
    private val db: KofipodDatabase,
    private val clock: Clock = Clock.System,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun fetch(feedUrl: String): RssFetchResult {
        if (feedUrl.isBlank()) return RssFetchResult.NetworkError("feed URL is blank")
        return withContext(Dispatchers.Default) {
            val cached = db.rssFeedCacheQueries.selectByFeedUrl(feedUrl).executeAsOneOrNull()
            try {
                withTimeout(timeoutMs) {
                    val response =
                        client.get(feedUrl) {
                            cached?.etag?.takeIf { it.isNotBlank() }?.let {
                                header(HttpHeaders.IfNoneMatch, it)
                            }
                            cached?.lastModified?.takeIf { it.isNotBlank() }?.let {
                                header(HttpHeaders.IfModifiedSince, it)
                            }
                        }
                    when (val status = response.status) {
                        HttpStatusCode.NotModified -> {
                            if (cached == null) {
                                // A 304 without a cache row means we sent no conditional
                                // headers (first fetch) and the server still returned
                                // "not modified" — misbehaving CDN or proxy. We have
                                // nothing to use, so surface as a real error rather
                                // than lying that all is well.
                                RssFetchResult.NetworkError("server returned 304 with no cached state")
                            } else {
                                // The body didn't change but the publisher still answered — touch
                                // `lastFetchedAt` so observers can tell live-but-quiet feeds apart
                                // from stale rows that were never re-checked.
                                db.rssFeedCacheQueries.upsertCache(
                                    feedUrl = feedUrl,
                                    etag = cached.etag,
                                    lastModified = cached.lastModified,
                                    lastFetchedAt = clock.now().toEpochMilliseconds(),
                                )
                                RssFetchResult.NotModified
                            }
                        }
                        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                            RssFetchResult.Unauthorized(status.value)
                        else -> {
                            if (!status.isSuccess()) {
                                RssFetchResult.NetworkError("HTTP ${status.value}")
                            } else {
                                // Body-size cap: RSS bodies are untrusted publisher content
                                // and the type docs already note 10–20 MB feeds are normal.
                                // A malicious or buggy host can serve gigabytes; without a
                                // cap, `bodyAsText()` would happily fill the heap and OOM.
                                // Pre-check the declared Content-Length, then read at most
                                // MAX_BODY_BYTES + 1 from the body so chunked-transfer hosts
                                // can't bypass the pre-check.
                                val declared = response.contentLength()
                                if (declared != null && declared > MAX_BODY_BYTES) {
                                    RssFetchResult.NetworkError(
                                        "feed declared $declared bytes; cap is $MAX_BODY_BYTES",
                                    )
                                } else {
                                    val source = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1)
                                    val bytes = source.readByteArray()
                                    if (bytes.size > MAX_BODY_BYTES) {
                                        RssFetchResult.NetworkError(
                                            "feed body exceeds $MAX_BODY_BYTES bytes",
                                        )
                                    } else {
                                        val channel = RssParser.parse(bytes.decodeToString())
                                        db.rssFeedCacheQueries.upsertCache(
                                            feedUrl = feedUrl,
                                            etag = response.headers[HttpHeaders.ETag],
                                            lastModified = response.headers[HttpHeaders.LastModified],
                                            lastFetchedAt = clock.now().toEpochMilliseconds(),
                                        )
                                        RssFetchResult.Fresh(channel)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // `withTimeout` blew the budget. This IS a CancellationException
                // subtype, but we deliberately catch the specific subtype before the
                // generic re-raise below so the caller can render a useful "timed out"
                // message instead of having the whole job collapse.
                RssFetchResult.NetworkError("timed out after ${timeoutMs}ms — ${e.message ?: "no detail"}")
            } catch (e: CancellationException) {
                // Real upstream cancellation. Re-raise verbatim — swallowing it here
                // would convert a structured-concurrency signal into a phantom network
                // error and let parent jobs run on after the user navigated away.
                throw e
            } catch (e: Exception) {
                // Connection refused, DNS failure, body-read I/O, etc. We don't write to
                // the cache on failure — leaves the existing row (if any) for the next
                // attempt to keep using its conditional headers.
                RssFetchResult.NetworkError(e.message ?: e::class.simpleName ?: "unknown")
            }
        }
    }

    companion object {
        /**
         * RSS bodies can be a few MB on shows with full history, and some hosts are slow
         * to TTFB. 15s is generous compared to the [com.kofikodr.kofipod.data.repo.AggregateSearchSource]'s
         * 10s search budget — search is interactive, RSS fetch is mostly background work.
         *
         * Exposed as a default rather than a hard constant so tests can construct a
         * client with a small budget. Production wiring (via [com.kofikodr.kofipod.di.CommonModule])
         * always uses this default.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 15_000L

        /**
         * Maximum response body we accept from a publisher RSS feed. Real-world
         * feeds top out around 20 MB (long-running shows with full episode history);
         * 25 MB gives a comfortable margin while still bounding worst-case heap use
         * to roughly the same order of magnitude as Android Auto Backup's cap.
         *
         * Anything larger is either malicious or so unusual that we'd rather fail
         * closed with a NetworkError than risk an OOM on a low-RAM device. Tests
         * inject a smaller value through the same plumbing as [DEFAULT_TIMEOUT_MS].
         */
        const val MAX_BODY_BYTES: Long = 25L * 1024L * 1024L
    }
}

/**
 * Discriminated outcome of an [RssFeedClient.fetch] call. Sealed so callers must
 * decide explicitly what to do per case rather than treating a 304 the same as a
 * blank-body parse.
 */
sealed interface RssFetchResult {
    /** 200 with a parsed channel. The cache row has been refreshed with the latest headers. */
    data class Fresh(val channel: RssChannel) : RssFetchResult

    /** 304 — nothing changed. Caller should use whatever is already in the Episode table. */
    object NotModified : RssFetchResult

    /**
     * 401 / 403 — paywalled or otherwise access-restricted feed. We don't auto-retry;
     * the UI surfaces this as a "this feed isn't publicly readable" message.
     */
    data class Unauthorized(val statusCode: Int) : RssFetchResult

    /** DNS, connection refused, body-read I/O, timeout, or non-2xx-non-3xx status. */
    data class NetworkError(val reason: String) : RssFetchResult
}
