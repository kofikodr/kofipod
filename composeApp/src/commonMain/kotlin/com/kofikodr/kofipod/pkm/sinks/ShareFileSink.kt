// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.pkm.MarkdownDocument
import com.kofikodr.kofipod.pkm.MarkdownTempFilePort
import com.kofikodr.kofipod.pkm.PkmExportRequest
import com.kofikodr.kofipod.share.Sharer

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
