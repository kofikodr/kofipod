// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Renders a Snippet to a file at [outputPath]. Implementations are expected to:
 *  - support either a local file path or a streaming URL as [sourceUriOrPath]
 *  - clip exactly to [snippet.startMs, snippet.endMs]
 *  - emit format-appropriate output (MP3 -> audio/mpeg ID3-tagged file)
 *  - report progress in [0f, 1f] via [onProgress]
 *  - return the absolute output path on success, or a Throwable on failure
 *
 * Slice 3 ships MP3 only. Slice 4 will add MP4 (Media3 Transformer with
 * Composition + BitmapOverlay + TextOverlay).
 */
expect class SnippetExporter {
    suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<String>
}
