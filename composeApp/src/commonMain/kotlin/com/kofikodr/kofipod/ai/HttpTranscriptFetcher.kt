// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable

/**
 * Production [TranscriptFetcher] backed by the app-wide [HttpClient]. Transcript
 * URLs are public and require no auth, so we deliberately reuse the shared client
 * rather than the key-bearing AI client — the AI client must never see a URL we
 * can't fully attest to (the `?key=` Logging-plugin guarantee in [AiHttpClient]).
 *
 * The URL itself comes from RSS feed metadata we don't control. A hostile or
 * misconfigured publisher could point us at a multi-GB file or a never-ending stream;
 * [bodyAsChannel] is read with a hard byte cap so we fail fast on responses that
 * would otherwise pin memory or burn the user's mobile data plan. Content-Length is
 * an early reject; the streaming counter is the authoritative gate (some servers
 * lie about Content-Length, others use chunked transfer with none declared).
 *
 * Contract pinned by `HttpTranscriptFetcherTest`.
 */
class HttpTranscriptFetcher(
    private val client: HttpClient,
    private val maxBytes: Long = DEFAULT_MAX_TRANSCRIPT_BYTES,
) : TranscriptFetcher {
    init {
        // The accumulator allocates a single ByteArray of `total.toInt()` at the end;
        // a maxBytes above Int.MAX_VALUE would overflow silently to a negative size
        // and surface as `TranscriptUnavailable` instead of enforcing the intended
        // cap. Pin the constraint up-front so future maintainers don't fall in.
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) {
            "maxBytes must be positive and fit in Int (got $maxBytes)"
        }
    }

    override suspend fun fetch(url: String): Result<String> =
        runCatching {
            val response: HttpResponse = client.get(url)
            if (!response.status.isSuccess()) {
                throw AiErrorException(AiError.TranscriptUnavailable)
            }
            // Early reject: if the server tells us the body is bigger than our cap,
            // don't even start streaming. Avoids paying the network cost of a hostile
            // declared-large body.
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > maxBytes) {
                throw AiErrorException(AiError.TranscriptUnavailable)
            }
            readBoundedText(response, maxBytes)
        }.recoverCatching { throwable ->
            throw if (throwable is AiErrorException) throwable else AiErrorException(AiError.TranscriptUnavailable)
        }

    companion object {
        /**
         * Max bytes we'll buffer for a transcript before giving up. Sized for the
         * worst-case real podcast — a 4-hour audiobook-style episode transcribed
         * at ~1 KB/sec works out to ~14 MB; 16 MB leaves headroom while preventing
         * a hostile feed from pinning hundreds of MB of memory. Kept high enough
         * that no legitimate publisher will hit it. Tests inject a smaller cap.
         */
        const val DEFAULT_MAX_TRANSCRIPT_BYTES = 16L * 1024L * 1024L
    }
}

private const val READ_BUFFER_SIZE = 8 * 1024

/**
 * Stream [response]'s body and decode as UTF-8, refusing to keep more than
 * [maxBytes] in flight. We accumulate chunks rather than concatenate strings so a
 * multi-byte UTF-8 sequence split across reads doesn't decode incorrectly. The
 * channel is cancelled the moment we cross the cap so the underlying socket can
 * release without draining the remainder of a hostile body.
 */
private suspend fun readBoundedText(
    response: HttpResponse,
    maxBytes: Long,
): String {
    val channel = response.bodyAsChannel()
    val chunks = mutableListOf<ByteArray>()
    var total = 0L
    val buf = ByteArray(READ_BUFFER_SIZE)
    while (true) {
        val read = channel.readAvailable(buf, 0, buf.size)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            channel.cancel(AiErrorException(AiError.TranscriptUnavailable))
            throw AiErrorException(AiError.TranscriptUnavailable)
        }
        chunks.add(if (read == buf.size) buf.copyOf() else buf.copyOf(read))
    }
    if (total == 0L) return ""
    val combined = ByteArray(total.toInt())
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(combined, offset)
        offset += chunk.size
    }
    return combined.decodeToString()
}
