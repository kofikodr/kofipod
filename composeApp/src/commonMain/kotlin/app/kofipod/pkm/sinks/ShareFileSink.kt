// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

import app.kofipod.pkm.MarkdownDocument
import app.kofipod.pkm.MarkdownTempFilePort
import app.kofipod.pkm.PkmExportRequest
import app.kofipod.share.Sharer

class ShareFileSink(
    private val tempFile: MarkdownTempFilePort,
    private val sharer: Sharer,
) : ExportSink {
    override suspend fun export(
        document: MarkdownDocument,
        request: PkmExportRequest,
        priorExternalId: String?,
    ): ExportSinkResult {
        val path = tempFile.writeTemp(document.filename, document.render())
        sharer.shareFile(
            title = "Share Markdown",
            path = path,
            mimeType = "text/markdown",
            captionText = null,
        )
        return ExportSinkResult.Success(externalId = null)
    }
}
