// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Decodes a clipped audio range to mono 16-bit PCM. The result feeds
 * [AmplitudeEnvelopeBuilder] which produces the per-frame bars values for
 * the snippet MP4's bars overlay.
 *
 * The Android actual wraps `MediaExtractor` + `MediaCodec`; the iOS actual
 * is a not-implemented stub (snippet rendering is Android-only).
 *
 * Hard-fail policy: any decode failure throws [SnippetPcmDecodeException].
 * The caller (`SnippetExporter.exportMp4`) surfaces the failure to the
 * user instead of falling back to synthetic bars — that keeps the
 * "music-video visualizer" promise honest.
 */
expect class PcmDecoder {
    suspend fun decodeMono(
        sourceUriOrPath: String,
        startMs: Long,
        endMs: Long,
    ): DecodedPcm
}

/**
 * Mono 16-bit PCM with its native sample rate (so the envelope builder can
 * compute slice widths in samples without ever needing to resample).
 */
class DecodedPcm(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DecodedPcm && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

class SnippetPcmDecodeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
