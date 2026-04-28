// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

/**
 * Prompt templates for the BYOK summary feature. Single function so prompt edits
 * are reviewable as a unit and so the test suite can assert the rendered string.
 *
 * Slice 3 layers entity extraction onto the summary task: the model is asked to
 * return a JSON object with `summary`, `people`, `things`, and `links` fields.
 * The matching `responseMimeType` + `responseSchema` (see [SUMMARY_RESPONSE_SCHEMA])
 * is what actually pins the shape; the prompt copy clarifies *what* belongs in
 * each list so the model doesn't dump every name from the transcript into
 * `people`.
 */
object AiPrompts {
    /**
     * Asks Gemini for an episode summary plus three short entity lists. The
     * trailing user message is the transcript (or the audio file via the Files
     * API path); both arrive after the prompt in the wire `contents`.
     *
     * @param localeTag BCP-47 tag (e.g. "en-US"). The model is told to write
     *   `summary` and `things` labels in the matching language, but proper
     *   nouns (people names, URLs) are kept verbatim — translating a guest's
     *   name is a worse result than the cost of one mixed-language string.
     */
    fun episodeSummaryPrompt(localeTag: String): String =
        buildString {
            appendLine("You are summarising one episode of a podcast.")
            appendLine()
            appendLine("The user message that follows is the episode's content")
            appendLine("— either a transcript (plain text, WebVTT, SRT, or JSON)")
            appendLine("or the audio file itself. If it's a transcript, ignore")
            appendLine("cue numbers, timestamps, speaker labels, HTML tags, and")
            appendLine("any structural metadata. Read for meaning only.")
            appendLine()
            appendLine("Return a JSON object with exactly these four fields:")
            appendLine()
            appendLine("  summary: a single flowing paragraph of about 200 words.")
            appendLine("    Plain prose, no headers, no bullet points, no preamble,")
            appendLine("    no closing remarks about the summary itself. Capture")
            appendLine("    the central claim, the main supporting points, and any")
            appendLine("    especially memorable beats — like a thoughtful")
            appendLine("    listener describing the episode to a friend.")
            appendLine()
            appendLine("  people: an array of {name, subtitle} objects for named")
            appendLine("    individuals discussed (hosts, guests, public figures")
            appendLine("    referenced by name). Keep names verbatim. The subtitle")
            appendLine("    is a short role/affiliation hint when one is clear from")
            appendLine("    the source — e.g. \"Host\", \"Guest · Modular\",")
            appendLine("    \"Referenced\". Leave subtitle as an empty string when")
            appendLine("    no clean signal is available; do not invent affiliations.")
            appendLine("    Skip the array if no one is named.")
            appendLine()
            appendLine("  things: an array of {name, subtitle} objects for specific")
            appendLine("    works, products, or topics mentioned by name — books,")
            appendLine("    papers, films, software, languages, companies, place")
            appendLine("    names. Keep names verbatim. Subtitle is a short kind or")
            appendLine("    year hint when obvious — e.g. \"Book · 2014\",")
            appendLine("    \"Language\", \"SQLite extension\". Leave subtitle empty")
            appendLine("    when unclear; do not guess. Skip generic concepts. Skip")
            appendLine("    the array if nothing is mentioned.")
            appendLine()
            appendLine("  links: an array of {label, url} pairs for any URLs the")
            appendLine("    speakers reference verbatim. Use the page title or a")
            appendLine("    short description as the label. Do NOT invent URLs;")
            appendLine("    only include ones that actually appear in the source.")
            appendLine("    Skip if none are mentioned.")
            appendLine()
            appendLine("Respond with JSON only, no prose, no code fences.")
            appendLine()
            append("Write the summary and any descriptive labels in the language ")
            append("matching this BCP-47 tag (proper nouns and URLs stay verbatim): ")
            append(localeTag)
            append('.')
        }
}
