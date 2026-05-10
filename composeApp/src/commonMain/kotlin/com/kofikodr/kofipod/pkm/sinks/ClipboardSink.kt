// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.pkm.ClipboardPort
import com.kofikodr.kofipod.pkm.MarkdownDocument
import com.kofikodr.kofipod.pkm.PkmExportRequest

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
