// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Raised when Readwise answers a highlight create/update with a non-2xx status.
 * Carries the numeric [status] so [ReadwiseSink] can distinguish failures that
 * will never succeed as-is (auth/permission — the token must be reconnected)
 * from transient ones (network / rate-limit / 5xx) that are worth retrying.
 */
class ReadwiseHttpException(val status: Int) :
    Exception("Readwise request failed: HTTP $status") {
    /** 401/403 — the saved token is invalid or revoked; prompt a reconnect. */
    val isAuthFailure: Boolean get() = status == 401 || status == 403

    /** 408 timeout, 429 rate-limit, and 5xx are worth retrying; other 4xx are not. */
    val isTransient: Boolean get() = status == 408 || status == 429 || status in 500..599
}

open class ReadwiseClient(private val client: HttpClient) {
    open suspend fun verify(token: String): Boolean {
        val resp: HttpResponse =
            client.get("https://readwise.io/api/v2/auth/") {
                header("Authorization", "Token $token")
            }
        return resp.status == HttpStatusCode.NoContent || resp.status == HttpStatusCode.OK
    }

    open suspend fun createHighlight(
        token: String,
        request: ReadwiseCreateRequest,
    ): Result<Long> =
        runCatching {
            val resp =
                client.post("https://readwise.io/api/v3/highlights/") {
                    header("Authorization", "Token $token")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            if (!resp.status.isSuccess()) {
                throw ReadwiseHttpException(resp.status.value)
            }
            val body: List<ReadwiseCreateResponseItem> = resp.body()
            body.firstOrNull()?.id ?: error("Readwise returned empty body")
        }

    open suspend fun updateHighlight(
        token: String,
        id: Long,
        request: ReadwiseUpdateRequest,
    ): Result<Unit> =
        runCatching {
            val resp =
                client.patch("https://readwise.io/api/v2/highlights/$id/") {
                    header("Authorization", "Token $token")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            if (!resp.status.isSuccess()) {
                throw ReadwiseHttpException(resp.status.value)
            }
            Unit
        }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
