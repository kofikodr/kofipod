// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import kotlinx.serialization.Serializable

/**
 * Structured shape Gemini returns when we set
 * `generationConfig.responseMimeType = "application/json"` plus the matching
 * [DISCUSS_RESPONSE_SCHEMA].
 *
 * `answer` is the prose reply. `citations` is a (possibly empty) list of
 * timestamped pointers into the transcript so the UI can render tappable
 * spans that seek the player.
 *
 * Defaults are wide so a partial response (e.g. an answer with no citations)
 * decodes cleanly rather than failing the whole chat turn — same convention
 * as [AiSummaryJson].
 */
@Serializable
data class DiscussAnswerJson(
    val answer: String = "",
    val citations: List<CitationJson> = emptyList(),
)

/**
 * Single timestamped citation. `label` is the short pretty form Gemini emits
 * (e.g. "12:34") and is shown verbatim in the UI; `timestampMs` is the same
 * point in the audio expressed in milliseconds so the citation tap can call
 * `player.seekTo(...)` without re-parsing the label.
 *
 * Both fields are required — a citation with no timestamp is useless and the
 * panel filter drops the whole entry rather than try to fall back.
 */
@Serializable
data class CitationJson(
    val label: String,
    val timestampMs: Long,
)

/**
 * Schema descriptor sent to Gemini alongside the prompt. Mirrors
 * [SUMMARY_RESPONSE_SCHEMA]'s pattern: `propertyOrdering` for stable output,
 * `required` for the hard contract. `citations` is required (as an array)
 * but each entry's fields are required so an emitted citation is always
 * complete or absent — no half-populated rows to filter.
 */
internal val DISCUSS_RESPONSE_SCHEMA: Schema =
    Schema(
        type = "OBJECT",
        properties =
            mapOf(
                "answer" to Schema(type = "STRING"),
                "citations" to
                    Schema(
                        type = "ARRAY",
                        items =
                            Schema(
                                type = "OBJECT",
                                properties =
                                    mapOf(
                                        "label" to Schema(type = "STRING"),
                                        "timestampMs" to Schema(type = "INTEGER"),
                                    ),
                                required = listOf("label", "timestampMs"),
                                propertyOrdering = listOf("label", "timestampMs"),
                            ),
                    ),
            ),
        required = listOf("answer", "citations"),
        propertyOrdering = listOf("answer", "citations"),
    )

/**
 * One historical turn we replay back to Gemini. Maps to a single `Content`
 * with the wire `role` field set to "user" or "model". Built from the
 * persisted [com.kofikodr.kofipod.db.DiscussMessage] rows by the repository.
 */
data class DiscussTurn(
    val role: DiscussRole,
    val text: String,
)

/**
 * Wire roles Gemini's REST API accepts. The string values are exact — the
 * model rejects anything else with a 400. Persisted as the wire string in
 * `DiscussMessage.role` so a future cross-platform consumer (e.g. the iOS
 * app once wired) can read the same column without translation.
 */
enum class DiscussRole(val wire: String) {
    User("user"),
    Model("model"),
    ;

    companion object {
        fun fromWire(value: String): DiscussRole? = entries.firstOrNull { it.wire == value }
    }
}
