// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Production [TranscriptFetcher] backed by the app-wide [HttpClient]. Transcript
 * URLs are public and require no auth, so we deliberately reuse the shared client
 * rather than the key-bearing AI client — the AI client must never see a URL we
 * can't fully attest to (the `?key=` Logging-plugin guarantee in [AiHttpClient]).
 */
class HttpTranscriptFetcher(private val client: HttpClient) : TranscriptFetcher {
    override suspend fun fetch(url: String): Result<String> =
        runCatching {
            val response: HttpResponse = client.get(url)
            if (!response.status.isSuccess()) {
                throw AiErrorException(AiError.TranscriptUnavailable)
            }
            response.bodyAsText()
        }.recoverCatching { throwable ->
            throw if (throwable is AiErrorException) throwable else AiErrorException(AiError.TranscriptUnavailable)
        }
}
