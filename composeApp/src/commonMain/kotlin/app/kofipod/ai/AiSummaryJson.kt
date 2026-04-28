// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import kotlinx.serialization.Serializable

/**
 * Structured shape Gemini returns when we set
 * `generationConfig.responseMimeType = "application/json"` plus the matching
 * [SUMMARY_RESPONSE_SCHEMA]. The four keys mirror the four panel sections:
 * the prose summary, two flat name lists (people, things), and a list of
 * label/url pairs.
 *
 * Fields default to empty so a partial response (e.g. an episode that mentions
 * no books) parses cleanly rather than failing the whole pipeline.
 */
@Serializable
data class AiSummaryJson(
    val summary: String = "",
    val people: List<String> = emptyList(),
    val things: List<String> = emptyList(),
    val links: List<MentionedLinkJson> = emptyList(),
)

@Serializable
data class MentionedLinkJson(
    val label: String,
    val url: String,
)

/**
 * Gemini's `responseSchema` is an OpenAPI-3-style schema descriptor. We send it
 * alongside the prompt so the model returns a JSON object whose shape matches
 * [AiSummaryJson] without us having to defend against free-form prose.
 *
 * `propertyOrdering` is honoured by Gemini and stabilises the output keys —
 * useful when comparing fixtures across releases. `required` is the hard
 * contract; without it the model is free to omit keys.
 */
internal val SUMMARY_RESPONSE_SCHEMA: Schema =
    Schema(
        type = "OBJECT",
        properties =
            mapOf(
                "summary" to Schema(type = "STRING"),
                "people" to Schema(type = "ARRAY", items = Schema(type = "STRING")),
                "things" to Schema(type = "ARRAY", items = Schema(type = "STRING")),
                "links" to
                    Schema(
                        type = "ARRAY",
                        items =
                            Schema(
                                type = "OBJECT",
                                properties =
                                    mapOf(
                                        "label" to Schema(type = "STRING"),
                                        "url" to Schema(type = "STRING"),
                                    ),
                                required = listOf("label", "url"),
                                propertyOrdering = listOf("label", "url"),
                            ),
                    ),
            ),
        required = listOf("summary", "people", "things", "links"),
        propertyOrdering = listOf("summary", "people", "things", "links"),
    )

/**
 * Recursive Gemini response-schema node. Only the fields we actually use are
 * modelled — Gemini accepts more (description, format, enum, …) but we want
 * the wire payload tight. `null` fields are dropped at serialisation time
 * (kofipodJson defaults `encodeDefaults = false`).
 */
@Serializable
internal data class Schema(
    val type: String,
    val properties: Map<String, Schema>? = null,
    val items: Schema? = null,
    val required: List<String>? = null,
    val propertyOrdering: List<String>? = null,
)
