// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

/**
 * Pure-Kotlin byte-size estimator for rendered snippets. Used by the editor's
 * format chip to label each segment with an estimated size (e.g. "MP4 · 3.4 MB")
 * before the user commits to render. Estimates are intentionally rough — the
 * actual MP4 size depends on cover-art compressibility and the bundled muxer's
 * choices — but they're stable enough that a user can compare formats.
 */
object SnippetSizeEstimator {
    /** Bytes per millisecond, indexed by format. MP3 ≈ 128 kbps; MP4 ≈ 640 kbps (compressed video + audio overlay). */
    private const val MP3_BYTES_PER_MS: Double = 16.0 // 128_000 bps / 8 / 1_000
    private const val MP4_BYTES_PER_MS: Double = 80.0 // 640_000 bps / 8 / 1_000

    fun estimateBytes(
        format: SnippetFormat,
        durationMs: Long,
    ): Long {
        val perMs =
            when (format) {
                SnippetFormat.MP3 -> MP3_BYTES_PER_MS
                SnippetFormat.MP4 -> MP4_BYTES_PER_MS
            }
        return (durationMs.coerceAtLeast(0L) * perMs).toLong()
    }

    fun formatBytes(bytes: Long): String {
        require(bytes >= 0L) { "bytes must be non-negative, got $bytes" }
        val mb = bytes.toDouble() / 1_000_000.0
        return when {
            mb >= 100.0 -> "${mb.toInt()} MB"
            mb >= 1.0 -> {
                val rounded = (mb * 10).toInt() / 10.0
                "$rounded MB"
            }
            else -> "${(bytes / 1_000).toInt()} KB" // truncates toward zero — estimates are rough by design (see KDoc)
        }
    }
}
