// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

/**
 * Prompts for the multi-turn Discuss / Q&A surface. Kept separate from
 * [AiPrompts] (single-shot summary) so the system instruction, citation
 * rules, and per-turn copy stay reviewable as a unit and so each prompt
 * can be unit-tested in isolation.
 */
object DiscussPrompts {
    /**
     * System instruction sent on every turn. Frames the task, fixes the
     * grounding rule (transcript only — never invent), and pins the citation
     * format the response schema expects. Locale tuning is light: prose stays
     * in the user's language but quoted material from the transcript is left
     * verbatim.
     */
    fun systemPrompt(localeTag: String): String =
        buildString {
            appendLine("You are an assistant answering questions about ONE specific")
            appendLine("podcast episode. The first user message is the episode's full")
            appendLine("transcript (plain text, WebVTT, SRT, or JSON). Read it for")
            appendLine("meaning; ignore cue numbers, speaker labels, HTML tags, and")
            appendLine("any structural metadata.")
            appendLine()
            appendLine("Hard rules:")
            appendLine(" - Only answer from the transcript. If the answer isn't there,")
            appendLine("   say so plainly. Do NOT speculate, do NOT add outside knowledge.")
            appendLine(" - Be concise. One short paragraph by default; bullet points only")
            appendLine("   when the user explicitly asks for a list.")
            appendLine(" - Cite the timestamps the answer comes from. The response")
            appendLine("   schema has a `citations` array — fill it whenever a specific")
            appendLine("   moment in the transcript supports the claim. Use the cue's")
            appendLine("   start time, not the end. Format the `label` as M:SS or H:MM:SS")
            appendLine("   (no leading zero on the leftmost unit). Express `timestampMs`")
            appendLine("   as the same point in milliseconds.")
            appendLine(" - If the transcript has no timestamps, return an empty citations")
            appendLine("   array. Don't invent timestamps.")
            appendLine()
            append("Write your answer in the language matching this BCP-47 tag ")
            append("(quoted excerpts stay verbatim): ")
            append(localeTag)
            append('.')
        }

    /**
     * Wraps the transcript blob as the first user turn. The model treats this
     * as the source of truth for every subsequent question in the session.
     */
    fun transcriptTurn(transcript: String): String =
        buildString {
            appendLine("Transcript follows. Treat it as the only source for every")
            appendLine("question I ask in this conversation.")
            appendLine()
            append(transcript)
        }

    /** First synthetic model reply, paired with [transcriptTurn] to seed the conversation. */
    const val TRANSCRIPT_ACK = "Got it. Ask me anything about this episode."

    /**
     * Companion to [transcriptTurn] for the audio-fallback path. Sent as a
     * text [Part] alongside the `fileData` reference in the first user turn
     * so the model knows the attached blob IS the source for the chat, with
     * the same hard rules the system prompt repeats. We keep this short —
     * the system instruction already carries the grounding + citation rules,
     * and a long preamble would burn input tokens on every turn (history is
     * replayed in full each call).
     */
    const val AUDIO_CONTEXT_PREAMBLE =
        "The attached audio is this episode. Treat it as the only source for every question I ask in this conversation."

    /**
     * Static fallback suggestions used when no cached summary is available
     * yet — generic enough to apply to any episode while still surfacing the
     * shapes of question the feature handles well.
     */
    val GENERIC_SUGGESTIONS: List<String> =
        listOf(
            "Summarise the main argument",
            "Give me the three most memorable quotes",
            "List the people mentioned",
            "What did the hosts disagree on?",
        )

    /**
     * Quick-prompt chips below the suggestions — same six on every episode.
     * Tapping a chip submits the chip's text verbatim as the next user
     * message; the chip IS the question, no edit step.
     */
    val QUICK_PROMPTS: List<String> =
        listOf(
            "Summarise",
            "Best quotes",
            "List people",
            "List books",
            "Disagreements",
            "Key takeaways",
        )

    /**
     * Builds four episode-specific suggestions from a cached [AiSummary]. We
     * mine the people, things, and links lists rather than the prose — those
     * already pass the model's own "is this worth surfacing" filter, so they
     * make better question seeds than chopping up the summary string.
     *
     * Falls back to [GENERIC_SUGGESTIONS] when the summary is absent or thin
     * enough to produce fewer than four contextual suggestions.
     */
    fun suggestionsFromSummary(summary: AiSummary?): List<String> {
        if (summary == null) return GENERIC_SUGGESTIONS
        val out = mutableListOf<String>()
        // First person: usually the host or primary guest. "What did X push back on?"
        // mirrors the mock copy and reads as a more interesting prompt than "Who is X?".
        summary.people.firstOrNull()?.let { out += "What did ${it.name} push back on?" }
        // Second person if distinct: a "Who is X?" probe surfaces the model's
        // grasp of someone the user might not recognise.
        summary.people.getOrNull(1)?.let { out += "Who is ${it.name}, and why mentioned?" }
        // First thing: "Summarise the X tangent" — direct echo of the screen copy
        // and a question shape the model handles well from a transcript.
        summary.things.firstOrNull()?.let { out += "Summarise the ${it.name} tangent" }
        // Always-include closer. Uses the exact wording from
        // [GENERIC_SUGGESTIONS] so the padding dedupe ('g !in out') catches
        // it on a thin-summary path — otherwise we'd surface two visually
        // similar quote prompts in the same list.
        out += GENERIC_SUGGESTIONS[1] // "Give me the three most memorable quotes"
        // Pad with generics if we couldn't extract enough entities.
        if (out.size < TARGET_SUGGESTION_COUNT) {
            for (g in GENERIC_SUGGESTIONS) {
                if (out.size >= TARGET_SUGGESTION_COUNT) break
                if (g !in out) out += g
            }
        }
        return out.take(TARGET_SUGGESTION_COUNT)
    }

    private const val TARGET_SUGGESTION_COUNT = 4
}
