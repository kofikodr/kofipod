// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
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
import kotlinx.coroutines.CancellationException
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
 * Seam over the multi-turn Discuss / Q&A call so [DiscussRepository] can be
 * unit-tested against a synchronous fake. Same rationale as [TextSummariser];
 * production [GeminiClient] is the only real implementation. Returns the parsed
 * [DiscussAnswerJson] — wire-shape mapping happens inside [GeminiClient] so
 * the repository never sees raw JSON.
 *
 * [context] is the source material — text or an uploaded audio reference —
 * sent as the first user turn (with a synthetic model acknowledgement so the
 * conversation alternates strictly user/model). [history] holds the prior real
 * turns; [question] is the new user turn.
 */
fun interface ChatSummariser {
    suspend fun chat(
        apiKey: String,
        model: GeminiModel,
        systemPrompt: String,
        context: ChatContext,
        history: List<DiscussTurn>,
        question: String,
    ): Result<DiscussAnswerJson>
}

/**
 * Seam over [GeminiClient.generateFromAudio] so [AiSummaryRepository] can be
 * unit-tested against a synchronous fake. Narrower than the pre-coordinator
 * [AudioSummariser] (which owned upload+poll+generate+delete in one call) —
 * [AudioUploadCoordinator] now owns the upload/poll lifecycle, and this seam
 * is just the structured-summary call against an already-active file URI.
 *
 * Production binding: a thin lambda over [GeminiClient.generateFromAudio].
 */
fun interface AudioSummariser {
    suspend fun summariseFromAudio(
        apiKey: String,
        model: GeminiModel,
        fileUri: String,
        mimeType: String,
        prompt: String,
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
 *
 * @param openRange seam over [openFileRange] used by the chunked resumable
 *   upload path. Production wires the real platform actual; tests inject a
 *   lambda over an in-memory `ByteArray` so they can assert chunk-by-chunk
 *   wire shape without a real file on disk.
 */
class GeminiClient(
    private val client: HttpClient,
    private val openRange: (path: String, offset: Long, length: Long) -> ByteReadChannel = ::openFileRange,
) : KeyValidator, TextSummariser, ChatSummariser {
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
                    timeout { requestTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS }
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
                    timeout { requestTimeoutMillis = GENERATE_REQUEST_TIMEOUT_MS }
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
        return decodeResponse<AiSummaryJson>(response, "generateFromText") { it.summary.isBlank() }
    }

    /**
     * Resumable chunked upload to the Files API. Wire protocol per Google's
     * resumable spec:
     *
     *  1. `POST /upload/v1beta/files?uploadType=resumable&key=…` with the metadata
     *     body — Gemini replies with the upload URL in the `X-Goog-Upload-URL`
     *     header. We never write that URL to a log; it's a single-use bearer.
     *  2. For each [chunkSize]-byte slice of the file: `PUT <uploadUrl>` with
     *     `X-Goog-Upload-Command: upload` (or `upload, finalize` on the last
     *     chunk) and `X-Goog-Upload-Offset: <bytes>`. The slice is re-read
     *     from [openRange] so a transient failure can resume from the
     *     server-confirmed offset without re-streaming earlier chunks.
     *  3. On a transport failure or transient HTTP error mid-chunk, send a
     *     `X-Goog-Upload-Command: query` PUT to read the server's
     *     `X-Goog-Upload-Size-Received` header — that's the resume offset.
     *
     * Up to [UPLOAD_MAX_RETRIES] attempts per chunk with exponential backoff;
     * a successful query that advances the offset resets the per-chunk
     * counter (real progress was made) so a flaky tunnel doesn't burn the
     * budget on chunks the server already has. There's no overall wall-clock
     * cap — the user's "Cancel" button is the right deadline.
     *
     * The returned [UploadedFile] is typically `state == "PROCESSING"` — call
     * [pollUntilActive] before passing the URI to [generateFromAudio].
     *
     * @param onProgress invoked after each accepted chunk (or after a query
     *   advances the offset) with the cumulative byte count the server has
     *   confirmed. Fires monotonically; receivers can plot it directly as
     *   `received / sizeBytes`.
     */
    suspend fun uploadAudio(
        apiKey: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
        onProgress: (uploadedBytes: Long) -> Unit = {},
        chunkSize: Int = UPLOAD_CHUNK_SIZE,
    ): Result<UploadedFile> {
        val startResponse: HttpResponse =
            runCatching {
                client.post("$BASE_URL/upload/v1beta/files") {
                    timeout { requestTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS }
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

        // Empty-file upload would hang the loop (offset never advances). Treat
        // it as the kind of upstream bug we can't recover from and surface a
        // clear failure rather than silently sending a finalize PUT with zero
        // bytes — Gemini would reject that anyway.
        if (sizeBytes <= 0L) {
            return Result.failure(AiErrorException(AiError.Unknown(null)))
        }

        var offset = 0L
        var attempt = 0
        var finalResponse: HttpResponse? = null
        while (offset < sizeBytes) {
            val chunkLen = minOf(chunkSize.toLong(), sizeBytes - offset)
            val isFinal = (offset + chunkLen) >= sizeBytes
            val command = if (isFinal) "upload, finalize" else "upload"
            val chunkOffset = offset

            // Request timeout is intentionally NOT set on the chunk PUT —
            // socket-inactivity (configured globally) is the right stall
            // detector for an upload that may take minutes on a slow link.
            val response: HttpResponse? =
                runCatching {
                    client.put(uploadUrl) {
                        headers {
                            append("X-Goog-Upload-Command", command)
                            append("X-Goog-Upload-Offset", chunkOffset.toString())
                        }
                        setBody(
                            object : OutgoingContent.WriteChannelContent() {
                                override val contentLength = chunkLen
                                override val contentType = ContentType.parse(mimeType)

                                override suspend fun writeTo(channel: ByteWriteChannel) {
                                    openRange(localPath, chunkOffset, chunkLen).copyAndClose(channel)
                                }
                            },
                        )
                    }
                }.onFailure {
                    // runCatching catches Throwable, including CancellationException —
                    // re-throw so a Cancel tap (or clearAll) propagates immediately
                    // instead of falling through into 16s of retry backoff.
                    if (it is CancellationException) throw it
                }.getOrElse { throwable ->
                    logTransportFailure("uploadAudio.chunk[$chunkOffset]", throwable)
                    null
                }

            if (response != null && response.status.isSuccess()) {
                offset += chunkLen
                attempt = 0
                onProgress(offset)
                if (isFinal) finalResponse = response
                continue
            }

            // Non-transient HTTP failure → fail fast (a 401 won't get better
            // by retrying; surface it so the user can fix the key).
            if (response != null && !response.status.isTransient()) {
                logHttpFailure("uploadAudio.chunk[$chunkOffset]", response.status.value)
                return Result.failure(AiErrorException(response.status.toAiError()))
            }
            if (response != null) {
                logHttpFailure("uploadAudio.chunk[$chunkOffset]", response.status.value)
            }

            attempt++
            if (attempt > UPLOAD_MAX_RETRIES) {
                return Result.failure(AiErrorException(AiError.Network))
            }
            delay(UPLOAD_RETRY_BACKOFF_MS shl (attempt - 1))

            // Ask the server how far it actually got — a chunk that failed
            // mid-stream may still have landed bytes. If the server reports
            // forward progress, jump to that offset and reset the per-chunk
            // budget (we made real progress, even if our PUT didn't finish).
            val queried = queryUploadOffset(uploadUrl)
            if (queried != null && queried > offset) {
                offset = queried
                attempt = 0
                onProgress(offset)
            }
        }

        val response =
            finalResponse
                ?: return Result.failure(AiErrorException(AiError.Unknown(null)))
        return runCatching { response.body<FileEnvelope>().file }
            .map { Result.success(it) }
            .getOrElse {
                logParseFailure("uploadAudio.finalize", it)
                Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
            }
    }

    /**
     * Issues a zero-body `query` PUT to learn the server's confirmed offset
     * for a resumable upload session. Returns null if the query itself fails
     * (transport or non-2xx) or the response is missing the size header — in
     * either case the caller falls back to retrying the chunk from the same
     * offset, which is safe (the server is idempotent on resumable PUTs).
     */
    private suspend fun queryUploadOffset(uploadUrl: String): Long? {
        val response: HttpResponse =
            runCatching {
                client.put(uploadUrl) {
                    timeout { requestTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS }
                    headers { append("X-Goog-Upload-Command", "query") }
                    setBody(EmptyBody)
                }
            }.onFailure {
                // Same cancellation-rethrow rule as the chunk PUT — see uploadAudio.
                if (it is CancellationException) throw it
            }.getOrElse {
                logTransportFailure("uploadAudio.query", it)
                return null
            }
        if (!response.status.isSuccess()) {
            logHttpFailure("uploadAudio.query", response.status.value)
            return null
        }
        return response.headers["X-Goog-Upload-Size-Received"]?.toLongOrNull()
    }

    /**
     * Whether [this] HTTP status is worth retrying on the upload path. Pulled
     * out so the upload loop and any future chunked-content surface stay
     * aligned. 408 Request Timeout joins the 5xx set because OkHttp / URLSession
     * can surface a per-chunk read stall as 408 from a stale upstream.
     */
    private fun HttpStatusCode.isTransient(): Boolean = value in TRANSIENT_5XX || value == 408

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
            val file = fetchFileState(apiKey, name).getOrElse { return Result.failure(it) }
            if (file.state == "ACTIVE") return Result.success(file)
            if (file.state == "FAILED") return Result.failure(AiErrorException(AiError.Unknown(null)))
            // Don't sleep after the final attempt — we're about to fall through
            // to the timeout failure anyway.
            if (attempt < maxAttempts - 1) delay(pollIntervalMs)
        }
        return Result.failure(AiErrorException(AiError.Unknown(null)))
    }

    /**
     * Single poll GET of `/v1beta/{name}` parsed into an [UploadedFile], with the
     * same transient-failure resilience the upload path has. Before this, one TCP
     * hiccup (or a transient 5xx) during the ≤5min polling window returned
     * [AiError.Network] outright — discarding a possibly multi-minute completed
     * upload, since [com.kofikodr.kofipod.ai.AudioUploadCoordinator] only caches the
     * Files API URI after a *successful* poll. So a blip forced the user to re-upload
     * the whole file and orphaned the PROCESSING file until the 48h TTL (issue #21).
     *
     * Transport exceptions and transient HTTP statuses (5xx / 408) are retried up to
     * [POLL_GET_MAX_ATTEMPTS] with short exponential backoff; a non-transient status
     * (e.g. 401/404) still fails fast — retrying won't fix a bad key or a deleted file.
     * [CancellationException] is re-thrown so a Cancel tap propagates immediately
     * rather than being misreported as a network error.
     */
    private suspend fun fetchFileState(
        apiKey: String,
        name: String,
    ): Result<UploadedFile> {
        repeat(POLL_GET_MAX_ATTEMPTS) { attempt ->
            val isLastAttempt = attempt == POLL_GET_MAX_ATTEMPTS - 1
            val response: HttpResponse =
                runCatching {
                    client.get("$BASE_URL/v1beta/$name") {
                        timeout { requestTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS }
                        url { parameters.append("key", apiKey) }
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                }.getOrElse {
                    logTransportFailure("pollUntilActive", it)
                    // Don't sleep after the final attempt; fall through to the post-loop
                    // return, which is the single canonical transport-exhausted exit.
                    if (!isLastAttempt) delay(POLL_GET_RETRY_BACKOFF_MS shl attempt)
                    return@repeat
                }
            if (!response.status.isSuccess()) {
                logHttpFailure("pollUntilActive", response.status.value)
                if (response.status.isTransient() && !isLastAttempt) {
                    delay(POLL_GET_RETRY_BACKOFF_MS shl attempt)
                    return@repeat
                }
                return Result.failure(AiErrorException(response.status.toAiError()))
            }
            val file =
                runCatching { response.body<UploadedFile>() }
                    .getOrElse {
                        logParseFailure("pollUntilActive", it)
                        return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
                    }
            return Result.success(file)
        }
        // Every attempt hit a transient failure without ever returning a usable body.
        return Result.failure(AiErrorException(AiError.Network))
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
                        timeout { requestTimeoutMillis = GENERATE_REQUEST_TIMEOUT_MS }
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
                return decodeResponse<AiSummaryJson>(response, "generateFromAudio") { it.summary.isBlank() }
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
     * Free-text transcription from an already-uploaded audio file. Same retry
     * pattern as [generateFromAudio], but uses [transcriptionGenerationConfig]
     * (no JSON schema) and decodes the response as plain text via
     * [extractCandidateText]. Used by Slice 4's snippet caption pipeline,
     * which needs a single transcript line — not a structured Summary JSON.
     */
    suspend fun transcribeFromAudio(
        apiKey: String,
        model: GeminiModel,
        fileUri: String,
        mimeType: String,
        prompt: String,
    ): Result<String> {
        var attempt = 0
        while (true) {
            val response: HttpResponse =
                runCatching {
                    client.post("$BASE_URL/v1beta/models/${model.apiId}:generateContent") {
                        timeout { requestTimeoutMillis = GENERATE_REQUEST_TIMEOUT_MS }
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
                                generationConfig = transcriptionGenerationConfig(),
                            ),
                        )
                    }
                }.getOrElse {
                    logTransportFailure("transcribeFromAudio", it)
                    return Result.failure(AiErrorException(AiError.Network))
                }

            if (response.status.isSuccess()) {
                return extractCandidateText(response, "transcribeFromAudio")
            }
            val transient = response.status.value in TRANSIENT_5XX
            if (transient && attempt < GENERATE_MAX_RETRIES) {
                logHttpFailure("transcribeFromAudio", response.status.value)
                delay(GENERATE_RETRY_BACKOFF_MS shl attempt)
                attempt++
                continue
            }
            val bodyText = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            logHttpFailure("transcribeFromAudio", response.status.value)
            return Result.failure(AiErrorException(response.status.toAudioAiError(bodyText)))
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
                timeout { requestTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS }
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
     * Free-text generation config used by [transcribeFromAudio]. No `responseSchema`
     * — the caller wants plain text, not a structured JSON document. Same
     * thinking-disabled posture as [summaryGenerationConfig] so token budget
     * lands on the visible response, not chain-of-thought.
     */
    private fun transcriptionGenerationConfig(): GenerationConfig =
        GenerationConfig(
            maxOutputTokens = TRANSCRIPTION_MAX_OUTPUT_TOKENS,
            temperature = TRANSCRIPTION_TEMPERATURE,
            thinkingConfig = ThinkingConfig(thinkingBudget = 0),
        )

    /**
     * Pulls the JSON document out of a successful `generateContent` response,
     * parses it as [T], and validates the result via [isBlankEnvelope] — a
     * model-emitted but empty answer is indistinguishable from a failed run
     * as far as the user cares, so callers pass a per-shape blank check.
     *
     * Inline + reified so the kotlinx-serialization codegen resolves the
     * right serializer at the call site. Two callers today (summary +
     * discuss); structured-output additions land here as `decodeResponse<X>`.
     */
    private suspend inline fun <reified T : Any> decodeResponse(
        response: HttpResponse,
        op: String,
        isBlankEnvelope: (T) -> Boolean,
    ): Result<T> {
        val text =
            extractCandidateText(response, op)
                .getOrElse { return Result.failure(it) }
        val structured =
            runCatching { aiJson.decodeFromString<T>(text) }
                .getOrElse {
                    logParseFailure(op, it)
                    return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
                }
        if (isBlankEnvelope(structured)) {
            return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
        }
        return Result.success(structured)
    }

    /**
     * Shared response-envelope unwrap. Treats three cases as failures:
     *  - parse failure on the outer GenerateContentResponse → [AiError.Unknown]
     *  - empty/blank candidate text → [AiError.Unknown]
     *  - non-`STOP` finish reason → still passes through but logged so a
     *    truncation report has a forensic breadcrumb.
     *
     * Splits Gemini's multi-part candidate body (one part per stream chunk)
     * back into a single string, trimmed.
     */
    private suspend fun extractCandidateText(
        response: HttpResponse,
        op: String,
    ): Result<String> {
        val parsed =
            runCatching { response.body<GenerateContentResponse>() }
                .getOrElse {
                    logParseFailure(op, it)
                    return Result.failure(AiErrorException(AiError.Unknown(response.status.value)))
                }
        val candidate = parsed.candidates.firstOrNull()
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
        return Result.success(text)
    }

    /**
     * Multi-turn Q&A call. Builds the `contents` array as
     *
     * ```
     *   [user(<context>), model(TRANSCRIPT_ACK), ...history, user(question)]
     * ```
     *
     * where `<context>` is either a text part (transcript) or a `fileData`
     * part referencing an already-uploaded Files API audio resource. The
     * system instruction lands on a separate top-level field. The schema
     * pins the response shape to [DiscussAnswerJson] so citations come back
     * structured rather than embedded in prose. Same status-code mapping as
     * the other surfaces — 400/401/403 → KeyInvalid, 429 → RateLimited,
     * transport → Network, anything else → Unknown.
     *
     * No retries on transient 5xx. A chat retry would burn input tokens for
     * the full context every time (audio re-processing on Gemini's side, or
     * a full transcript re-read). The user's Retry button is the right
     * re-entry point.
     */
    override suspend fun chat(
        apiKey: String,
        model: GeminiModel,
        systemPrompt: String,
        context: ChatContext,
        history: List<DiscussTurn>,
        question: String,
    ): Result<DiscussAnswerJson> {
        val firstUserParts: List<Part> =
            when (context) {
                is ChatContext.Transcript ->
                    listOf(Part(text = DiscussPrompts.transcriptTurn(context.text)))
                is ChatContext.Audio ->
                    listOf(
                        Part(fileData = FileData(mimeType = context.mimeType, fileUri = context.fileUri)),
                        Part(text = DiscussPrompts.AUDIO_CONTEXT_PREAMBLE),
                    )
            }
        val contents =
            buildList {
                add(Content(parts = firstUserParts, role = ROLE_USER))
                add(Content(parts = listOf(Part(text = DiscussPrompts.TRANSCRIPT_ACK)), role = ROLE_MODEL))
                history.forEach { turn ->
                    add(Content(parts = listOf(Part(text = turn.text)), role = turn.role.wire))
                }
                add(Content(parts = listOf(Part(text = question)), role = ROLE_USER))
            }
        val response: HttpResponse =
            runCatching {
                client.post("$BASE_URL/v1beta/models/${model.apiId}:generateContent") {
                    timeout { requestTimeoutMillis = GENERATE_REQUEST_TIMEOUT_MS }
                    contentType(ContentType.Application.Json)
                    url { parameters.append("key", apiKey) }
                    setBody(
                        GenerateContentRequest(
                            contents = contents,
                            generationConfig = chatGenerationConfig(),
                            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
                        ),
                    )
                }
            }.getOrElse {
                logTransportFailure("chat", it)
                return Result.failure(AiErrorException(AiError.Network))
            }

        if (!response.status.isSuccess()) {
            logHttpFailure("chat", response.status.value)
            return Result.failure(AiErrorException(response.status.toAiError()))
        }
        return decodeResponse<DiscussAnswerJson>(response, "chat") { it.answer.isBlank() }
    }

    /**
     * Generation config for the multi-turn chat path. Same thinking-disabled
     * + structured-JSON shape as [summaryGenerationConfig], but pinned to
     * [DISCUSS_RESPONSE_SCHEMA] and a smaller token cap because individual
     * answers are short and a runaway response is more annoying than a
     * truncated one in chat.
     */
    private fun chatGenerationConfig(): GenerationConfig =
        GenerationConfig(
            maxOutputTokens = CHAT_MAX_OUTPUT_TOKENS,
            temperature = CHAT_TEMPERATURE,
            thinkingConfig = ThinkingConfig(thinkingBudget = 0),
            responseMimeType = "application/json",
            responseSchema = DISCUSS_RESPONSE_SCHEMA,
        )

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

        // Chat answers stay short by spec (one paragraph by default), so a
        // 2048-token cap is comfortable headroom and a tighter ceiling against
        // a runaway turn — a chat reply that bleeds across the screen is more
        // jarring than a summary that does the same. Slightly lower temperature
        // than the summary path because Q&A wants grounded recall, not flair.
        private const val CHAT_MAX_OUTPUT_TOKENS = 2048
        private const val CHAT_TEMPERATURE = 0.3

        // Snippet caption transcription: we want a single short line of text, not
        // a structured JSON document — so the token cap is tight and the schema is
        // absent. Low temperature keeps the output grounded and deterministic.
        private const val TRANSCRIPTION_MAX_OUTPUT_TOKENS = 256
        private const val TRANSCRIPTION_TEMPERATURE = 0.2

        // Wire role strings expected by Gemini's multi-turn API. Pinned here
        // (not stringified at call sites) so the only sources of truth are
        // this file and DiscussRole.kt, and a typo would surface as a 400
        // from a single canonical location.
        private const val ROLE_USER = "user"
        private const val ROLE_MODEL = "model"

        private const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        private const val DEFAULT_POLL_TIMEOUT_MS = 5L * 60 * 1000

        // Per-poll-GET transient-failure retry budget (issue #21). Small and fast: a
        // blip during polling shouldn't discard a completed upload, but we also don't
        // want to stall the outer poll loop. Backoff is 500ms, then 1s (POLL_GET_RETRY_
        // BACKOFF_MS shl attempt), so a fully-failing GET costs ~1.5s before surfacing.
        private const val POLL_GET_MAX_ATTEMPTS = 3
        private const val POLL_GET_RETRY_BACKOFF_MS = 500L

        // Retry budget for transient 5xx on generateFromAudio. Backoff doubles
        // each attempt: 2s, 4s, 8s. Three retries is a sweet spot — enough to
        // ride out a brief 503 burst on the Flash tier without compounding
        // the user's perceived latency past ~15s of silent waiting.
        private const val GENERATE_MAX_RETRIES = 3
        private const val GENERATE_RETRY_BACKOFF_MS = 2_000L
        private val TRANSIENT_5XX = setOf(500, 502, 503, 504)

        // Per-call request-timeout overrides. The HTTP client itself has
        // request-timeout disabled and relies on socket-inactivity as the
        // stall detector — see AiHttpClient for the rationale. These values
        // re-introduce a wall-clock cap on the calls where the request body
        // is small and a hung response is the failure mode (inference,
        // metadata round-trips). The chunked upload PUT deliberately gets NO
        // request timeout — it can take minutes on a slow link.
        //
        // 5 minutes covers the longest observed Flash inference on a
        // transcript-length input by a comfortable margin without leaving
        // the UI hung indefinitely on a wedged connection.
        private const val GENERATE_REQUEST_TIMEOUT_MS = 5L * 60 * 1000

        // Short, fixed-cost calls. 30s is generous against transient
        // upstream slowness without making an obviously-stuck call cost
        // the user minutes of waiting.
        private const val METADATA_REQUEST_TIMEOUT_MS = 30_000L

        // Resumable upload chunk size. Multiple of 256KB (Google's documented
        // requirement) and the published sweet spot for throughput vs resume
        // granularity. A 100 MB episode lands in 13 chunks; a 300 MB one in
        // 38. A failed chunk only wastes that many bytes on retry, not the
        // whole upload.
        private const val UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024

        // Per-chunk retry budget. 5 attempts with exponential backoff (1s,
        // 2s, 4s, 8s, 16s) gives ~31s of total wait against a flaky link
        // before we surface AiError.Network — and a successful query that
        // advances the offset resets the counter, so a tunnel that's
        // genuinely making progress doesn't drain the budget.
        private const val UPLOAD_MAX_RETRIES = 5
        private const val UPLOAD_RETRY_BACKOFF_MS = 1_000L

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
    // Optional system-instruction channel — Gemini honours it when set, ignores
    // when omitted. Single-shot summary calls leave it null; the multi-turn
    // chat path uses it to carry the persona + citation rules so they don't
    // have to be re-sent inside every user turn.
    val systemInstruction: Content? = null,
)

/**
 * One slot of a `contents` array. `role` is required by Gemini for multi-turn
 * (`"user"` or `"model"`); single-shot calls omit it and Gemini infers `"user"`.
 * Kept optional so existing summary callers don't need to set it.
 */
@Serializable
private data class Content(
    val parts: List<Part>,
    val role: String? = null,
)

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

/**
 * Zero-byte body for the resumable-upload `query` PUT. Ktor's default body
 * derivation would surface `null` here as a `NullBody` and produce a request
 * without a `Content-Length` header — Google's resumable endpoint requires
 * `Content-Length: 0` on a query, so we send an explicit empty content.
 */
private object EmptyBody : OutgoingContent.NoContent() {
    override val contentLength: Long = 0L
}

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
