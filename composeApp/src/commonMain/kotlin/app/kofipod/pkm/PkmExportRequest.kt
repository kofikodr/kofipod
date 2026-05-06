// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/** Identifies which item the user wants to export. */
sealed interface PkmExportRequest {
    data class Snippet(val snippetId: String) : PkmExportRequest

    data class Bookmark(val bookmarkId: String) : PkmExportRequest

    data class AiSummary(val episodeId: String) : PkmExportRequest
}

/** Selected destination from the export bottom-sheet. */
enum class PkmExportSink { Clipboard, File }

/** Coordinator → host (snackbar) signal. */
sealed interface PkmExportResult {
    data object Copied : PkmExportResult

    data object Shared : PkmExportResult

    data class Failed(val message: String) : PkmExportResult
}
