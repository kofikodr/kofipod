// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Tiny seam over the "validate this API key" call so [AiSetupViewModel] can be
 * unit-tested against a synchronous fake without standing up Ktor's MockEngine
 * (whose internal dispatcher doesn't compose with `runTest`'s virtual scheduler).
 * The production [GeminiClient] is the only real implementation today.
 */
fun interface KeyValidator {
    suspend fun validate(
        apiKey: String,
        model: GeminiModel,
    ): Result<Unit>
}

/**
 * Seam over the text-summarisation call so [AiSummaryRepository] can be unit-tested
 * against a synchronous fake. Same rationale as [KeyValidator]; the production
 * [GeminiClient] is the only real implementation.
 */
fun interface TextSummariser {
    suspend fun generateFromText(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        content: String,
    ): Result<String>
}

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
class GeminiClient(private val client: HttpClient) : KeyValidator, TextSummariser {
    /**
     * Issues a 4-token completion request. Returns [Result.success] on HTTP 200 and
     * [Result.failure] wrapping an [AiError] otherwise.
     *
     * Network failures surface as [AiError.Network]. We never log the key, the prompt,
     * or the response body — only the HTTP status code on non-2xx responses.
     */
    override suspend fun validate(
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
            }.getOrElse {
                logTransportFailure("validate", it)
                return Result.failure(AiErrorException(AiError.Network))
            }

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

    /**
     * Issues a `generateContent` call with two text parts: the prompt, then the
     * raw [content] (typically a transcript body in VTT / SRT / JSON / plain text —
     * the prompt instructs the model to handle format detection itself).
     *
     * Returns the generated summary text on success. Status code mapping mirrors
     * [validate]: 400/401/403 → `KeyInvalid`, 429 → `RateLimited`, IO →
     * `Network`, anything else → `Unknown`. We never log the prompt, the
     * content, or the response body.
     */
    override suspend fun generateFromText(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        content: String,
    ): Result<String> {
        val response: HttpResponse =
            runCatching {
                client.post("$BASE_URL/v1beta/models/${model.apiId}:generateContent") {
                    contentType(ContentType.Application.Json)
                    url { parameters.append("key", apiKey) }
                    setBody(
                        GenerateContentRequest(
                            contents = listOf(Content(listOf(Part(prompt), Part(content)))),
                            generationConfig =
                                GenerationConfig(
                                    maxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS,
                                    temperature = SUMMARY_TEMPERATURE,
                                ),
                        ),
                    )
                }
            }.getOrElse {
                logTransportFailure("generateFromText", it)
                return Result.failure(AiErrorException(AiError.Network))
            }

        if (!response.status.isSuccess()) {
            logHttpFailure("generateFromText", response.status.value)
            return Result.failure(AiErrorException(response.status.toAiError()))
        }
        val parsed =
            runCatching { response.body<GenerateContentResponse>() }
                .getOrElse {
                    logParseFailure("generateFromText", it)
                    return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
                }
        val text =
            parsed.candidates.firstOrNull()
                ?.content?.parts
                ?.firstOrNull { it.text.isNotBlank() }
                ?.text
                ?.trim()
        return if (text.isNullOrBlank()) {
            Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
        } else {
            Result.success(text)
        }
    }

    private fun HttpStatusCode.toAiError(): AiError =
        when (this) {
            HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> AiError.KeyInvalid
            HttpStatusCode.TooManyRequests -> AiError.RateLimited
            else -> AiError.Unknown(value)
        }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"

        // Slice 2 prompt asks for ~200 words; 512 tokens leaves headroom for
        // languages that tokenise less efficiently than English (e.g. Thai, JP).
        private const val SUMMARY_MAX_OUTPUT_TOKENS = 512
        private const val SUMMARY_TEMPERATURE = 0.4

        // Diagnostic log tag. Filterable via `adb logcat -s Kofipod-AI:V`. We
        // never log the request body, response body, or API key — only the
        // throwable's class name and (for HTTP failures) the status code.
        private const val LOG_TAG = "Kofipod-AI"

        private fun logTransportFailure(
            op: String,
            throwable: Throwable,
        ) {
            println("$LOG_TAG: $op transport failed: ${throwable::class.simpleName}")
        }

        private fun logHttpFailure(
            op: String,
            status: Int,
        ) {
            println("$LOG_TAG: $op HTTP $status")
        }

        private fun logParseFailure(
            op: String,
            throwable: Throwable,
        ) {
            println("$LOG_TAG: $op response parse failed: ${throwable::class.simpleName}")
        }
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
private data class Part(val text: String = "")

@Serializable
private data class GenerationConfig(
    val maxOutputTokens: Int,
    val temperature: Double,
)

// --------------------------------------------------------------------------
// Response DTOs (only the fields we actually read — Gemini returns much more).
// --------------------------------------------------------------------------

@Serializable
private data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
)

@Serializable
private data class Candidate(val content: Content = Content(emptyList()))
