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
    fun generateFromText_parsesStructuredJson_intoAiSummaryJson() =
        runTest {
            // Slice 3 wired Gemini's structured-output (responseMimeType +
            // responseSchema) so the candidate text is a JSON document, not
            // free prose. The client must decode it into [AiSummaryJson]
            // verbatim — preserving entity counts so the panel can render
            // the People / Things / Links sections.
            val structured =
                buildString {
                    append("""{"summary":"Episode summary body.",""")
                    append(""""people":[{"name":"Alice","subtitle":"Host"},{"name":"Bob","subtitle":""}],""")
                    append(""""things":[{"name":"Pragmatic Programmer","subtitle":"Book"}],""")
                    append(""""links":[{"label":"Site","url":"https://x.test"}]}""")
                }
            // Wrap the JSON in extra whitespace inside the candidate text so we
            // also pin the outer-trim contract (Gemini occasionally pads the
            // text part with leading/trailing newlines around the schema body).
            val client = clientThatReturns(status = HttpStatusCode.OK, body = candidateBody("\n  $structured  \n"))

            val result =
                GeminiClient(client).generateFromText(
                    apiKey = "k",
                    model = GeminiModel.Flash,
                    prompt = "P",
                    content = "C",
                )

            val parsed = assertNotNull(result.getOrNull(), "Structured JSON response must parse to AiSummaryJson")
            assertEquals("Episode summary body.", parsed.summary, "Outer whitespace around the JSON envelope must be trimmed before decode")
            assertEquals(2, parsed.people.size)
            assertEquals("Alice", parsed.people[0].name)
            assertEquals("Host", parsed.people[0].subtitle)
            assertEquals("Bob", parsed.people[1].name)
            assertEquals("", parsed.people[1].subtitle, "Empty subtitle must round-trip as blank string, not be dropped")
            assertEquals(1, parsed.things.size)
            assertEquals("Pragmatic Programmer", parsed.things[0].name)
            assertEquals("Book", parsed.things[0].subtitle)
            assertEquals(1, parsed.links.size)
            assertEquals("https://x.test", parsed.links[0].url)
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
                    candidateBody("""{"summary":"ok"}"""),
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
    fun generateFromText_joinsMultipleParts_intoSingleStructuredJson() =
        runTest {
            // Regression: long responses caused Gemini to split a single JSON
            // body across multiple `parts[*]`. The earlier implementation took
            // only `parts.firstOrNull`, so the joined fragment was no longer a
            // valid JSON document and parsing collapsed the whole response.
            // The fix joins all non-blank parts before structured-decoding,
            // so a refactor that reverts to firstOrNull would fail to parse.
            val client =
                clientThatReturns(
                    status = HttpStatusCode.OK,
                    body =
                        """
                        {
                          "candidates": [{
                            "content": {
                              "parts": [
                                { "text": "{\"summary\":\"Inte" },
                                { "text": "resting episode about " },
                                { "text": "podcasting.\"}" }
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
                result.getOrNull()?.summary,
                "Multi-part JSON must be joined before parsing — first-only would yield invalid JSON and surface as Unknown",
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
                    candidateBody("""{"summary":"ok"}"""),
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
    fun generateFromText_returnsUnknown_onMalformedJsonInCandidateText() =
        runTest {
            // The model is supposed to honour responseSchema, but a wedge in the
            // structured-output path (e.g. tripped safety filter that emits a
            // plain-text apology, or a bad fixture in dev) lands non-JSON in
            // the candidate text. Surface AiError.Unknown rather than crash —
            // the panel shows the retry card instead of stack-tracing.
            val client =
                clientThatReturns(
                    status = HttpStatusCode.OK,
                    body = candidateBody("not valid json {{{"),
                )

            val result =
                GeminiClient(client).generateFromText("k", GeminiModel.Flash, "P", "C")

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            assertIs<AiError.Unknown>(error, "Malformed structured output must surface as Unknown, not propagate as a parse exception")
        }

    @Test
    fun generateFromText_returnsUnknown_onBlankSummary() =
        runTest {
            // Schema-valid but content-empty: e.g. responseSchema enforces the
            // shape but the model produces `{"summary":""}` with no entities.
            // From the user's perspective this is indistinguishable from a
            // failed run — empty Ready card with a "Generated 5m ago" footer
            // is worse than an error card with Retry.
            val client =
                clientThatReturns(
                    status = HttpStatusCode.OK,
                    body = candidateBody("""{"summary":"   "}"""),
                )

            val result =
                GeminiClient(client).generateFromText("k", GeminiModel.Flash, "P", "C")

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            assertIs<AiError.Unknown>(error, "Blank summary in a schema-valid envelope must still surface as Unknown")
        }

    @Test
    fun generateFromText_sendsResponseSchema_onTheWire() =
        runTest {
            // Without responseMimeType + responseSchema Gemini falls back to
            // free-form prose, which would parse as malformed JSON downstream
            // and surface every successful generation as Unknown. Pin both
            // fields to the wire so a refactor that drops one of them fails
            // here rather than silently in production.
            var observed: HttpRequestData? = null
            val handler: MockRequestHandler = { request ->
                observed = request
                respond(
                    candidateBody("""{"summary":"ok"}"""),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
            val client =
                HttpClient(MockEngine(handler)) {
                    install(ContentNegotiation) { json(Json) }
                }

            GeminiClient(client).generateFromText("k", GeminiModel.Flash, "P", "C")

            val request = assertNotNull(observed)
            val bodyText = (request.body as TextContent).text
            val generationConfig = Json.parseToJsonElement(bodyText).jsonObject["generationConfig"]!!.jsonObject
            assertEquals(
                "application/json",
                generationConfig["responseMimeType"]?.jsonPrimitive?.content,
                "responseMimeType must be application/json — without it the model returns prose",
            )
            val schema =
                assertNotNull(
                    generationConfig["responseSchema"]?.jsonObject,
                    "responseSchema must be present so Gemini constrains the JSON shape",
                )
            assertEquals("OBJECT", schema["type"]?.jsonPrimitive?.content)
            val properties = assertNotNull(schema["properties"]?.jsonObject)
            assertTrue("summary" in properties, "Schema must declare summary")
            assertTrue("people" in properties, "Schema must declare people — entity extraction is the slice contract")
            assertTrue("things" in properties)
            assertTrue("links" in properties)
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
                    candidateBody("""{"summary":"ok"}"""),
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

    /**
     * Builds a Gemini-shaped success body whose single candidate text part
     * carries [structuredJson] as a JSON-encoded string. Saves every test from
     * hand-escaping the inner double-quotes when standing up a happy-path
     * response.
     */
    private fun candidateBody(structuredJson: String): String {
        val escaped = structuredJson.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

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
