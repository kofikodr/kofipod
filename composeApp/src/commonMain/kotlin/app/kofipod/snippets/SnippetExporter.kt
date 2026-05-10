// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Renders a Snippet to a file at [outputPath]. Implementations are expected to:
 *  - support either a local file path or a streaming URL as [sourceUriOrPath]
 *  - clip exactly to [snippet.startMs, snippet.endMs]
 *  - emit format-appropriate output (MP3 -> audio/mpeg; MP4 -> H264/AAC with
 *    a waveform cover-card video track and optional caption text overlay)
 *  - report progress in [0f, 1f] via [onProgress]
 *  - return the absolute output path on success, or a Throwable on failure
 *
 * Slice 3 shipped MP3 only. Slice 4 adds MP4 (Media3 Transformer with a
 * Composition graph: image-source MediaItem + OverlayEffect carrying a
 * TextOverlay for the caption and a pre-rendered Bitmap for the cover-card
 * waveform).
 */
expect class SnippetExporter {
    suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<String>

    suspend fun exportMp4(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        coverArtUriOrPath: String?,
        captionText: String?,
        waveformSamples: WaveformSamples,
        onProgress: (Float) -> Unit = {},
    ): Result<String>
}
