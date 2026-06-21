// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.data.net.kofipodJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract for [ReadwiseClient]'s status handling. The key behaviour issue #22
 * depends on: a non-2xx response must surface as a [ReadwiseHttpException] that
 * carries the numeric status, so [ReadwiseSink] can tell a revoked token (401/403,
 * stop retrying + prompt reconnect) apart from a transient blip (429/5xx, retry).
 */
class ReadwiseClientTest {
    @Test
    fun createHighlight_returnsId_andSendsTokenHeader_on200() =
        runTest {
            var authHeader: String? = null
            val client =
                clientResponding { request ->
                    authHeader = request.headers["Authorization"]
                    respond(
                        content = """[{"id": 42}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val result = client.createHighlight("rw-tok", sampleCreate())
            assertEquals(42L, result.getOrNull())
            assertEquals("Token rw-tok", authHeader, "Readwise auth is a 'Token <key>' header")
        }

    @Test
    fun createHighlight_on401_failsWithAuthException() =
        runTest {
            val client = clientResponding { respond("Unauthorized", HttpStatusCode.Unauthorized) }
            val error = client.createHighlight("bad", sampleCreate()).exceptionOrNull()
            val httpError = assertIs<ReadwiseHttpException>(error)
            assertEquals(401, httpError.status)
            assertTrue(httpError.isAuthFailure, "401 must be classified as an auth failure")
            assertTrue(!httpError.isTransient, "401 must not be retried")
        }

    @Test
    fun createHighlight_on403_failsWithAuthException() =
        runTest {
            val client = clientResponding { respond("Forbidden", HttpStatusCode.Forbidden) }
            val httpError = assertIs<ReadwiseHttpException>(client.createHighlight("x", sampleCreate()).exceptionOrNull())
            assertEquals(403, httpError.status)
            assertTrue(httpError.isAuthFailure)
            assertTrue(!httpError.isTransient, "403 must not be retried")
        }

    @Test
    fun createHighlight_on408_isTransient() =
        runTest {
            val client = clientResponding { respond("timeout", HttpStatusCode.RequestTimeout) }
            val httpError = assertIs<ReadwiseHttpException>(client.createHighlight("x", sampleCreate()).exceptionOrNull())
            assertEquals(408, httpError.status)
            assertTrue(httpError.isTransient, "408 request-timeout must stay retryable")
            assertTrue(!httpError.isAuthFailure)
        }

    @Test
    fun createHighlight_on500_failsWithTransientException() =
        runTest {
            val client = clientResponding { respond("boom", HttpStatusCode.InternalServerError) }
            val httpError = assertIs<ReadwiseHttpException>(client.createHighlight("x", sampleCreate()).exceptionOrNull())
            assertEquals(500, httpError.status)
            assertTrue(httpError.isTransient, "5xx must stay retryable")
            assertTrue(!httpError.isAuthFailure)
        }

    @Test
    fun createHighlight_on429_isTransient() =
        runTest {
            val client = clientResponding { respond("slow down", HttpStatusCode.TooManyRequests) }
            val httpError = assertIs<ReadwiseHttpException>(client.createHighlight("x", sampleCreate()).exceptionOrNull())
            assertEquals(429, httpError.status)
            assertTrue(httpError.isTransient, "429 rate-limit must stay retryable")
        }

    @Test
    fun createHighlight_on200_withEmptyBody_isFailure() =
        runTest {
            val client =
                clientResponding {
                    respond(
                        content = "[]",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            assertTrue(client.createHighlight("x", sampleCreate()).isFailure, "Empty array means no id was created")
        }

    @Test
    fun updateHighlight_on401_failsWithAuthException() =
        runTest {
            val client = clientResponding { respond("Unauthorized", HttpStatusCode.Unauthorized) }
            val httpError =
                assertIs<ReadwiseHttpException>(
                    client.updateHighlight("x", 7L, ReadwiseUpdateRequest(text = "t")).exceptionOrNull(),
                )
            assertEquals(401, httpError.status)
            assertTrue(httpError.isAuthFailure)
        }

    @Test
    fun updateHighlight_on200_succeeds() =
        runTest {
            val client = clientResponding { respond("", HttpStatusCode.OK) }
            assertTrue(client.updateHighlight("x", 7L, ReadwiseUpdateRequest(text = "t")).isSuccess)
        }

    private fun clientResponding(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ReadwiseClient =
        ReadwiseClient(
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(kofipodJson) }
            },
        )

    private fun sampleCreate(): ReadwiseCreateRequest =
        ReadwiseCreateRequest(
            highlights =
                listOf(
                    ReadwiseHighlightCreate(
                        text = "quote",
                        title = "Episode",
                        author = "Show",
                        sourceUrl = "https://pod.link/abc",
                        note = "kofipodId:bookmark-b1",
                    ),
                ),
        )
}
