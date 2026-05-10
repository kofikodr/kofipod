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
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
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
            val (start, _) = newUploadFlow()
            GeminiClient(start.client).uploadAudio(
                apiKey = "k",
                fileChannel = ByteReadChannel(byteArrayOf(1, 2, 3)),
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
    fun uploadAudio_finalizeRequest_putsToCapturedUrl_andParsesWrappedFileEnvelope() =
        runTest {
            val (start, finalize) = newUploadFlow()
            val result =
                GeminiClient(start.client).uploadAudio(
                    apiKey = "k",
                    fileChannel = ByteReadChannel(byteArrayOf(1, 2, 3)),
                    mimeType = "audio/mpeg",
                    sizeBytes = 3,
                    displayName = "ep-204.mp3",
                )

            val finalizeRequest = assertNotNull(finalize.observed[0], "Finalize request was not captured")
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
                GeminiClient(client).uploadAudio(
                    apiKey = "k",
                    fileChannel = ByteReadChannel(byteArrayOf(1, 2, 3)),
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
                GeminiClient(client).uploadAudio(
                    apiKey = "k",
                    fileChannel = ByteReadChannel(byteArrayOf(1)),
                    mimeType = "audio/mpeg",
                    sizeBytes = 1,
                    displayName = "ep.mp3",
                )

            assertEquals(AiError.KeyInvalid, (result.exceptionOrNull() as? AiErrorException)?.error)
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
     * Spins up the two-stage upload flow and returns the start + finalize stub
     * recorders so individual assertions stay focused.
     */
    private fun newUploadFlow(): Pair<RecordingStub, RecordingStub> {
        val start = RecordingStub()
        val finalize = RecordingStub()
        // MockEngine delegates by URL: requests starting with BASE_URL go through
        // the start stub (also the upload-start endpoint); the dedicated upload
        // URL goes through finalize. This mirrors how the real flow works —
        // start replies with X-Goog-Upload-URL = UPLOAD_URL and the second
        // request PUTs there.
        val handler: MockRequestHandler = { request ->
            val url = request.url.toString()
            if (url.startsWith(UPLOAD_URL)) {
                finalize.observed += request
                respond(
                    """
                    {"file":{
                      "name":"files/abc","uri":"https://example.com/files/abc",
                      "mimeType":"audio/mpeg","sizeBytes":"3","state":"PROCESSING"
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

    private class RecordingStub {
        lateinit var client: HttpClient
        val observed: MutableList<HttpRequestData> = mutableListOf()
    }

    private companion object {
        const val UPLOAD_URL = "https://upload.example.com/upload/abc-token"
    }
}
