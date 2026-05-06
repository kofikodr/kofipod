// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.share.Sharer

/**
 * Routes a [MarkdownDocument] to one of two sinks:
 *   - clipboard via [ClipboardPort]
 *   - .md file via [MarkdownTempFilePort] + [Sharer.shareFile]
 *
 * Pure orchestrator — no entitlement check (that's the caller's job; see
 * [PkmExportCoordinator]).
 */
class MarkdownExporter(
    private val clipboard: ClipboardPort,
    private val tempFile: MarkdownTempFilePort,
    private val sharer: Sharer,
) : MarkdownSink {
    override fun exportToClipboard(document: MarkdownDocument) {
        clipboard.copyText(label = "Kofipod Markdown", text = document.render())
    }

    override suspend fun exportAsFile(
        document: MarkdownDocument,
        shareTitle: String,
    ) {
        val path = tempFile.writeTemp(document.filename, document.render())
        sharer.shareFile(
            title = shareTitle,
            path = path,
            mimeType = "text/markdown",
            captionText = null,
        )
    }
}
