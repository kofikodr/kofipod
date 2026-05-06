// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

import app.kofipod.pkm.MarkdownDocument
import app.kofipod.pkm.PkmExportRequest
import app.kofipod.pkm.connections.PkmConnection

class ObsidianSink(
    private val writer: ObsidianFolderWriter,
    private val connectionLoader: suspend () -> PkmConnection?,
) : ExportSink {
    override suspend fun export(
        document: MarkdownDocument,
        request: PkmExportRequest,
        priorExternalId: String?,
    ): ExportSinkResult {
        val conn =
            connectionLoader()
                ?: return ExportSinkResult.PermanentFailure("Obsidian not connected")
        val folder =
            conn.folderUri
                ?: return ExportSinkResult.PermanentFailure("Obsidian folder URI missing")
        return runCatching {
            writer.write(folder, document.filename, document.render())
            ExportSinkResult.Success(externalId = document.filename)
        }.getOrElse { t ->
            ExportSinkResult.PermanentFailure(t.message ?: "Obsidian write failed")
        }
    }
}
