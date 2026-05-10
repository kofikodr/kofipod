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
}

private class FakeReadwiseClient(
    val createReturns: Result<Long> = Result.success(1L),
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
        return Result.success(Unit)
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
