// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [DiscussPrompts] — the suggestion-derivation logic and the static
 * fallbacks the UI surfaces. The prompt strings themselves change less than
 * the entity-mining rules, so the assertions focus on shape (count, key
 * substitutions) rather than exact copy.
 */
class DiscussPromptsTest {
    @Test
    fun suggestionsFromSummary_nullSummary_returnsGenericFour() {
        val out = DiscussPrompts.suggestionsFromSummary(null)
        assertEquals(DiscussPrompts.GENERIC_SUGGESTIONS, out)
        assertEquals(4, out.size, "Generic fallback must always produce four suggestions")
    }

    @Test
    fun suggestionsFromSummary_richSummary_producesEpisodeSpecificPrompts() {
        val summary =
            sampleSummary(
                people = listOf(person("Toby Lin"), person("Wren Acosta")),
                things = listOf(thing("SQLite")),
            )

        val out = DiscussPrompts.suggestionsFromSummary(summary)

        assertEquals(4, out.size, "Suggestion count is part of the layout contract")
        assertTrue(
            out.any { "Toby Lin" in it },
            "First-person prompt must reference the lead-billed person — got $out",
        )
        assertTrue(
            out.any { "Wren Acosta" in it },
            "Second-person prompt must reference the second-billed person — got $out",
        )
        assertTrue(
            out.any { "SQLite" in it },
            "Topic prompt must reference the first 'things' entry — got $out",
        )
        assertTrue(
            out.any { "quote" in it.lowercase() },
            "Always-included quotes prompt must survive the trim — got $out",
        )
    }

    @Test
    fun suggestionsFromSummary_thinSummary_padsWithGenerics() {
        // A summary with one person and no things only produces two episode-
        // specific prompts; the rest must come from GENERIC_SUGGESTIONS to
        // keep the UI from rendering an awkwardly short row.
        val summary = sampleSummary(people = listOf(person("Alex")), things = emptyList())

        val out = DiscussPrompts.suggestionsFromSummary(summary)

        assertEquals(4, out.size)
        assertTrue(out.any { "Alex" in it }, "Episode-specific prompt must come first — got $out")
        val genericsIncluded = DiscussPrompts.GENERIC_SUGGESTIONS.count { it in out }
        assertTrue(genericsIncluded >= 1, "Padding must pull at least one generic — got $out")
    }

    @Test
    fun suggestionsFromSummary_doesNotDuplicate_whenGenericTextAlreadyPresent() {
        // The "best quotes" generic overlaps with the always-included
        // "Give me the three best quotes" closer. Padding must skip the
        // generic to avoid two visually-similar rows in the same list.
        val summary = sampleSummary(people = emptyList(), things = emptyList())

        val out = DiscussPrompts.suggestionsFromSummary(summary)

        val quotesPrompts = out.filter { "quote" in it.lowercase() }
        assertEquals(1, quotesPrompts.size, "Only one quotes-style prompt should appear — got $out")
    }

    @Test
    fun quickPrompts_isAlwaysSix() {
        // The chip row layout assumes six chips; a regression to five or
        // seven would either leave a gap or wrap an extra row. Pin the
        // count so the LayoutManager never sees a surprise.
        assertEquals(6, DiscussPrompts.QUICK_PROMPTS.size)
    }

    @Test
    fun systemPrompt_carriesLocaleTag_andCitationRule() {
        val prompt = DiscussPrompts.systemPrompt("pt-BR")

        assertTrue("pt-BR" in prompt, "Locale tag must be embedded so the model writes in the user's language")
        assertTrue("citations" in prompt, "Citation rule must be in the system prompt — that's the response-schema contract")
        assertTrue("transcript" in prompt.lowercase(), "Grounding rule must reference the transcript — no outside knowledge")
    }

    private fun sampleSummary(
        people: List<MentionedPerson>,
        things: List<MentionedThing>,
    ): AiSummary =
        AiSummary(
            episodeId = "ep1",
            generatedAtMs = 0L,
            modelId = GeminiModel.Flash.apiId,
            sourceKind = AiSourceKind.Transcript,
            sourceFingerprint = "https://example.com/t.vtt",
            summary = "Doesn't matter for these tests.",
            people = people,
            things = things,
        )

    private fun person(name: String): MentionedPerson = MentionedPerson(name = name, subtitle = "")

    private fun thing(name: String): MentionedThing = MentionedThing(name = name, subtitle = "")
}
