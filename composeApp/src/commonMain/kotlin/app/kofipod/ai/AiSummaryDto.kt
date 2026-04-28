// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

// Domain types for the BYOK summary feature. Kept thin and free of any DB or HTTP
// shape — DTOs that mirror the wire format live in their respective callers.

/**
 * Which input drove a cached summary. Persisted as the `wire` string so a future
 * v3 (e.g. on-device transcription) can extend the enum without rewriting rows.
 */
enum class AiSourceKind(val wire: String) {
    Transcript("transcript"),
    Audio("audio"),
    ;

    companion object {
        fun fromWire(value: String): AiSourceKind? = entries.firstOrNull { it.wire == value }
    }
}

data class AiSummary(
    val episodeId: String,
    val generatedAtMs: Long,
    val modelId: String,
    val sourceKind: AiSourceKind,
    val sourceFingerprint: String,
    val summary: String,
    val people: List<MentionedPerson> = emptyList(),
    val things: List<MentionedThing> = emptyList(),
    val links: List<MentionedLink> = emptyList(),
)

/**
 * A named individual mentioned in the episode — a host, guest, or referenced
 * figure. [subtitle] is a short role/affiliation hint ("Host", "Guest · Modular",
 * "Referenced") that the Mentioned tab renders in an accent colour next to the
 * name. Blank subtitle → name renders alone, no extra spacing. Domain-only
 * type; the wire shape is [MentionedPersonJson].
 */
data class MentionedPerson(
    val name: String,
    val subtitle: String = "",
)

/**
 * A specific work, product, or topic referenced by name (book, paper, film,
 * software, company, place). [subtitle] is a short kind/year/disambiguation
 * hint ("Book · 2014", "Language", "SQLite extension") shown next to the name.
 */
data class MentionedThing(
    val name: String,
    val subtitle: String = "",
)

data class MentionedLink(
    val label: String,
    val url: String,
)

/**
 * What the Summary tab renders. Authored as a single closed hierarchy so the
 * panel composable is one `when` over a sealed shape — no booleans dangling
 * across siblings, no "loading and ready at the same time" race.
 */
sealed interface AiSummaryUiState {
    /** No Gemini key configured. The Summary tab is hidden by the parent. */
    data object Hidden : AiSummaryUiState

    /**
     * Key configured but no cached summary for this episode.
     *
     * @property available which input the repo would use if [AiSummaryRepository.generate]
     *   were invoked now. `null` means neither transcript nor (for Slice 2.5) a downloaded
     *   audio file is available — the panel offers no Generate button in that branch.
     */
    data class Idle(val available: AiSourceKind?) : AiSummaryUiState

    /** Request in flight. Source is locked at the moment generate() was called. */
    data class Generating(val sourceKind: AiSourceKind) : AiSummaryUiState

    /**
     * Cached summary present. [stale] is true when the current best-available source's
     * fingerprint differs from the cached one — UI shows a "Source updated" hint above
     * the body and surfaces Regenerate.
     */
    data class Ready(val summary: AiSummary, val stale: Boolean) : AiSummaryUiState

    data class Error(val error: AiError) : AiSummaryUiState
}
