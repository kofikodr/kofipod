// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [GeminiClient.validate]'s HTTP-status → [AiError] mapping. This is the security
 * contract of the BYOK flow: a wrong mapping (e.g. 401 mis-routed to RateLimited)
 * would tell the user their key works when it doesn't, or vice versa. Each branch in
 * the production `when` block has a dedicated test.
 *
 * The Logging plugin is intentionally NOT installed on this test client either — we
 * want the test fixture to mirror the production constraint that this client is the
 * only HTTP path Gemini calls travel.
 */
class GeminiClientTest {
    @Test
    fun validate_returnsSuccess_on200() =
        runTest {
            val client = clientThatReturns(HttpStatusCode.OK)

            val result = GeminiClient(client).validate(apiKey = "k", model = GeminiModel.Flash)

            assertTrue(result.isSuccess, "200 OK must round-trip as Result.success")
        }

    @Test
    fun validate_returnsKeyInvalid_on400() = assertMaps(HttpStatusCode.BadRequest, AiError.KeyInvalid)

    @Test
    fun validate_returnsKeyInvalid_on401() = assertMaps(HttpStatusCode.Unauthorized, AiError.KeyInvalid)

    @Test
    fun validate_returnsKeyInvalid_on403() = assertMaps(HttpStatusCode.Forbidden, AiError.KeyInvalid)

    @Test
    fun validate_returnsRateLimited_on429() = assertMaps(HttpStatusCode.TooManyRequests, AiError.RateLimited)

    @Test
    fun validate_returnsUnknown_carryingStatusCode_onServer500() =
        runTest {
            val client = clientThatReturns(HttpStatusCode.InternalServerError)

            val result = GeminiClient(client).validate(apiKey = "k", model = GeminiModel.Flash)

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            val unknown = assertIs<AiError.Unknown>(error, "500 should map to AiError.Unknown, got $error")
            assertEquals(
                500,
                unknown.statusCode,
                "Unknown must propagate the actual status code so the UI / telemetry can disambiguate",
            )
        }

    @Test
    fun validate_returnsUnknown_onUnexpected2xxButNotSuccess() =
        runTest {
            // 204 No Content is in `isSuccess()` per Ktor, so this confirms the helper
            // we trust isn't quietly disagreeing with our intent. Locked here so a future
            // refactor that special-cases 200 (and rejects 2xx-but-not-200) doesn't
            // accidentally reject Google's "all clear" signals.
            val client = clientThatReturns(HttpStatusCode.NoContent)

            val result = GeminiClient(client).validate(apiKey = "k", model = GeminiModel.Flash)

            assertTrue(result.isSuccess, "Any 2xx must be treated as success — Ktor's isSuccess() is the source of truth")
        }

    @Test
    fun validate_returnsNetwork_onTransportException() =
        runTest {
            val client =
                HttpClient(MockEngine { _ -> throw IOException("no network") }) {
                    install(ContentNegotiation) { json(Json) }
                }

            val result = GeminiClient(client).validate(apiKey = "k", model = GeminiModel.Flash)

            val error = (result.exceptionOrNull() as? AiErrorException)?.error
            assertEquals(
                AiError.Network,
                error,
                "Transport failures (no DNS, no route, broken pipe) must surface as AiError.Network — never Unknown",
            )
        }

    @Test
    fun validate_includesApiKey_inUrl_andUsesModelApiId() =
        runTest {
            // The most security-sensitive request invariant: the key flows in the URL
            // query parameter (per Gemini's REST contract) and the model in the path
            // segment. If either is wrong, the call either leaks to the wrong endpoint
            // or fails authentication. We pin both shapes here.
            var observedUrl: String? = null
            val handler: MockRequestHandler = { request ->
                observedUrl = request.url.toString()
                respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            val client =
                HttpClient(MockEngine(handler)) {
                    install(ContentNegotiation) { json(Json) }
                }

            GeminiClient(client).validate(apiKey = "secret-abc", model = GeminiModel.FlashLite)

            val url = observedUrl ?: error("MockEngine did not capture the request")
            assertTrue(
                "models/${GeminiModel.FlashLite.apiId}:generateContent" in url,
                "URL must target the selected model's apiId path segment; got: $url",
            )
            assertTrue(
                "key=secret-abc" in url,
                "URL must include the ?key=… query param so Gemini can authenticate; got: $url",
            )
        }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun assertMaps(
        status: HttpStatusCode,
        expectedError: AiError,
    ) = runTest {
        val client = clientThatReturns(status)

        val result = GeminiClient(client).validate(apiKey = "k", model = GeminiModel.Flash)

        val actual = (result.exceptionOrNull() as? AiErrorException)?.error
        assertEquals(
            expectedError,
            actual,
            "HTTP ${status.value} must map to $expectedError (got $actual). " +
                "If you're widening this branch, add the new test alongside.",
        )
    }

    private fun clientThatReturns(status: HttpStatusCode): HttpClient =
        HttpClient(
            MockEngine { _ ->
                if (status.value < 400) {
                    respond("{}", status, headersOf("Content-Type", "application/json"))
                } else {
                    respondError(status)
                }
            },
        ) {
            install(ContentNegotiation) { json(Json) }
        }
}
