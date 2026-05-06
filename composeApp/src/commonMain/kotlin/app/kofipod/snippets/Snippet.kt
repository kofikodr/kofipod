// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * MVP supports MP3 only. MP4 ships in Slice 4 alongside the Media3 Transformer
 * video composition graph. The enum is introduced now so the editor's format
 * chip and the Snippet.lastExportFormat column don't need to change wire shape
 * later — Slice 4 will simply add `MP4` and start emitting it.
 */
enum class SnippetFormat(val wire: String, val mimeType: String, val fileExtension: String) {
    /**
     * Video export. Cover-art bg + generated waveform card overlay + caption
     * text overlay. Composition graph lives in [SnippetExporter.exportMp4]
     * (Android = Media3 Transformer). Default for new snippets in the editor —
     * the design positions MP4 as the headline format.
     */
    MP4(wire = "mp4", mimeType = "video/mp4", fileExtension = "mp4"),

    /**
     * Audio-only export. Despite the enum name `MP3` (chosen for user-facing
     * familiarity and forward compatibility with a future libmp3lame muxer),
     * the actual container is M4A (AAC-in-MP4) — that's what Media3
     * Transformer's bundled muxer produces reliably. The MIME `audio/mp4`
     * matches the bytes; share targets handle it correctly.
     */
    MP3(wire = "mp3", mimeType = "audio/mp4", fileExtension = "m4a"),
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
