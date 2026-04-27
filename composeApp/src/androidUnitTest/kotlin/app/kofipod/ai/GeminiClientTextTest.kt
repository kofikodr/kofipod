// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins [GeminiClient.generateFromText]'s contract:
 *
 *  - Request body shape (two text parts: prompt then content, in that order).
 *  - Response parsing (extracts `candidates[0].content.parts[*].text`).
 *  - HTTP-status → [AiError] mapping (mirrors validate; same security implications).
 *
 * No live network. The transcript path is the v1 hot path — wrong status mapping
 * would silently waste user quota or surface "AI failed" when their key is fine.
 */
class GeminiClientTextTest {
    @Test
    fun generateFromText_returnsCandidateText_on200() =
        runTest {
            val client =
                clientThatReturns(
                    status = HttpStatusCode.OK,
                    body =
                        """
                        {
                          "candidates": [{
                            "content": { "parts": [{ "text": "  Episode summary body.  " }] }
                          }]
                        }
                        """.trimIndent(),
                )

            val result =
                GeminiClient(client).generateFromText(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    prompt = "P",
                    content = "C",
                )

            assertEquals(
                "Episode summary body.",
                result.getOrNull(),
                "Successful response body's candidate text should round-trip trimmed",
            )
        }

    @Test
    fun generateFromText_sendsPromptThenContent_asTwoTextParts() =
        runTest {
            // The request DTO order is load-bearing: Gemini's instruction-following
            // is most reliable when the prompt precedes the user content. Flipping
            // the parts (e.g. via a refactor that reuses a generic Builder) would
            // silently degrade summaries.
            var observed: HttpRequestData? = null
            val handler: MockRequestHandler = { request ->
                observed = request
                respond(
                    """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
            val client =
                HttpClient(MockEngine(handler)) {
                    install(ContentNegotiation) { json(Json) }
                }

            GeminiClient(client).generateFromText(
                apiKey = "k",
                model = GeminiModel.Flash,
                prompt = "PROMPT_TEXT",
                content = "TRANSCRIPT_TEXT",
            )

            val request = assertNotNull(observed, "MockEngine did not capture the request")
            val bodyText = (request.body as TextContent).text
            val root = Json.parseToJsonElement(bodyText).jsonObject
            val parts =
                root["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            assertEquals(2, parts.size, "Body must carry exactly two text parts")
            assertEquals("PROMPT_TEXT", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("TRANSCRIPT_TEXT", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        }

    @Test
    fun generateFromText_joinsMultipleParts_intoSingleString() =
        runTest {
            // Regression: long transcripts caused Gemini to split a single response
            // across multiple `parts[*]`. The earlier implementation took only
            // `parts.firstOrNull`, so the user saw "Inte" / "Mofac" — words
            // sliced at the part boundary. The fix joins all non-blank parts.
            // A future refactor that reverts to firstOrNull must fail this test.
            val client =
                clientThatReturns(
                    status = HttpStatusCode.OK,
                    body =
                        """
                        {
                          "candidates": [{
                            "content": {
                              "parts": [
                                { "text": "Inte" },
                                { "text": "resting episode about " },
                                { "text": "podcasting." }
                              ]
                            }
                          }]
                        }
                        """.trimIndent(),
                )

            val result =
                GeminiClient(client).generateFromText(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    prompt = "P",
                    content = "C",
                )

            assertEquals(
                "Interesting episode about podcasting.",
                result.getOrNull(),
                "Multi-part responses must be joined, not truncated to the first part",
            )
        }

    @Test
    fun generateFromText_disablesThinkingBudget_inRequestBody() =
        runTest {
            // Regression: Gemini 2.5 Flash bills "thinking" (chain-of-thought)
            // tokens against `maxOutputTokens`, and on long transcripts the
            // entire budget would be consumed by thinking, surfacing as a
            // truncated 2-liner cut mid-sentence. `thinkingBudget = 0`
            // disables thinking entirely. A refactor that drops ThinkingConfig
            // (or sets it to -1 "dynamic" / null) silently re-introduces the
            // truncation bug — this assertion is the only thing that catches it.
            var observed: HttpRequestData? = null
            val handler: MockRequestHandler = { request ->
                observed = request
                respond(
                    """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
            val client =
                HttpClient(MockEngine(handler)) {
                    install(ContentNegotiation) { json(Json) }
                }

            GeminiClient(client).generateFromText(
                apiKey = "k",
                model = GeminiModel.Flash,
                prompt = "P",
                content = "C",
            )

            val request = assertNotNull(observed, "MockEngine did not capture the request")
            val bodyText = (request.body as TextContent).text
            val root = Json.parseToJsonElement(bodyText).jsonObject
            val generationConfig =
                assertNotNull(
                    root["generationConfig"]?.jsonObject,
                    "generationConfig must be present on the wire",
                )
            val thinkingBudget =
                assertNotNull(
                    generationConfig["thinkingConfig"]?.jsonObject?.get("thinkingBudget")?.jsonPrimitive?.int,
                    "thinkingConfig.thinkingBudget MUST be on the wire — without it, " +
                        "long transcripts get truncated to a 2-liner",
                )
            assertEquals(0, thinkingBudget, "thinkingBudget must be 0 (disabled), not -1 or any positive value")
        }

    @Test
    fun generateFromText_returnsUnknown_onEmptyCandidates() =
        runTest {
            // Gemini sometimes returns 200 with empty candidates (e.g. safety filter
            // tripped). Without a defensive check we'd surface an empty summary; map
            // it to AiError.Unknown so the UI shows the retry card instead.
            val client =
                clientThatReturns(
                    status = HttpStatusCode.OK,
                    body = """{"candidates":[]}""",
                )

            val result =
                GeminiClient(client).generateFromText(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    prompt = "P",
                    content = "C",
                )

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            assertIs<AiError.Unknown>(error, "Empty candidates must surface as Unknown, not silent empty success")
        }

    @Test
    fun generateFromText_mapsKeyInvalid_on400_401_403() =
        runTest {
            for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                val client = clientThatReturns(status)

                val result =
                    GeminiClient(client).generateFromText(
                        apiKey = "k",
                        model = GeminiModel.Flash,
                        prompt = "P",
                        content = "C",
                    )

                assertEquals(
                    AiError.KeyInvalid,
                    (result.exceptionOrNull() as? AiErrorException)?.error,
                    "${status.value} must map to KeyInvalid",
                )
            }
        }

    @Test
    fun generateFromText_mapsRateLimited_on429() =
        runTest {
            val client = clientThatReturns(HttpStatusCode.TooManyRequests)

            val result =
                GeminiClient(client).generateFromText("k", GeminiModel.Flash, "P", "C")

            assertEquals(
                AiError.RateLimited,
                (result.exceptionOrNull() as? AiErrorException)?.error,
            )
        }

    @Test
    fun generateFromText_mapsNetwork_onTransportException() =
        runTest {
            val client =
                HttpClient(MockEngine { _ -> throw IOException("no route") }) {
                    install(ContentNegotiation) { json(Json) }
                }

            val result =
                GeminiClient(client).generateFromText("k", GeminiModel.Flash, "P", "C")

            assertEquals(
                AiError.Network,
                (result.exceptionOrNull() as? AiErrorException)?.error,
            )
        }

    @Test
    fun generateFromText_mapsUnknown_carryingStatusCode_on500() =
        runTest {
            val client = clientThatReturns(HttpStatusCode.InternalServerError)

            val result =
                GeminiClient(client).generateFromText("k", GeminiModel.Flash, "P", "C")

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            val unknown = assertIs<AiError.Unknown>(error)
            assertEquals(500, unknown.statusCode)
        }

    @Test
    fun generateFromText_putsApiKeyInUrl_andUsesModelPath() =
        runTest {
            var observedUrl: String? = null
            val handler: MockRequestHandler = { request ->
                observedUrl = request.url.toString()
                respond(
                    """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
            val client =
                HttpClient(MockEngine(handler)) {
                    install(ContentNegotiation) { json(Json) }
                }

            GeminiClient(client).generateFromText("secret-xyz", GeminiModel.FlashLite, "P", "C")

            val url = assertNotNull(observedUrl)
            assertTrue("models/${GeminiModel.FlashLite.apiId}:generateContent" in url)
            assertTrue("key=secret-xyz" in url)
        }

    @Suppress("unused") // referenced by the helper to suppress JsonArray/JsonObject import lints if any
    private val sentinel: Pair<JsonArray, JsonObject>? = null

    private fun clientThatReturns(
        status: HttpStatusCode,
        body: String = "{}",
    ): HttpClient =
        HttpClient(
            MockEngine { _ ->
                if (status.value < 400) {
                    respond(body, status, headersOf("Content-Type", "application/json"))
                } else {
                    respondError(status)
                }
            },
        ) {
            install(ContentNegotiation) { json(Json) }
        }
}
