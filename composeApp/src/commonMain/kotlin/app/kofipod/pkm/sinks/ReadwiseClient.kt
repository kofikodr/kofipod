// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

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

class ReadwiseClient(private val client: HttpClient) {
    suspend fun verify(token: String): Boolean {
        val resp: HttpResponse =
            client.get("https://readwise.io/api/v2/auth/") {
                header("Authorization", "Token $token")
            }
        return resp.status == HttpStatusCode.NoContent || resp.status == HttpStatusCode.OK
    }

    suspend fun createHighlight(
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
                error("Readwise POST failed: ${resp.status}")
            }
            val body: List<ReadwiseCreateResponseItem> = resp.body()
            body.firstOrNull()?.id ?: error("Readwise returned empty body")
        }

    suspend fun updateHighlight(
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
                error("Readwise PATCH failed: ${resp.status}")
            }
            Unit
        }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
