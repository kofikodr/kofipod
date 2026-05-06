// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetCaptionPickerTest {
    private val picker = SnippetCaptionPicker()

    @Test
    fun transcript_url_present_picks_transcript_path() {
        val p =
            picker.pick(
                transcriptUrl = "https://x.com/transcript.vtt",
                isAudioDownloaded = true,
                hasGeminiKey = true,
            )
        assertEquals(SnippetCaptionPicker.Path.Transcript, p)
    }

    @Test
    fun no_transcript_but_audio_downloaded_and_key_picks_gemini() {
        val p =
            picker.pick(
                transcriptUrl = null,
                isAudioDownloaded = true,
                hasGeminiKey = true,
            )
        assertEquals(SnippetCaptionPicker.Path.Gemini, p)
    }

    @Test
    fun no_transcript_no_audio_picks_none() {
        val p =
            picker.pick(
                transcriptUrl = null,
                isAudioDownloaded = false,
                hasGeminiKey = true,
            )
        assertEquals(SnippetCaptionPicker.Path.None, p)
    }

    @Test
    fun no_transcript_audio_downloaded_but_no_key_picks_none() {
        val p =
            picker.pick(
                transcriptUrl = null,
                isAudioDownloaded = true,
                hasGeminiKey = false,
            )
        assertEquals(SnippetCaptionPicker.Path.None, p)
    }

    @Test
    fun blank_transcript_url_treated_as_missing() {
        val p =
            picker.pick(
                transcriptUrl = "   ",
                isAudioDownloaded = true,
                hasGeminiKey = true,
            )
        assertEquals(SnippetCaptionPicker.Path.Gemini, p)
    }
}
