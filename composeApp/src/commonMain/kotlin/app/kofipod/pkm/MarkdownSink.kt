// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Seam over the two terminal export operations [PkmExportCoordinator] dispatches
 * to. Lets tests substitute a fake without constructing the real
 * [MarkdownExporter] (which depends on an `expect class Sharer` that isn't
 * trivially constructible in `commonTest`).
 *
 * The real [MarkdownExporter] implements this interface — method signatures
 * already match, so production wiring is "implements MarkdownSink" with no
 * behaviour change.
 */
interface MarkdownSink {
    /** Copy the rendered document to the system clipboard. */
    fun exportToClipboard(document: MarkdownDocument)

    /** Write the rendered document to a temp `.md` file and invoke the share sheet. */
    suspend fun exportAsFile(
        document: MarkdownDocument,
        shareTitle: String,
    )
}
