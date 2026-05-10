// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetSizeEstimatorTest {
    @Test
    fun mp3_estimate_uses_128_kbps() {
        val bytes = SnippetSizeEstimator.estimateBytes(SnippetFormat.MP3, 60_000L)
        // 128 kbps = 16_000 B/s → 60s = 960_000 B (within ±10% for codec overhead)
        assertTrue(bytes in 900_000..1_050_000, "got $bytes")
    }

    @Test
    fun mp4_estimate_uses_about_640_kbps() {
        val bytes = SnippetSizeEstimator.estimateBytes(SnippetFormat.MP4, 60_000L)
        // 640 kbps = 80_000 B/s → 60s = 4.8 MB; allow ±15%
        assertTrue(bytes in 4_000_000..5_600_000, "got $bytes")
    }

    @Test
    fun mp4_42s_clip_matches_design_label_3_4_MB_to_4_MB_range() {
        // design copy says "MP4 · 3.4 MB" for a 0:42 clip — tolerate 2.5–5 MB
        val bytes = SnippetSizeEstimator.estimateBytes(SnippetFormat.MP4, 42_000L)
        val mb = bytes.toDouble() / 1_000_000.0
        assertTrue(mb in 2.5..5.0, "got $mb MB")
    }

    @Test
    fun formatBytes_under_1_MB_shows_KB() {
        assertEquals("640 KB", SnippetSizeEstimator.formatBytes(640_000L))
    }

    @Test
    fun formatBytes_megabyte_range_shows_one_decimal() {
        assertEquals("3.4 MB", SnippetSizeEstimator.formatBytes(3_400_000L))
    }

    @Test
    fun formatBytes_over_100_MB_drops_decimal() {
        assertEquals("123 MB", SnippetSizeEstimator.formatBytes(123_000_000L))
    }

    @Test
    fun formatBytes_at_one_MB_boundary() {
        // Pin the KB/MB threshold — most likely point of accidental regression
        // if someone changes 1_000_000 to 1_048_576 (binary MB).
        assertEquals("1.0 MB", SnippetSizeEstimator.formatBytes(1_000_000L))
        assertEquals("999 KB", SnippetSizeEstimator.formatBytes(999_999L))
    }

    @Test
    fun formatBytes_rejects_negative_input() {
        val ex = kotlin.runCatching { SnippetSizeEstimator.formatBytes(-1L) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IAE, got ${ex?.javaClass}")
    }
}
