// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * [GeminiClient] is the only real implementation. Returns the parsed structured
 * response — the wire-shape mapping happens inside [GeminiClient] so the
 * repository never sees raw JSON.
 */
fun interface TextSummariser {
    suspend fun generateFromText(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        content: String,
    ): Result<AiSummaryJson>
}

/**
 * Seam over the audio-summarisation pipeline (upload → poll → generate → delete)
 * so [AiSummaryRepository] can be unit-tested against a synchronous fake. The
 * production wiring (in `CommonModule`) opens the local audio file via the
 * [openLocalFileChannel] expect-fun, then delegates to [GeminiClient.summariseAudio].
 */
fun interface AudioSummariser {
    /**
     * @param onStage receives [GenerationStage.Preparing] before upload starts and
     *   [GenerationStage.Analysing] after the upload finalises. The repository
     *   uses these transitions to drive the staged progress UI without having to
     *   own the underlying multi-step pipeline. Tests can ignore the callback.
     */
    suspend fun summariseAudio(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
        onStage: (GenerationStage) -> Unit,
    ): Result<AiSummaryJson>
}

/**
 * Files API uploaded-file metadata. Returned by [GeminiClient.uploadAudio] and
 * [GeminiClient.pollUntilActive]; passed through to [GeminiClient.generateFromAudio]
 * (we read [uri] + [mimeType]) and [GeminiClient.deleteFile] (we read [name]).
 *
 * `state` follows Gemini's lifecycle: `PROCESSING` immediately after upload,
 * `ACTIVE` once Gemini has indexed the file, `FAILED` on a server-side error.
 * The Files API auto-deletes after 48h.
 */
@Serializable
data class UploadedFile(
    val name: String,
    val uri: String,
    val mimeType: String,
    val sizeBytes: String? = null,
    val state: String,
)

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
    ): Result<AiSummaryJson> {
        val response: HttpResponse =
            runCatching {
                client.post("$BASE_URL/v1beta/models/${model.apiId}:generateContent") {
                    contentType(ContentType.Application.Json)
                    url { parameters.append("key", apiKey) }
                    setBody(
                        GenerateContentRequest(
                            contents = listOf(Content(listOf(Part(text = prompt), Part(text = content)))),
                            generationConfig = summaryGenerationConfig(),
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
        return decodeStructuredResponse(response, "generateFromText")
    }

    /**
     * Resumable upload to the Files API. Done in two HTTP round-trips per Google's
     * resumable protocol:
     *
     *  1. `POST /upload/v1beta/files?uploadType=resumable&key=…` with the metadata
     *     body — Gemini replies with the upload URL in the `X-Goog-Upload-URL`
     *     header. We never write that URL to a log; it's a single-use bearer.
     *  2. `PUT <uploadUrl>` streaming the audio bytes (`upload, finalize`
     *     command). Body is parsed as `{"file": UploadedFile}`.
     *
     * The returned [UploadedFile] is typically `state == "PROCESSING"` — call
     * [pollUntilActive] before passing the URI to [generateFromAudio].
     *
     * The audio bytes are read from [fileChannel] — a fresh channel per call,
     * since Ktor will drain it. We never log the file path or any byte content.
     */
    suspend fun uploadAudio(
        apiKey: String,
        fileChannel: ByteReadChannel,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
    ): Result<UploadedFile> {
        val startResponse: HttpResponse =
            runCatching {
                client.post("$BASE_URL/upload/v1beta/files") {
                    url {
                        parameters.append("key", apiKey)
                        parameters.append("uploadType", "resumable")
                    }
                    headers {
                        append("X-Goog-Upload-Protocol", "resumable")
                        append("X-Goog-Upload-Command", "start")
                        append("X-Goog-Upload-Header-Content-Length", sizeBytes.toString())
                        append("X-Goog-Upload-Header-Content-Type", mimeType)
                    }
                    contentType(ContentType.Application.Json)
                    setBody(StartUploadRequest(StartUploadFile(displayName = displayName)))
                }
            }.getOrElse {
                logTransportFailure("uploadAudio.start", it)
                return Result.failure(AiErrorException(AiError.Network))
            }

        if (!startResponse.status.isSuccess()) {
            logHttpFailure("uploadAudio.start", startResponse.status.value)
            return Result.failure(AiErrorException(startResponse.status.toAiError()))
        }
        val uploadUrl =
            startResponse.headers["X-Goog-Upload-URL"]
                ?: return Result.failure(AiErrorException(AiError.Unknown(startResponse.status.value)))

        val finalizeResponse: HttpResponse =
            runCatching {
                client.put(uploadUrl) {
                    headers {
                        append("X-Goog-Upload-Command", "upload, finalize")
                        append("X-Goog-Upload-Offset", "0")
                    }
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentLength = sizeBytes
                            override val contentType = ContentType.parse(mimeType)

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                fileChannel.copyAndClose(channel)
                            }
                        },
                    )
                }
            }.getOrElse {
                logTransportFailure("uploadAudio.finalize", it)
                return Result.failure(AiErrorException(AiError.Network))
            }

        if (!finalizeResponse.status.isSuccess()) {
            logHttpFailure("uploadAudio.finalize", finalizeResponse.status.value)
            return Result.failure(AiErrorException(finalizeResponse.status.toAiError()))
        }
        return runCatching { finalizeResponse.body<FileEnvelope>().file }
            .map { Result.success(it) }
            .getOrElse {
                logParseFailure("uploadAudio.finalize", it)
                Result.failure(AiErrorException(AiError.Unknown(finalizeResponse.status.value)))
            }
    }

    /**
     * Polls `GET /v1beta/{name}?key=…` until the file's `state` flips from
     * `PROCESSING` to `ACTIVE`. Caps at [pollTimeoutMs] (5 min default) —
     * beyond that we surface [AiError.Unknown] rather than block the UI
     * forever. Real episodes land between 30s and 2 min in PROCESSING for
     * typical 30–60 MB MP3s; the original 30s cap was too aggressive and
     * showed users a generic error mid-pipeline.
     *
     * `pollIntervalMs` and `pollTimeoutMs` are injected so unit tests can use
     * tighter values without real wall-clock waits.
     */
    suspend fun pollUntilActive(
        apiKey: String,
        name: String,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
    ): Result<UploadedFile> {
        // First check is immediate — the upload's finalize response often already
        // reports ACTIVE for short clips, and we'd otherwise pay an unnecessary
        // [pollIntervalMs] wait before learning that.
        val maxAttempts = (pollTimeoutMs / pollIntervalMs).toInt().coerceAtLeast(1)
        repeat(maxAttempts) { attempt ->
            val response: HttpResponse =
                runCatching {
                    client.get("$BASE_URL/v1beta/$name") {
                        url { parameters.append("key", apiKey) }
                    }
                }.getOrElse {
                    logTransportFailure("pollUntilActive", it)
                    return Result.failure(AiErrorException(AiError.Network))
                }
            if (!response.status.isSuccess()) {
                logHttpFailure("pollUntilActive", response.status.value)
                return Result.failure(AiErrorException(response.status.toAiError()))
            }
            val file =
                runCatching { response.body<UploadedFile>() }
                    .getOrElse {
                        logParseFailure("pollUntilActive", it)
                        return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
                    }
            if (file.state == "ACTIVE") return Result.success(file)
            if (file.state == "FAILED") return Result.failure(AiErrorException(AiError.Unknown(null)))
            // Don't sleep after the final attempt — we're about to fall through
            // to the timeout failure anyway.
            if (attempt < maxAttempts - 1) delay(pollIntervalMs)
        }
        return Result.failure(AiErrorException(AiError.Unknown(null)))
    }

    /**
     * Issues `generateContent` with a `fileData` part referencing an already-uploaded
     * audio file plus a text prompt. Same status-code mapping as [generateFromText],
     * with one addition: a 400 carrying `INVALID_ARGUMENT` and a "exceeds the maximum"
     * / token-budget message maps to [AiError.AudioTooLong] so the UI can show a
     * dedicated copy variant rather than the generic "key invalid" card.
     */
    suspend fun generateFromAudio(
        apiKey: String,
        model: GeminiModel,
        fileUri: String,
        mimeType: String,
        prompt: String,
    ): Result<AiSummaryJson> {
        // Retry transient 5xx (notably 503 "model overloaded", common on the
        // Flash tier during peak hours) with exponential backoff. The upload
        // is already finalised on Gemini's side at this point, so a retry is
        // cheap — we're only re-issuing the generateContent request, not
        // re-uploading the audio. We don't retry 429: that's the user's
        // quota and the right surface is the dedicated RateLimited card,
        // not a silent burn through their daily budget.
        var attempt = 0
        while (true) {
            val response: HttpResponse =
                runCatching {
                    client.post("$BASE_URL/v1beta/models/${model.apiId}:generateContent") {
                        contentType(ContentType.Application.Json)
                        url { parameters.append("key", apiKey) }
                        setBody(
                            GenerateContentRequest(
                                contents =
                                    listOf(
                                        Content(
                                            listOf(
                                                Part(fileData = FileData(mimeType = mimeType, fileUri = fileUri)),
                                                Part(text = prompt),
                                            ),
                                        ),
                                    ),
                                generationConfig = summaryGenerationConfig(),
                            ),
                        )
                    }
                }.getOrElse {
                    logTransportFailure("generateFromAudio", it)
                    return Result.failure(AiErrorException(AiError.Network))
                }

            if (response.status.isSuccess()) {
                return decodeStructuredResponse(response, "generateFromAudio")
            }
            val transient = response.status.value in TRANSIENT_5XX
            if (transient && attempt < GENERATE_MAX_RETRIES) {
                logHttpFailure("generateFromAudio", response.status.value)
                delay(GENERATE_RETRY_BACKOFF_MS shl attempt)
                attempt++
                continue
            }
            val bodyText = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            logHttpFailure("generateFromAudio", response.status.value)
            return Result.failure(AiErrorException(response.status.toAudioAiError(bodyText)))
        }
    }

    /**
     * Orchestrates the full audio pipeline: [uploadAudio] → [pollUntilActive] →
     * [generateFromAudio] → best-effort [deleteFile]. This is the path
     * [AiSummaryRepository] takes for the audio fallback; tests can use the
     * primitive methods directly via [GeminiClientAudioTest].
     *
     * The cleanup runs in `finally` so the uploaded file is removed even when
     * generation fails — otherwise a failed retry could leave duplicates lying
     * around for 48h until Gemini's auto-purge kicks in.
     */
    suspend fun summariseAudio(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        fileChannel: ByteReadChannel,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
        onStage: (GenerationStage) -> Unit = {},
    ): Result<AiSummaryJson> {
        onStage(GenerationStage.Preparing)
        val uploaded =
            uploadAudio(apiKey, fileChannel, mimeType, sizeBytes, displayName)
                .getOrElse { return Result.failure(it) }
        return try {
            // Upload byte stream is now on Gemini's side. The poll + generate
            // round-trip is the slow tail (Gemini transcribes + reasons over
            // the audio), so flip the stage indicator here so the UI doesn't
            // sit on "Uploading" while we're really waiting on the model.
            onStage(GenerationStage.Analysing)
            val active =
                pollUntilActive(apiKey, uploaded.name)
                    .getOrElse { return Result.failure(it) }
            generateFromAudio(apiKey, model, active.uri, active.mimeType, prompt)
        } finally {
            deleteFile(apiKey, uploaded.name)
        }
    }

    /**
     * Best-effort Files API delete. Returns success even on HTTP failures — the
     * caller treats this as a hint, not a guarantee, because Gemini auto-deletes
     * uploaded files after 48h anyway. Transport failures are swallowed too.
     */
    suspend fun deleteFile(
        apiKey: String,
        name: String,
    ): Result<Unit> {
        runCatching {
            client.delete("$BASE_URL/v1beta/$name") {
                url { parameters.append("key", apiKey) }
            }
        }.onFailure {
            logTransportFailure("deleteFile", it)
        }
        return Result.success(Unit)
    }

    /**
     * Builds the [GenerationConfig] used by both `generateFromText` and
     * `generateFromAudio`. Pinned in one place so the two paths can never drift
     * — and so the structured-output schema lands consistently on every call.
     *
     * `responseMimeType: application/json` + `responseSchema` instructs Gemini
     * to emit a JSON document matching [SUMMARY_RESPONSE_SCHEMA]; the
     * accompanying prompt clarifies *what* belongs in each field.
     */
    private fun summaryGenerationConfig(): GenerationConfig =
        GenerationConfig(
            maxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS,
            temperature = SUMMARY_TEMPERATURE,
            // Disable Flash 2.5's chain-of-thought tokens. Reasoning is billed
            // against maxOutputTokens, and on long transcripts it can burn the
            // whole budget, leaving the visible response cut off mid-sentence.
            thinkingConfig = ThinkingConfig(thinkingBudget = 0),
            responseMimeType = "application/json",
            responseSchema = SUMMARY_RESPONSE_SCHEMA,
        )

    /**
     * Pulls the JSON document out of a successful `generateContent` response and
     * parses it as [AiSummaryJson]. Treats four cases as failures:
     *
     *  - empty/blank candidate text → [AiError.Unknown]
     *  - JSON parse failure → [AiError.Unknown] (we log the exception type
     *    only — never the body, which can carry transcript content)
     *  - empty `summary` → [AiError.Unknown] (a valid envelope with nothing to
     *    show is indistinguishable from a failed run as far as the user cares)
     *  - non-`STOP` finish reason → still passes through, but logged so a
     *    truncation report has a forensic breadcrumb.
     */
    private suspend fun decodeStructuredResponse(
        response: HttpResponse,
        op: String,
    ): Result<AiSummaryJson> {
        val parsed =
            runCatching { response.body<GenerateContentResponse>() }
                .getOrElse {
                    logParseFailure(op, it)
                    return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
                }
        val candidate = parsed.candidates.firstOrNull()
        // Gemini can split a single response across multiple `parts` (e.g. one
        // per stream chunk). Joining all non-blank text preserves the JSON
        // body even if the model emits it across boundaries.
        val text =
            candidate
                ?.content?.parts
                ?.mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
                ?.joinToString(separator = "")
                ?.trim()
        val reason = candidate?.finishReason
        if (reason != null && reason != "STOP") {
            logFinishReason(op, reason)
        }
        if (text.isNullOrBlank()) {
            return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
        }
        val structured =
            runCatching { aiJson.decodeFromString<AiSummaryJson>(text) }
                .getOrElse {
                    logParseFailure(op, it)
                    return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
                }
        if (structured.summary.isBlank()) {
            return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
        }
        return Result.success(structured)
    }

    private fun HttpStatusCode.toAiError(): AiError =
        when (this) {
            HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> AiError.KeyInvalid
            HttpStatusCode.TooManyRequests -> AiError.RateLimited
            else -> AiError.Unknown(value)
        }

    /**
     * Audio variant of [toAiError]. A 400 with `INVALID_ARGUMENT` and a message
     * announcing the size cap is Gemini's way of saying "this audio is past my
     * context window". Anything else falls through to the same mapping the
     * text path uses — a malformed or rejected key still surfaces as KeyInvalid.
     *
     * The heuristic is deliberately narrow: `"exceeds the maximum"` is the
     * specific phrase Gemini uses for context-window overflows. Earlier drafts
     * also matched on the bare substring `"token"`, but that's far too broad —
     * a key-auth error like `"invalid authentication token"` would have been
     * misclassified as AudioTooLong, showing the user "this episode is too
     * long" with no retry action when their actual problem is a bad key.
     */
    private fun HttpStatusCode.toAudioAiError(body: String): AiError {
        if (this != HttpStatusCode.BadRequest) return toAiError()
        val parsed = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val status = parsed?.get("status")?.jsonPrimitive?.content
        val message = parsed?.get("message")?.jsonPrimitive?.content.orEmpty()
        val tooLong =
            status == "INVALID_ARGUMENT" &&
                message.contains("exceeds the maximum", ignoreCase = true)
        return if (tooLong) AiError.AudioTooLong else AiError.KeyInvalid
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"

        // Lenient configuration for the model's structured output. Gemini is
        // free to evolve the response envelope (e.g. add a `confidence` field),
        // and a strict parser would surface those additions as AiError.Unknown
        // instead of silently ignoring the new key. Defaults on AiSummaryJson
        // cover the inverse case — a missing entity list parses as empty.
        private val aiJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        // Slice 2 sized this for a ~200-word prose summary alone (1024 tokens).
        // Slice 3 added three entity arrays inside the same JSON envelope, and
        // long-URL `links` plus less-efficient non-English tokenisation pushed
        // real episodes past that cap — surfacing as MAX_TOKENS truncation and
        // an unparseable JSON document. 8192 is generous headroom; we only pay
        // for what the model actually emits, so the cap matters only as a
        // ceiling against runaway responses.
        private const val SUMMARY_MAX_OUTPUT_TOKENS = 8192
        private const val SUMMARY_TEMPERATURE = 0.4

        private const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        private const val DEFAULT_POLL_TIMEOUT_MS = 5L * 60 * 1000

        // Retry budget for transient 5xx on generateFromAudio. Backoff doubles
        // each attempt: 2s, 4s, 8s. Three retries is a sweet spot — enough to
        // ride out a brief 503 burst on the Flash tier without compounding
        // the user's perceived latency past ~15s of silent waiting.
        private const val GENERATE_MAX_RETRIES = 3
        private const val GENERATE_RETRY_BACKOFF_MS = 2_000L
        private val TRANSIENT_5XX = setOf(500, 502, 503, 504)

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

        private fun logFinishReason(
            op: String,
            reason: String,
        ) {
            println("$LOG_TAG: $op finished with reason=$reason (response may be truncated)")
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
private data class Part(
    val text: String? = null,
    val fileData: FileData? = null,
)

/**
 * `fileData` part referencing a file already uploaded via the Files API.
 * `fileUri` is the `gs://`-equivalent URI Gemini returns from the upload, not
 * the original local path.
 */
@Serializable
private data class FileData(
    val mimeType: String,
    val fileUri: String,
)

/** Resumable upload start metadata: `{"file": {"display_name": "..."}}`. */
@Serializable
private data class StartUploadRequest(val file: StartUploadFile)

@Serializable
private data class StartUploadFile(
    @SerialName("display_name") val displayName: String,
)

/** Wraps an [UploadedFile] inside a `{"file": …}` envelope, as the upload PUT response does. */
@Serializable
private data class FileEnvelope(val file: UploadedFile)

@Serializable
private data class GenerationConfig(
    val maxOutputTokens: Int,
    val temperature: Double,
    val thinkingConfig: ThinkingConfig? = null,
    // Pinning the wire shape for entity extraction. Both fields are sent
    // together — schema without mimeType is silently ignored by Gemini, and
    // mimeType without schema gives free-form JSON that drifts run-to-run.
    val responseMimeType: String? = null,
    val responseSchema: Schema? = null,
)

/**
 * Gemini 2.5 Flash bills "thinking" (chain-of-thought) tokens against
 * `maxOutputTokens`. For a summary task we don't need reasoning — silence it,
 * otherwise long transcripts can burn the whole budget and surface as a
 * truncated 2-liner with no visible cause.
 *
 * `thinkingBudget = 0` disables thinking entirely. -1 means "dynamic" (default).
 */
@Serializable
private data class ThinkingConfig(val thinkingBudget: Int)

// --------------------------------------------------------------------------
// Response DTOs (only the fields we actually read — Gemini returns much more).
// --------------------------------------------------------------------------

@Serializable
private data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
)

@Serializable
private data class Candidate(
    val content: Content = Content(emptyList()),
    val finishReason: String? = null,
)
