// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

/**
 * Prompt templates for the BYOK summary feature. Single function so prompt edits
 * are reviewable as a unit and so the test suite can assert the rendered string.
 *
 * Slice 2 ships only [episodeSummaryPrompt]; Slice 3 either swaps it for a
 * structured-output variant or layers a JSON-schema constraint on top.
 */
object AiPrompts {
    /**
     * Asks Gemini for a podcast-episode summary derived from a transcript whose
     * format we have not pre-parsed. The prompt explicitly tells the model that
     * the trailing user message may be VTT, SRT, JSON, or plain text and to
     * ignore the structural noise.
     *
     * @param localeTag BCP-47 tag (e.g. "en-US"). The model is told to write in
     *   the matching language; we do not translate the prompt itself, since
     *   Gemini handles instruction-following across languages well and a single
     *   stable English prompt is far easier to reason about and test.
     */
    fun episodeSummaryPrompt(localeTag: String): String =
        buildString {
            appendLine("You are summarising one episode of a podcast.")
            appendLine()
            appendLine("The user message that follows is the episode's transcript.")
            appendLine("It may be plain text, WebVTT, SRT, or JSON.")
            appendLine("Ignore cue numbers, timestamps, speaker labels, HTML tags,")
            appendLine("and any structural metadata. Read for meaning only.")
            appendLine()
            appendLine("Write a single, flowing summary of about 200 words.")
            appendLine("Plain prose, no headers, no bullet points, no preamble,")
            appendLine("no code fences, no closing remarks about the summary itself.")
            appendLine("Capture the central claim, the main supporting points,")
            appendLine("and any especially memorable beats — like a thoughtful")
            appendLine("listener describing the episode to a friend.")
            appendLine()
            append("Write the summary in the language matching this BCP-47 tag: ")
            append(localeTag)
            append('.')
        }
}
