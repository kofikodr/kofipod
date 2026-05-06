package app.kofipod.snippets

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
        val plain = "This is a long monolithic transcript with no timing cues. ".repeat(10)
        val sliced = TranscriptSlicer.sliceForWindow(plain, startMs = 0L, endMs = 60_000L)
        assertEquals(true, (sliced?.length ?: 0) <= 200)
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
}
