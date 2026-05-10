// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the load-bearing parts of the summary prompt. We intentionally do NOT
 * snapshot the entire string — small wording tweaks for clarity shouldn't break
 * the build — but the format-agnostic instruction and the language directive are
 * load-bearing: dropping either silently degrades summaries for VTT-only feeds or
 * non-English locales.
 */
class AiPromptsTest {
    @Test
    fun summaryPrompt_carriesFormatAgnosticInstruction() {
        val prompt = AiPrompts.episodeSummaryPrompt("en-US")

        assertTrue(
            "WebVTT" in prompt && "SRT" in prompt && "JSON" in prompt && "plain text" in prompt,
            "Prompt must enumerate the transcript formats Gemini may receive — without this, " +
                "VTT/SRT cue numbers leak into summaries. Got:\n$prompt",
        )
        assertTrue(
            "Ignore" in prompt || "ignore" in prompt,
            "Prompt must explicitly tell the model to ignore structural metadata. Got:\n$prompt",
        )
    }

    @Test
    fun summaryPrompt_includesLocaleTag_verbatim() {
        val prompt = AiPrompts.episodeSummaryPrompt("ja-JP")

        assertTrue(
            "ja-JP" in prompt,
            "Locale tag must appear verbatim in the prompt — Gemini follows it literally. Got:\n$prompt",
        )
    }

    @Test
    fun summaryPrompt_forbidsHeadersAndCodeFences() {
        // The Ready state renders the body as plain text; if the model emits
        // markdown headers or ``` fences we'll show raw syntax to the user.
        val prompt = AiPrompts.episodeSummaryPrompt("en-US")

        assertTrue(
            "no headers" in prompt,
            "Prompt must forbid headers — got:\n$prompt",
        )
        assertTrue(
            "no code fences" in prompt,
            "Prompt must forbid code fences — got:\n$prompt",
        )
    }
}
