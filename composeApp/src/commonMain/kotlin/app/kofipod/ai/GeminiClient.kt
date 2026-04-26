// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Thin wrapper over the Gemini Developer API (`generativelanguage.googleapis.com`).
 *
 * Slice 1 ships only [validate]: a near-zero-cost `generateContent` round-trip that
 * confirms a pasted key actually works against Google. Files API upload + audio
 * `generateContent` arrive in Slice 2.
 *
 * The API key is passed at request scope (the `?key=` query param), never persisted
 * into the client, so the same instance survives a key rotation without rebuild.
 */
class GeminiClient(private val client: HttpClient) {
    /**
     * Issues a 4-token completion request. Returns [Result.success] on HTTP 200 and
     * [Result.failure] wrapping an [AiError] otherwise.
     *
     * Network failures surface as [AiError.Network]. We never log the key, the prompt,
     * or the response body — only the HTTP status code on non-2xx responses.
     */
    suspend fun validate(
        apiKey: String,
        model: GeminiModel,
    ): Result<Unit> {
        val response: HttpResponse =
            runCatching {
                client.post("$BASE_URL/v1beta/models/${model.apiId}:generateContent") {
                    contentType(ContentType.Application.Json)
                    url { parameters.append("key", apiKey) }
                    setBody(
                        GenerateContentRequest(
                            contents = listOf(Content(listOf(Part(text = "Say OK")))),
                            generationConfig = GenerationConfig(maxOutputTokens = 4, temperature = 0.0),
                        ),
                    )
                }
            }.getOrElse { return Result.failure(AiErrorException(AiError.Network)) }

        return when {
            response.status.isSuccess() -> Result.success(Unit)
            response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ->
                Result.failure(AiErrorException(AiError.KeyInvalid))
            response.status == HttpStatusCode.TooManyRequests ->
                Result.failure(AiErrorException(AiError.RateLimited))
            else ->
                Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
        }
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"
    }
}

/**
 * Exception form of [AiError] so callers can use [Result] / `runCatching` ergonomics
 * without losing the structured error type. UI code unwraps via `error.toAiError()`.
 */
class AiErrorException(val error: AiError) : Exception(error::class.simpleName)

fun Throwable.toAiError(): AiError =
    when (this) {
        is AiErrorException -> error
        else -> AiError.Unknown()
    }

// --------------------------------------------------------------------------
// Request DTOs (kept private to this file — these match Gemini's REST shape).
// --------------------------------------------------------------------------

@Serializable
private data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig,
)

@Serializable
private data class Content(val parts: List<Part>)

@Serializable
private data class Part(val text: String)

@Serializable
private data class GenerationConfig(
    val maxOutputTokens: Int,
    val temperature: Double,
)
