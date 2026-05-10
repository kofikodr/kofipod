// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

import app.kofipod.pkm.ClipboardPort
import app.kofipod.pkm.MarkdownDocument
import app.kofipod.pkm.PkmExportRequest

class ClipboardSink(private val clipboard: ClipboardPort) : ExportSink {
    override suspend fun export(
        document: MarkdownDocument,
        request: PkmExportRequest,
        priorExternalId: String?,
    ): ExportSinkResult {
        clipboard.copyText("Kofipod Markdown", document.render())
        return ExportSinkResult.Success(externalId = null)
    }
}
