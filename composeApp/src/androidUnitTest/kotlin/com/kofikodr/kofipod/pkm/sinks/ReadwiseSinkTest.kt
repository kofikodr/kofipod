// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.pkm.MarkdownDocument
import com.kofikodr.kofipod.pkm.PkmExportRequest
import com.kofikodr.kofipod.pkm.connections.ConnectionKind
import com.kofikodr.kofipod.pkm.connections.OAuthTokenVault
import com.kofikodr.kofipod.pkm.connections.PkmConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReadwiseSinkTest {
    @Test fun firstExportPostsAndReturnsExternalId() =
        runTest {
            val client = FakeReadwiseClient(createReturns = Result.success(42L))
            val vault = FakeVault().apply { put("readwise.token", "rw-tok") }
            val conn = PkmConnection("readwise", ConnectionKind.Readwise, "readwise.token", null, 0L, null)
            val sink = ReadwiseSink(client, vault) { conn }
            val doc =
                MarkdownDocument(
                    frontmatter =
                        listOf(
                            "podcast" to "Show",
                            "episode" to "Episode 1",
                            "episodeUrl" to "https://pod.link/abc",
                            "kofipodId" to "bookmark-b1",
                        ),
                    body = "Quote text",
                    filename = "x.md",
                )
            val result = sink.export(doc, PkmExportRequest.Bookmark("b1"), priorExternalId = null)
            assertIs<ExportSinkResult.Success>(result)
            assertEquals("42", result.externalId)
            assertEquals(1, client.createCalls)
            assertEquals(0, client.updateCalls)

            // Verify payload structure
            val createRequest = client.lastCreateRequest
            assertIs<ReadwiseCreateRequest>(createRequest)
            assertEquals(1, createRequest.highlights.size)
            val highlight = createRequest.highlights[0]
            assertEquals("Quote text", highlight.text)
            assertEquals("Episode 1", highlight.title)
            assertEquals("Show", highlight.author)
            assertEquals("https://pod.link/abc", highlight.sourceUrl)
            assertEquals("kofipodId:bookmark-b1", highlight.note)
            assertEquals("podcast", highlight.sourceType)
        }

    @Test fun reExportPatchesByExternalId() =
        runTest {
            val client = FakeReadwiseClient()
            val vault = FakeVault().apply { put("readwise.token", "rw-tok") }
            val conn = PkmConnection("readwise", ConnectionKind.Readwise, "readwise.token", null, 0L, null)
            val sink = ReadwiseSink(client, vault) { conn }
            val doc =
                MarkdownDocument(
                    frontmatter =
                        listOf(
                            "podcast" to "Show",
                            "episode" to "Episode 1",
                            "episodeUrl" to "https://x",
                            "kofipodId" to "bookmark-b1",
                        ),
                    body = "Updated quote",
                    filename = "x.md",
                )
            val result = sink.export(doc, PkmExportRequest.Bookmark("b1"), priorExternalId = "42")
            assertIs<ExportSinkResult.Success>(result)
            assertEquals("42", result.externalId)
            assertEquals(0, client.createCalls)
            assertEquals(1, client.updateCalls)
            assertEquals(42L, client.lastUpdateId)

            // Verify update payload
            val updateRequest = client.lastUpdateRequest
            assertIs<ReadwiseUpdateRequest>(updateRequest)
            assertEquals("Updated quote", updateRequest.text)
            assertEquals("kofipodId:bookmark-b1", updateRequest.note)
        }

    @Test fun missingTokenReturnsPermanentFailure() =
        runTest {
            val sink =
                ReadwiseSink(FakeReadwiseClient(), FakeVault()) {
                    PkmConnection("readwise", ConnectionKind.Readwise, "readwise.token", null, 0L, null)
                }
            val result =
                sink.export(
                    MarkdownDocument(emptyList(), "", "x.md"),
                    PkmExportRequest.Bookmark("b1"),
                    null,
                )
            assertIs<ExportSinkResult.PermanentFailure>(result)
        }

    @Test fun networkFailurePropagatesAsTransientFailure() =
        runTest {
            val client = FakeReadwiseClient(createReturns = Result.failure(RuntimeException("network down")))
            val vault = FakeVault().apply { put("readwise.token", "rw-tok") }
            val conn = PkmConnection("readwise", ConnectionKind.Readwise, "readwise.token", null, 0L, null)
            val sink = ReadwiseSink(client, vault) { conn }
            val result =
                sink.export(
                    MarkdownDocument(
                        listOf("podcast" to "x", "episode" to "y", "episodeUrl" to "https://x", "kofipodId" to "bookmark-b1"),
                        "body",
                        "x.md",
                    ),
                    PkmExportRequest.Bookmark("b1"),
                    null,
                )
            assertIs<ExportSinkResult.TransientFailure>(result)
        }

    @Test fun unauthorizedReturnsPermanentFailureWithReconnectPrompt() =
        runTest {
            // A revoked/invalid token (401) must NOT loop through the retry worker —
            // it must surface as a permanent failure that points the user to reconnect.
            val result = exportWith(createReturns = Result.failure(ReadwiseHttpException(401)))
            val failure = assertIs<ExportSinkResult.PermanentFailure>(result)
            assertTrue(
                failure.message.contains("Reconnect", ignoreCase = true),
                "Auth failure must prompt the user to reconnect, was: ${failure.message}",
            )
        }

    @Test fun forbiddenReturnsPermanentFailureWithReconnectPrompt() =
        runTest {
            // 403 is an auth failure too — it must carry the same reconnect prompt as 401.
            val failure =
                assertIs<ExportSinkResult.PermanentFailure>(
                    exportWith(createReturns = Result.failure(ReadwiseHttpException(403))),
                )
            assertTrue(failure.message.contains("Reconnect", ignoreCase = true))
        }

    @Test fun rateLimitReturnsTransientFailure() =
        runTest {
            // 429 is a back-off signal, not a credential problem — keep retrying.
            assertIs<ExportSinkResult.TransientFailure>(
                exportWith(createReturns = Result.failure(ReadwiseHttpException(429))),
            )
        }

    @Test fun serverErrorReturnsTransientFailure() =
        runTest {
            assertIs<ExportSinkResult.TransientFailure>(
                exportWith(createReturns = Result.failure(ReadwiseHttpException(503))),
            )
        }

    @Test fun otherClientErrorReturnsPermanentFailure() =
        runTest {
            // A 400 will never succeed on blind retry — it must be permanent so the
            // worker stops looping, even though it isn't an auth failure.
            val result = exportWith(createReturns = Result.failure(ReadwiseHttpException(400)))
            val failure = assertIs<ExportSinkResult.PermanentFailure>(result)
            assertTrue(
                !failure.message.contains("Reconnect", ignoreCase = true),
                "Non-auth permanent failure must not falsely tell the user to reconnect",
            )
        }

    @Test fun unauthorizedOnUpdatePathReturnsPermanentFailure() =
        runTest {
            // The re-export (PATCH) path must classify auth failures identically.
            val client = FakeReadwiseClient(updateReturns = Result.failure(ReadwiseHttpException(401)))
            val vault = FakeVault().apply { put("readwise.token", "rw-tok") }
            val conn = PkmConnection("readwise", ConnectionKind.Readwise, "readwise.token", null, 0L, null)
            val sink = ReadwiseSink(client, vault) { conn }
            val result =
                sink.export(
                    MarkdownDocument(
                        listOf("podcast" to "x", "episode" to "y", "episodeUrl" to "https://x", "kofipodId" to "bookmark-b1"),
                        "body",
                        "x.md",
                    ),
                    PkmExportRequest.Bookmark("b1"),
                    priorExternalId = "42",
                )
            val failure = assertIs<ExportSinkResult.PermanentFailure>(result)
            assertTrue(failure.message.contains("Reconnect", ignoreCase = true))
            assertEquals(1, client.updateCalls)
        }

    /** Run a create-path export against a client whose create call returns [createReturns]. */
    private suspend fun exportWith(createReturns: Result<Long>): ExportSinkResult {
        val client = FakeReadwiseClient(createReturns = createReturns)
        val vault = FakeVault().apply { put("readwise.token", "rw-tok") }
        val conn = PkmConnection("readwise", ConnectionKind.Readwise, "readwise.token", null, 0L, null)
        val sink = ReadwiseSink(client, vault) { conn }
        return sink.export(
            MarkdownDocument(
                listOf("podcast" to "x", "episode" to "y", "episodeUrl" to "https://x", "kofipodId" to "bookmark-b1"),
                "body",
                "x.md",
            ),
            PkmExportRequest.Bookmark("b1"),
            priorExternalId = null,
        )
    }
}

private class FakeReadwiseClient(
    val createReturns: Result<Long> = Result.success(1L),
    val updateReturns: Result<Unit> = Result.success(Unit),
) : ReadwiseClient(
        HttpClient(MockEngine) {
            engine {
                addHandler { respond("", HttpStatusCode.OK) }
            }
        },
    ) {
    var createCalls = 0
    var updateCalls = 0
    var lastUpdateId: Long? = null
    var lastCreateRequest: ReadwiseCreateRequest? = null
    var lastUpdateRequest: ReadwiseUpdateRequest? = null

    override suspend fun verify(token: String) = true

    override suspend fun createHighlight(
        token: String,
        request: ReadwiseCreateRequest,
    ): Result<Long> {
        createCalls++
        lastCreateRequest = request
        return createReturns
    }

    override suspend fun updateHighlight(
        token: String,
        id: Long,
        request: ReadwiseUpdateRequest,
    ): Result<Unit> {
        updateCalls++
        lastUpdateId = id
        lastUpdateRequest = request
        return updateReturns
    }
}

private class FakeVault : OAuthTokenVault {
    private val map = mutableMapOf<String, String>()

    override suspend fun put(
        key: String,
        token: String,
    ) {
        map[key] = token
    }

    override suspend fun get(key: String): String? = map[key]

    override suspend fun clear(key: String) {
        map.remove(key)
    }
}
