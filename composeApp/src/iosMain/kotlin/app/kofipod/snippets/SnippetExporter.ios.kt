// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

actual class SnippetExporter {
    actual suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): Result<String> = Result.failure(NotImplementedError("Snippets not supported on iOS"))

    actual suspend fun exportMp4(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        coverArtUriOrPath: String?,
        captionText: String?,
        waveformSamples: WaveformSamples,
        onProgress: (Float) -> Unit,
    ): Result<String> = Result.failure(NotImplementedError("Snippets MP4 not yet supported on iOS"))
}
