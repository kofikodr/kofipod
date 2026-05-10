// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [GeminiClient]'s Files API + audio `generateContent` request shapes:
 *
 *  - [GeminiClient.uploadAudio]: two-step resumable upload — start request must
 *    carry the `X-Goog-Upload-*` headers + JSON metadata; finalize must PUT to
 *    the URL captured from the start response and parse the wrapped file
 *    envelope.
 *  - [GeminiClient.pollUntilActive]: GETs `/v1beta/<name>?key=…`, retries while
 *    state == PROCESSING, fails on FAILED, times out cleanly.
 *  - [GeminiClient.generateFromAudio]: body marshals as `[fileData, text]` parts
 *    in that order — flipping the order silently degrades summaries the same way
 *    the text path does.
 *  - Audio-specific 400 mapping: `INVALID_ARGUMENT` + "exceeds the maximum"
 *    surfaces as [AiError.AudioTooLong] rather than the generic KeyInvalid.
 *
 * No live network. Mirrors [GeminiClientTextTest]; happy-path streaming over a
 * real file is intentionally out of scope per the slice plan.
 */
class GeminiClientAudioTest {
    // ---------------------------------------------------------------------
    // uploadAudio
    // ---------------------------------------------------------------------

    @Test
    fun uploadAudio_startRequest_carriesResumableHeadersAndMetadataBody() =
        runTest {
            val (start, _) = newUploadFlow(payloadSize = 3)
            buildClient(start.client, payload = byteArrayOf(1, 2, 3)).uploadAudio(
                apiKey = "k",
                localPath = TEST_PATH,
                mimeType = "audio/mpeg",
                sizeBytes = 3,
                displayName = "ep-204.mp3",
            )

            val request = assertNotNull(start.observed[0], "Start request was not captured")
            assertEquals("resumable", request.url.parameters["uploadType"])
            assertEquals("k", request.url.parameters["key"])
            assertEquals("resumable", request.headers["X-Goog-Upload-Protocol"])
            assertEquals("start", request.headers["X-Goog-Upload-Command"])
            assertEquals("3", request.headers["X-Goog-Upload-Header-Content-Length"])
            assertEquals("audio/mpeg", request.headers["X-Goog-Upload-Header-Content-Type"])
            val body = (request.body as TextContent).text
            val displayName =
                Json.parseToJsonElement(body)
                    .jsonObject["file"]!!.jsonObject["display_name"]!!.jsonPrimitive.content
            assertEquals("ep-204.mp3", displayName)
        }

    @Test
    fun uploadAudio_singleChunk_putsToCapturedUrl_andParsesWrappedFileEnvelope() =
        runTest {
            // 3 bytes is well under the 8MB chunk size, so the upload finishes
            // in one PUT carrying `upload, finalize` at offset 0. Pins the
            // small-file fast path: the finalize PUT inherits the start
            // response's X-Goog-Upload-URL and the wrapped envelope decodes
            // into the typed UploadedFile.
            val (start, finalize) = newUploadFlow(payloadSize = 3)
            val result =
                buildClient(start.client, payload = byteArrayOf(1, 2, 3)).uploadAudio(
                    apiKey = "k",
                    localPath = TEST_PATH,
                    mimeType = "audio/mpeg",
                    sizeBytes = 3,
                    displayName = "ep-204.mp3",
                )

            assertEquals(1, finalize.observed.size, "3-byte payload must finalise in a single PUT")
            val finalizeRequest = assertNotNull(finalize.observed[0])
            assertEquals(HttpMethod.Put, finalizeRequest.method)
            assertEquals(UPLOAD_URL, finalizeRequest.url.toString())
            assertEquals("upload, finalize", finalizeRequest.headers["X-Goog-Upload-Command"])
            assertEquals("0", finalizeRequest.headers["X-Goog-Upload-Offset"])

            val file = result.getOrNull()
            assertNotNull(file)
            assertEquals("files/abc", file.name)
            assertEquals("https://example.com/files/abc", file.uri)
            assertEquals("PROCESSING", file.state)
        }

    @Test
    fun uploadAudio_multiChunk_putsAtAdvancingOffsets_andFiresProgressMonotonically() =
        runTest {
            // 20 bytes payload at chunkSize=8 → three PUTs at offsets 0, 8, 16.
            // The first two carry the bare `upload` command; the third carries
            // `upload, finalize`. The progress callback fires once per chunk
            // with the cumulative offset and never goes backwards.
            val payload = ByteArray(20) { it.toByte() }
            val (start, finalize) = newUploadFlow(payloadSize = 20)
            val received = mutableListOf<Long>()
            val rangeCalls = mutableListOf<Pair<Long, Long>>()
            val result =
                buildClient(start.client, payload = payload, rangeCalls = rangeCalls).uploadAudio(
                    apiKey = "k",
                    localPath = TEST_PATH,
                    mimeType = "audio/mpeg",
                    sizeBytes = 20,
                    displayName = "ep.mp3",
                    onProgress = { received += it },
                    chunkSize = 8,
                )

            assertEquals(3, finalize.observed.size, "20-byte payload at 8-byte chunks must take 3 PUTs")
            assertEquals(listOf("0", "8", "16"), finalize.observed.map { it.headers["X-Goog-Upload-Offset"] })
            assertEquals(listOf("upload", "upload", "upload, finalize"), finalize.observed.map { it.headers["X-Goog-Upload-Command"] })
            assertEquals(listOf(8L, 16L, 20L), received, "Progress must fire once per accepted chunk with cumulative bytes")
            // Body content pin: each chunk PUT must re-read its own slice of
            // the payload, not the same first 8 bytes three times. Pinning
            // the openRange call list catches a regression where the loop
            // forgets to advance the offset in `openRange(...)` while still
            // advancing it in the X-Goog-Upload-Offset header.
            assertEquals(
                listOf(0L to 8L, 8L to 8L, 16L to 4L),
                rangeCalls,
                "Each chunk must source bytes from its own offset and length",
            )
            assertEquals("files/abc", result.getOrNull()?.name)
        }

    @Test
    fun uploadAudio_chunkFailure_queriesServer_andResumesFromConfirmedOffset() =
        runTest {
            // Simulates a real flaky-network case: the first chunk PUT fails
            // mid-stream (treated as a transient 500 here), the client issues
            // a `query` PUT, the server reports it received some bytes, and
            // the next chunk PUT resumes from that offset rather than from 0.
            // Without the resume the upload would re-stream every chunk on
            // every disconnect — exactly the regression this slice fixes.
            val payload = ByteArray(16) { it.toByte() }
            val observed = mutableListOf<HttpRequestData>()
            var failedFirstChunk = false
            val client =
                HttpClient(
                    MockEngine { request ->
                        observed += request
                        val url = request.url.toString()
                        when {
                            url.startsWith(BASE_URL) -> {
                                respond(
                                    "{}",
                                    HttpStatusCode.OK,
                                    headersOf(
                                        HttpHeaders.ContentType to listOf("application/json"),
                                        "X-Goog-Upload-URL" to listOf(UPLOAD_URL),
                                    ),
                                )
                            }
                            request.headers["X-Goog-Upload-Command"] == "query" -> {
                                // Server says "I have 4 bytes already".
                                respond(
                                    "{}",
                                    HttpStatusCode.OK,
                                    headersOf("X-Goog-Upload-Size-Received" to listOf("4")),
                                )
                            }
                            request.headers["X-Goog-Upload-Command"] == "upload" -> {
                                drainBody(request.body)
                                if (!failedFirstChunk) {
                                    failedFirstChunk = true
                                    respondError(HttpStatusCode.InternalServerError)
                                } else {
                                    respond(
                                        "{}",
                                        HttpStatusCode.OK,
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                }
                            }
                            else -> {
                                drainBody(request.body)
                                // Final chunk carries `upload, finalize` and
                                // returns the file envelope.
                                respond(
                                    """
                                    {"file":{
                                      "name":"files/abc","uri":"https://example.com/files/abc",
                                      "mimeType":"audio/mpeg","sizeBytes":"16","state":"PROCESSING"
                                    }}
                                    """.trimIndent(),
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        }
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val received = mutableListOf<Long>()
            val rangeCalls = mutableListOf<Pair<Long, Long>>()
            val result =
                buildClient(client, payload = payload, rangeCalls = rangeCalls).uploadAudio(
                    apiKey = "k",
                    localPath = TEST_PATH,
                    mimeType = "audio/mpeg",
                    sizeBytes = 16,
                    displayName = "ep.mp3",
                    onProgress = { received += it },
                    chunkSize = 8,
                )

            assertTrue(result.isSuccess, "Resume must complete the upload")
            // After the failed chunk: a query, then the next PUT lands at
            // offset 4 (not 0 — that's the resume) — proving the offset
            // header was driven by the server's confirmation, not the
            // client's wishful retry.
            val resumePut =
                observed.firstOrNull { it.headers["X-Goog-Upload-Offset"] == "4" }
            assertNotNull(resumePut, "Resume PUT at offset 4 must be issued after the query")
            // Progress must reflect the server-confirmed jump (4) before
            // continuing — proves the UI bar would advance, not snap back.
            assertTrue(
                received.contains(4L),
                "Progress must surface the queried offset so the UI bar moves forward, not back to 0",
            )
            // Body content pin: after the resume, the client must re-open the
            // payload AT offset 4 — not at 0 again. Without this assertion,
            // a regression that advanced the X-Goog-Upload-Offset header but
            // re-streamed bytes 0..7 in the body would silently send garbage
            // (Gemini would reject it as a checksum mismatch in production,
            // but the unit test wouldn't catch it). The first attempt opens
            // (0, 8); after the query reports server-side offset 4, the
            // resumed attempt must open (4, 12) to send bytes 4..15.
            assertTrue(
                rangeCalls.any { it.first == 4L },
                "Resumed PUT must re-read the payload from offset 4, not 0 — saw $rangeCalls",
            )
        }

    @Test
    fun uploadAudio_chunkRetryBudget_surfacesNetworkError_afterRepeatedFailures() =
        runTest {
            // Every chunk PUT errors transiently and the query never reports
            // forward progress, so the per-chunk retry budget burns out and
            // the upload surfaces AiError.Network rather than looping forever.
            // Pins the upper bound on a persistently-broken connection.
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.toString().startsWith(BASE_URL) ->
                                respond(
                                    "{}",
                                    HttpStatusCode.OK,
                                    headersOf(
                                        HttpHeaders.ContentType to listOf("application/json"),
                                        "X-Goog-Upload-URL" to listOf(UPLOAD_URL),
                                    ),
                                )
                            request.headers["X-Goog-Upload-Command"] == "query" ->
                                respond(
                                    "{}",
                                    HttpStatusCode.OK,
                                    headersOf("X-Goog-Upload-Size-Received" to listOf("0")),
                                )
                            else -> respondError(HttpStatusCode.InternalServerError)
                        }
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                buildClient(client, payload = ByteArray(8) { 0 }).uploadAudio(
                    apiKey = "k",
                    localPath = TEST_PATH,
                    mimeType = "audio/mpeg",
                    sizeBytes = 8,
                    displayName = "ep.mp3",
                    chunkSize = 8,
                )

            assertEquals(AiError.Network, (result.exceptionOrNull() as? AiErrorException)?.error)
        }

    @Test
    fun uploadAudio_cancellation_propagatesImmediately_withoutBurningRetryBackoff() =
        runTest {
            // The chunk PUT and the query PUT both wrap the network call in
            // runCatching, which catches Throwable — including
            // CancellationException. Without an explicit re-throw, a Cancel
            // tap (or clearAll) mid-upload would silently fall through into
            // the per-chunk retry loop and burn up to ~31s of backoff
            // (1+2+4+8+16) before delay() finally re-checks cancellation.
            // Pin the immediate-propagation contract so a regression that
            // drops the re-throw is caught.
            val parked = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.toString().startsWith(BASE_URL)) {
                            respond(
                                "{}",
                                HttpStatusCode.OK,
                                headersOf(
                                    HttpHeaders.ContentType to listOf("application/json"),
                                    "X-Goog-Upload-URL" to listOf(UPLOAD_URL),
                                ),
                            )
                        } else {
                            // Park the chunk PUT indefinitely so the only way
                            // out of uploadAudio is cancellation.
                            parked.await()
                            error("unreachable — parked indefinitely")
                        }
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val started = CompletableDeferred<Unit>()
            val job =
                async {
                    started.complete(Unit)
                    buildClient(client, payload = ByteArray(8) { 0 }).uploadAudio(
                        apiKey = "k",
                        localPath = TEST_PATH,
                        mimeType = "audio/mpeg",
                        sizeBytes = 8,
                        displayName = "ep.mp3",
                        chunkSize = 8,
                    )
                }
            // Wait until the upload is genuinely parked inside the chunk PUT.
            started.await()
            testScheduler.runCurrent()

            val cancelAtVirtualMs = testScheduler.currentTime
            job.cancel()
            job.join()
            val elapsed = testScheduler.currentTime - cancelAtVirtualMs

            // Cancel must propagate before the per-chunk backoff would have
            // fired (1s = 1_000ms minimum). 100ms is generous slack — the
            // virtual scheduler advances only as the cancellation unwinds.
            assertTrue(
                elapsed < 1_000,
                "Cancel must propagate without waiting for retry backoff — virtual elapsed ${elapsed}ms",
            )
        }

    @Test
    fun uploadAudio_returnsUnknown_whenStartResponseLacksUploadUrl() =
        runTest {
            // The start response is required to surface X-Goog-Upload-URL. If
            // Gemini omits it (e.g. a future API change) we must NOT fall back
            // to a guessed URL — surface as Unknown so the user retries
            // instead of hammering some other endpoint.
            val client =
                HttpClient(
                    MockEngine { _ ->
                        // 200 OK but no X-Goog-Upload-URL header.
                        respond(
                            "{}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                buildClient(client, payload = byteArrayOf(1, 2, 3)).uploadAudio(
                    apiKey = "k",
                    localPath = TEST_PATH,
                    mimeType = "audio/mpeg",
                    sizeBytes = 3,
                    displayName = "ep.mp3",
                )

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            assertIs<AiError.Unknown>(error)
        }

    @Test
    fun uploadAudio_mapsKeyInvalid_when401OnStart() =
        runTest {
            val client =
                HttpClient(MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }) {
                    install(ContentNegotiation) { json(Json) }
                }

            val result =
                buildClient(client, payload = byteArrayOf(1)).uploadAudio(
                    apiKey = "k",
                    localPath = TEST_PATH,
                    mimeType = "audio/mpeg",
                    sizeBytes = 1,
                    displayName = "ep.mp3",
                )

            assertEquals(AiError.KeyInvalid, (result.exceptionOrNull() as? AiErrorException)?.error)
        }

    @Test
    fun uploadAudio_mapsKeyInvalid_whenChunkPutReturns401_nonTransient() =
        runTest {
            // A 401 mid-upload means the key was revoked between start and
            // chunk PUT (or the start response gave us a session URL bound
            // to a key the server has since rotated). Either way it's NOT
            // transient — retrying would burn the chunk budget on something
            // that won't recover. Must surface as KeyInvalid immediately,
            // not as the generic Network error from the retry-exhausted
            // path.
            var seenStart = false
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (!seenStart && request.url.toString().startsWith(BASE_URL)) {
                            seenStart = true
                            respond(
                                "{}",
                                HttpStatusCode.OK,
                                headersOf(
                                    HttpHeaders.ContentType to listOf("application/json"),
                                    "X-Goog-Upload-URL" to listOf(UPLOAD_URL),
                                ),
                            )
                        } else {
                            respondError(HttpStatusCode.Unauthorized)
                        }
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                buildClient(client, payload = byteArrayOf(1)).uploadAudio(
                    apiKey = "k",
                    localPath = TEST_PATH,
                    mimeType = "audio/mpeg",
                    sizeBytes = 1,
                    displayName = "ep.mp3",
                    chunkSize = 8,
                )

            assertEquals(
                AiError.KeyInvalid,
                (result.exceptionOrNull() as? AiErrorException)?.error,
                "A 401 on a chunk PUT must surface immediately, not loop through the retry budget",
            )
        }

    // ---------------------------------------------------------------------
    // pollUntilActive
    // ---------------------------------------------------------------------

    @Test
    fun pollUntilActive_returnsActive_onFirstCheck_whenAlreadyActive() =
        runTest {
            // Hot path: short clips often come back ACTIVE before we even poll.
            // We must check immediately and short-circuit, otherwise every audio
            // summary pays an unnecessary [pollIntervalMs] wait.
            val urls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        urls += request.url.toString()
                        respond(
                            """{"name":"files/abc","uri":"u","mimeType":"audio/mpeg","state":"ACTIVE"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result = GeminiClient(client).pollUntilActive(apiKey = "k", name = "files/abc")

            assertEquals("ACTIVE", result.getOrNull()?.state)
            assertEquals(1, urls.size, "Should not poll again once ACTIVE is observed")
            assertTrue("v1beta/files/abc" in urls[0])
            assertTrue("key=k" in urls[0])
        }

    @Test
    fun pollUntilActive_pollsRepeatedlyUntilActive() =
        runTest {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine { _ ->
                        calls += 1
                        val state = if (calls < 3) "PROCESSING" else "ACTIVE"
                        respond(
                            """{"name":"files/abc","uri":"u","mimeType":"audio/mpeg","state":"$state"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                GeminiClient(client).pollUntilActive(
                    apiKey = "k",
                    name = "files/abc",
                    pollIntervalMs = 10,
                    pollTimeoutMs = 1000,
                )

            assertEquals("ACTIVE", result.getOrNull()?.state)
            assertEquals(3, calls, "Polling must continue while state == PROCESSING")
        }

    @Test
    fun pollUntilActive_failsImmediately_onFailedState() =
        runTest {
            // FAILED is terminal — Gemini won't move it back to ACTIVE, so
            // continuing to poll just wastes the user's time + quota. Surface
            // Unknown so the UI shows the retry card.
            val client =
                HttpClient(
                    MockEngine { _ ->
                        respond(
                            """{"name":"files/abc","uri":"u","mimeType":"audio/mpeg","state":"FAILED"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result = GeminiClient(client).pollUntilActive("k", "files/abc", pollIntervalMs = 10, pollTimeoutMs = 1000)

            assertIs<AiError.Unknown>((result.exceptionOrNull() as? AiErrorException)?.error)
        }

    @Test
    fun pollUntilActive_timesOut_whenStateNeverFlipsToActive() =
        runTest {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine { _ ->
                        calls += 1
                        respond(
                            """{"name":"files/abc","uri":"u","mimeType":"audio/mpeg","state":"PROCESSING"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                GeminiClient(client).pollUntilActive(
                    apiKey = "k",
                    name = "files/abc",
                    pollIntervalMs = 10,
                    pollTimeoutMs = 30,
                )

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            assertIs<AiError.Unknown>(error)
            assertNull(error.statusCode, "Timeout has no HTTP status to report")
            // Bound rather than pin: the contract is "polls at least once and
            // gives up within the budget". An exact count would couple to the
            // private floor-division arithmetic and break on a benign refactor
            // (e.g. swapping to a deadline-based `withTimeout`) without any
            // user-visible regression.
            assertTrue(
                calls in 1..5,
                "Polling must terminate within a sane bound — saw $calls attempts",
            )
        }

    // ---------------------------------------------------------------------
    // generateFromAudio
    // ---------------------------------------------------------------------

    @Test
    fun generateFromAudio_sendsFileDataThenText_asTwoParts_inOrder() =
        runTest {
            // Order is load-bearing: putting the audio reference before the
            // prompt mirrors how Google's docs structure multimodal requests
            // and matches their best-practice guidance for instruction
            // following on file inputs.
            var observed: HttpRequestData? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        observed = request
                        respond(
                            """{"candidates":[{"content":{"parts":[{"text":"{\"summary\":\"summary\"}"}]}}]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                GeminiClient(client).generateFromAudio(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    fileUri = "https://example.com/files/abc",
                    mimeType = "audio/mpeg",
                    prompt = "PROMPT_TEXT",
                )

            assertEquals("summary", result.getOrNull()?.summary)

            val request = assertNotNull(observed)
            val body = (request.body as TextContent).text
            val parts =
                Json.parseToJsonElement(body)
                    .jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            assertEquals(2, parts.size)

            val fileData = parts[0].jsonObject["fileData"]!!.jsonObject
            assertEquals("audio/mpeg", fileData["mimeType"]!!.jsonPrimitive.content)
            assertEquals("https://example.com/files/abc", fileData["fileUri"]!!.jsonPrimitive.content)

            assertEquals("PROMPT_TEXT", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        }

    @Test
    fun generateFromAudio_disablesThinkingBudget_inRequestBody() =
        runTest {
            // Same regression as the text path: long-audio summaries can burn
            // the entire output budget on chain-of-thought tokens, surfacing
            // as a 2-liner cut mid-sentence. Audio is even more vulnerable
            // because the prompt asks for a full summary of the whole episode.
            var observed: HttpRequestData? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        observed = request
                        respond(
                            """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            GeminiClient(client).generateFromAudio(
                apiKey = "k",
                model = GeminiModel.Flash,
                fileUri = "u",
                mimeType = "audio/mpeg",
                prompt = "P",
            )

            val body = (observed!!.body as TextContent).text
            val thinkingBudget =
                Json.parseToJsonElement(body)
                    .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!
                    .jsonObject["thinkingBudget"]!!.jsonPrimitive.content
            assertEquals("0", thinkingBudget)
        }

    @Test
    fun generateFromAudio_mapsAudioTooLong_when400CarriesExceedsMaxMessage() =
        runTest {
            // Gemini's "this audio is past my context window" surfaces as 400
            // INVALID_ARGUMENT with a message naming a token / size cap. We
            // map that to AudioTooLong so the UI shows the dedicated copy
            // variant ("8h+ episodes can't be summarised yet") rather than
            // the generic key-invalid card.
            val client =
                HttpClient(
                    MockEngine { _ ->
                        respond(
                            """
                            {
                              "error": {
                                "code": 400,
                                "message": "Request exceeds the maximum number of tokens allowed.",
                                "status": "INVALID_ARGUMENT"
                              }
                            }
                            """.trimIndent(),
                            HttpStatusCode.BadRequest,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                GeminiClient(client).generateFromAudio(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    fileUri = "u",
                    mimeType = "audio/mpeg",
                    prompt = "P",
                )

            assertEquals(AiError.AudioTooLong, (result.exceptionOrNull() as? AiErrorException)?.error)
        }

    @Test
    fun generateFromAudio_mapsKeyInvalid_when400MessageMentionsTokenButNotLength() =
        runTest {
            // Regression guard: an earlier heuristic mapped any 400 +
            // INVALID_ARGUMENT message containing the substring "token" to
            // AudioTooLong. That misclassified bad-key errors phrased as
            // "invalid authentication token" — the user would see "this
            // episode is too long" with no retry button when their real
            // problem was a rejected key. The narrowed heuristic only
            // matches on "exceeds the maximum"; this test pins that.
            val client =
                HttpClient(
                    MockEngine { _ ->
                        respond(
                            """
                            {
                              "error": {
                                "code": 400,
                                "message": "Request had invalid authentication token. Please check your API key.",
                                "status": "INVALID_ARGUMENT"
                              }
                            }
                            """.trimIndent(),
                            HttpStatusCode.BadRequest,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                GeminiClient(client).generateFromAudio(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    fileUri = "u",
                    mimeType = "audio/mpeg",
                    prompt = "P",
                )

            assertEquals(
                AiError.KeyInvalid,
                (result.exceptionOrNull() as? AiErrorException)?.error,
                "A 400 mentioning \"token\" in an auth context must NOT mis-fire as AudioTooLong",
            )
        }

    @Test
    fun generateFromAudio_mapsKeyInvalid_when400IsNotAboutLength() =
        runTest {
            // A genuine bad-key 400 (no INVALID_ARGUMENT-with-token-message
            // signal) must still land as KeyInvalid, otherwise audio-only
            // users would see "audio too long" copy when their key is the
            // actual problem.
            val client =
                HttpClient(
                    MockEngine { _ ->
                        respond(
                            """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}""",
                            HttpStatusCode.BadRequest,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result =
                GeminiClient(client).generateFromAudio(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    fileUri = "u",
                    mimeType = "audio/mpeg",
                    prompt = "P",
                )

            assertEquals(AiError.KeyInvalid, (result.exceptionOrNull() as? AiErrorException)?.error)
        }

    @Test
    fun generateFromAudio_mapsRateLimited_on429() =
        runTest {
            val client =
                HttpClient(MockEngine { _ -> respondError(HttpStatusCode.TooManyRequests) }) {
                    install(ContentNegotiation) { json(Json) }
                }
            val result =
                GeminiClient(client).generateFromAudio("k", GeminiModel.Flash, "u", "audio/mpeg", "P")
            assertEquals(AiError.RateLimited, (result.exceptionOrNull() as? AiErrorException)?.error)
        }

    // ---------------------------------------------------------------------
    // deleteFile
    // ---------------------------------------------------------------------

    @Test
    fun deleteFile_issuesDelete_andSucceedsEvenWhenServerErrors() =
        runTest {
            // Delete is best-effort — Gemini auto-purges Files API uploads
            // after 48h regardless. A 500 here must NOT bubble up to the
            // user; the summary already landed in the DB and the temp
            // upload is the server's problem.
            var captured: HttpRequestData? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        captured = request
                        respondError(HttpStatusCode.InternalServerError)
                    },
                ) { install(ContentNegotiation) { json(Json) } }

            val result = GeminiClient(client).deleteFile(apiKey = "k", name = "files/abc")

            assertTrue(result.isSuccess)
            val req = assertNotNull(captured)
            assertEquals(HttpMethod.Delete, req.method)
            assertTrue("v1beta/files/abc" in req.url.toString())
            assertEquals("k", req.url.parameters["key"])
        }

    // ---------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------

    /**
     * Spins up a happy-path two-stage upload flow with a MockEngine that
     * routes the start POST and every chunk PUT separately. Each PUT
     * accepts the body unconditionally; the final chunk also returns the
     * `{"file": …}` envelope (matching real Gemini behaviour where the
     * envelope only lands on the `upload, finalize` PUT). [payloadSize]
     * is recorded into the envelope's `sizeBytes` so multi-chunk tests
     * see a coherent response, even though the client only reads
     * `name`/`uri`/`mimeType`/`state` from it.
     */
    private fun newUploadFlow(payloadSize: Int): Pair<RecordingStub, RecordingStub> {
        val start = RecordingStub()
        val finalize = RecordingStub()
        val handler: MockRequestHandler = { request ->
            val url = request.url.toString()
            if (url.startsWith(UPLOAD_URL)) {
                drainBody(request.body)
                finalize.observed += request
                respond(
                    """
                    {"file":{
                      "name":"files/abc","uri":"https://example.com/files/abc",
                      "mimeType":"audio/mpeg","sizeBytes":"$payloadSize","state":"PROCESSING"
                    }}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                start.observed += request
                respond(
                    "{}",
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        "X-Goog-Upload-URL" to listOf(UPLOAD_URL),
                    ),
                )
            }
        }
        val client =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(Json) }
            }
        start.client = client
        finalize.client = client
        return start to finalize
    }

    /**
     * Builds a [GeminiClient] wired with an in-memory chunk opener so the
     * chunked-upload code path can exercise its byte-range reads against
     * an honest payload without writing a temp file. The opener slices
     * [payload] using the offset + length the client requests, mirroring
     * what `openFileRange` would produce on disk. [rangeCalls], when
     * non-null, records every `(offset, length)` the client asked for —
     * tests use this to pin that the body of a resumed PUT was actually
     * re-read from the resumed offset, not just the offset header.
     */
    private fun buildClient(
        client: HttpClient,
        payload: ByteArray,
        rangeCalls: MutableList<Pair<Long, Long>>? = null,
    ): GeminiClient =
        GeminiClient(
            client = client,
            openRange = { _, offset, length ->
                rangeCalls?.add(offset to length)
                val from = offset.toInt().coerceAtLeast(0).coerceAtMost(payload.size)
                val to = (from + length.toInt()).coerceAtMost(payload.size)
                ByteReadChannel(payload.copyOfRange(from, to))
            },
        )

    private class RecordingStub {
        lateinit var client: HttpClient
        val observed: MutableList<HttpRequestData> = mutableListOf()
    }

    /**
     * Forces a request body's `writeTo(channel)` to execute so production
     * code paths that emit side effects from inside `writeTo` (e.g. our
     * `openRange(path, offset, length)` call) are observable in unit tests.
     * Ktor's MockEngine handler doesn't drain the body on its own — it just
     * stashes the [OutgoingContent] and returns the synthetic response, so
     * without this drain `writeTo` would never run and any assertions on
     * what the body actually sourced from disk would silently pass.
     */
    private suspend fun drainBody(content: OutgoingContent) {
        // Bind the smart-cast to a local val so the closure inside `launch`
        // sees the narrowed type — Kotlin doesn't carry smart casts across
        // coroutine boundaries.
        val writable = content as? OutgoingContent.WriteChannelContent ?: return
        coroutineScope {
            val sink = ByteChannel(autoFlush = true)
            launch {
                try {
                    writable.writeTo(sink)
                } finally {
                    sink.flushAndClose()
                }
            }
            // Drain concurrently so writeTo doesn't block on a full sink.
            // We don't care about the bytes — the side effect we want is
            // openRange being called inside writeTo.
            sink.toByteArray()
        }
    }

    private companion object {
        const val UPLOAD_URL = "https://upload.example.com/upload/abc-token"
        const val TEST_PATH = "/tmp/test-audio.mp3"
        const val BASE_URL = "https://generativelanguage.googleapis.com"
    }
}
