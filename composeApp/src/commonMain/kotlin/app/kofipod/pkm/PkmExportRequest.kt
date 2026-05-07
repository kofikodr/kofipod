// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/** Identifies which item the user wants to export. */
sealed interface PkmExportRequest {
    data class Snippet(val snippetId: String) : PkmExportRequest

    data class Bookmark(val bookmarkId: String) : PkmExportRequest

    data class AiSummary(val episodeId: String) : PkmExportRequest
}

/** Coordinator → host (snackbar) signal. */
sealed interface PkmExportResult {
    data object Copied : PkmExportResult

    data object Shared : PkmExportResult

    /** Connection-bound destination wrote the item (Obsidian / Readwise / Notion). UI surfaces a confirmation snackbar. */
    data object Exported : PkmExportResult

    data class Failed(val message: String) : PkmExportResult
}
