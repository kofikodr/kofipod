package com.kofikodr.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptSlicerTest {
    @Test
    fun webvtt_picks_cue_nearest_start() {
        val vtt =
            """
            WEBVTT

            00:00:10.000 --> 00:00:15.000
            First line spoken.

            00:01:30.000 --> 00:01:35.000
            The bazel adoption inflection point.

            00:03:00.000 --> 00:03:05.000
            Closing thoughts.
            """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(vtt, startMs = 90_000L, endMs = 95_000L)
        assertEquals("The bazel adoption inflection point.", sliced)
    }

    @Test
    fun srt_picks_cue_nearest_start() {
        val srt =
            """
            1
            00:00:10,000 --> 00:00:15,000
            First line spoken.

            2
            00:01:30,000 --> 00:01:35,000
            The bazel adoption inflection point.
            """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(srt, startMs = 90_000L, endMs = 95_000L)
        assertEquals("The bazel adoption inflection point.", sliced)
    }

    @Test
    fun plain_text_returns_first_n_chars() {
        // Pin the exact prefix. The prior assertion `(sliced?.length ?: 0) <= 200`
        // would pass for an empty string, the wrong section of the transcript,
        // or any truncation — none of which exercise the actual contract.
        // PLAIN_TEXT_LIMIT = 200; trim() runs first; ifBlank { null } guards
        // an empty input. The fallback ignores the start/end window and
        // returns the prefix of the trimmed transcript.
        val plain = "This is a long monolithic transcript with no timing cues. ".repeat(10)
        val sliced = TranscriptSlicer.sliceForWindow(plain, startMs = 0L, endMs = 60_000L)
        assertEquals(plain.trim().take(200), sliced)
    }

    @Test
    fun plain_text_shorter_than_limit_returnsFullTrimmedTranscript() {
        // No unnecessary truncation when the input is below the cap. Pinning
        // both sides of the take() boundary: a regression that truncated to
        // a hardcoded len < 200 would fail here even though the length-only
        // assertion would still pass.
        val plain = "  Short plain transcript with leading and trailing whitespace.  "
        val sliced = TranscriptSlicer.sliceForWindow(plain, startMs = 0L, endMs = 60_000L)
        assertEquals("Short plain transcript with leading and trailing whitespace.", sliced)
    }

    @Test
    fun empty_input_returns_null() {
        assertNull(TranscriptSlicer.sliceForWindow("", 0L, 1_000L))
        assertNull(TranscriptSlicer.sliceForWindow("   \n  ", 0L, 1_000L))
    }

    @Test
    fun no_cue_in_window_falls_back_to_nearest() {
        val vtt =
            """
            WEBVTT

            00:00:10.000 --> 00:00:15.000
            Only cue.
            """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(vtt, startMs = 60_000L, endMs = 70_000L)
        assertEquals("Only cue.", sliced)
    }

    @Test
    fun webvtt_with_positioning_flags_still_parses() {
        // YouTube auto-captions and some podcast hosts emit cue headers like
        // "00:01:30.000 --> 00:01:35.000 line:50% position:50%". The slicer
        // must accept these or every such file regresses to plain-text mode.
        val vtt =
            """
            WEBVTT

            00:00:10.000 --> 00:00:15.000 line:50% position:50%
            Cue with positioning flags.

            00:01:30.000 --> 00:01:35.000 align:start
            Bazel inflection point.
            """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(vtt, startMs = 90_000L, endMs = 95_000L)
        assertEquals("Bazel inflection point.", sliced)
    }

    @Test
    fun note_block_does_not_falsely_match_embedded_timestamp() {
        // The NOTE block contains a timestamp-shaped substring at 00:00:10.000.
        // We query exactly that window. Under correct `matchEntire` regex
        // semantics (line-anchored), the NOTE line cannot match — the slicer
        // sees zero cues at 10s and falls back to nearest-to-startMs, which
        // is the real cue at 90s. If a future refactor switched to `find`,
        // the embedded timestamps would be parsed as a cue at 10s and the
        // text following the cue ("Spurious comment line.") would be returned
        // instead, failing this assertion.
        val vtt =
            """
            WEBVTT

            NOTE
            See 00:00:10.000 --> 00:00:15.000 in the original draft
            Spurious comment line.

            00:01:30.000 --> 00:01:35.000
            Real cue.
            """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(vtt, startMs = 10_000L, endMs = 15_000L)
        assertEquals("Real cue.", sliced)
    }
}
