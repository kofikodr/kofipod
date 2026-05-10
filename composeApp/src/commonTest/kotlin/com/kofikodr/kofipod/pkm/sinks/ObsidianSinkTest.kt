// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.pkm.MarkdownDocument
import com.kofikodr.kofipod.pkm.PkmExportRequest
import com.kofikodr.kofipod.pkm.connections.ConnectionKind
import com.kofikodr.kofipod.pkm.connections.PkmConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ObsidianSinkTest {
    @Test fun successWritesViaWriterAndReturnsFilenameAsExternalId() =
        runTest {
            val writer = FakeWriter()
            val conn = PkmConnection("obsidian", ConnectionKind.Obsidian, null, "content://tree/abc", 0L, null)
            val sink = ObsidianSink(writer) { conn }
            val doc =
                MarkdownDocument(
                    frontmatter = listOf("kofipodId" to "snippet-s1"),
                    body = "body",
                    filename = "demo-snippet-s1.md",
                )
            val result = sink.export(doc, PkmExportRequest.Snippet("s1"), priorExternalId = null)
            assertIs<ExportSinkResult.Success>(result)
            assertEquals("demo-snippet-s1.md", result.externalId)
            assertEquals("content://tree/abc", writer.lastTreeUri)
            assertEquals("demo-snippet-s1.md", writer.lastFilename)
            assertEquals(doc.render(), writer.lastBody)
        }

    @Test fun missingConnectionReturnsPermanentFailure() =
        runTest {
            val sink = ObsidianSink(FakeWriter()) { null }
            val doc = MarkdownDocument(emptyList(), "", "x.md")
            val result = sink.export(doc, PkmExportRequest.Snippet("s1"), null)
            assertIs<ExportSinkResult.PermanentFailure>(result)
        }

    @Test fun missingFolderUriReturnsPermanentFailure() =
        runTest {
            val conn = PkmConnection("obsidian", ConnectionKind.Obsidian, null, null, 0L, null)
            val sink = ObsidianSink(FakeWriter()) { conn }
            val doc = MarkdownDocument(emptyList(), "", "x.md")
            val result = sink.export(doc, PkmExportRequest.Snippet("s1"), null)
            assertIs<ExportSinkResult.PermanentFailure>(result)
        }

    @Test fun writerThrowsReturnsPermanentFailure() =
        runTest {
            val writer = FakeWriter(throwOnWrite = IllegalStateException("revoked"))
            val conn = PkmConnection("obsidian", ConnectionKind.Obsidian, null, "content://tree/x", 0L, null)
            val sink = ObsidianSink(writer) { conn }
            val doc = MarkdownDocument(emptyList(), "", "x.md")
            val result = sink.export(doc, PkmExportRequest.Snippet("s1"), null)
            assertIs<ExportSinkResult.PermanentFailure>(result)
        }
}

private class FakeWriter(val throwOnWrite: Throwable? = null) : ObsidianFolderWriter {
    var lastTreeUri: String? = null
    var lastFilename: String? = null
    var lastBody: String? = null

    override suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    ) {
        throwOnWrite?.let { throw it }
        lastTreeUri = treeUri
        lastFilename = filename
        lastBody = body
    }
}
