// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * MVP supports MP3 only. MP4 ships in Slice 4 alongside the Media3 Transformer
 * video composition graph. The enum is introduced now so the editor's format
 * chip and the Snippet.lastExportFormat column don't need to change wire shape
 * later — Slice 4 will simply add `MP4` and start emitting it.
 */
enum class SnippetFormat(val wire: String, val mimeType: String, val fileExtension: String) {
    MP3(wire = "mp3", mimeType = "audio/mpeg", fileExtension = "mp3"),
    ;

    companion object {
        fun fromWire(value: String?): SnippetFormat? = entries.firstOrNull { it.wire == value }
    }
}

data class Snippet(
    val id: String,
    val episodeId: String,
    val podcastId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String?,
    val captionOverride: String?,
    val createdAtMs: Long,
    val lastExportFormat: SnippetFormat?,
    val lastExportPath: String?,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val isRendered: Boolean get() = lastExportFormat != null && !lastExportPath.isNullOrBlank()
}

data class SnippetWithContext(
    val snippet: Snippet,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String,
)
