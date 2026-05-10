// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

// Domain types for the BYOK summary feature. Kept thin and free of any DB or HTTP
// shape — DTOs that mirror the wire format live in their respective callers.

/**
 * Visible step of the generate pipeline. Surfaced in [AiSummaryUiState.Generating]
 * so the panel can render the multi-row stage list (preparing → analysing →
 * formatting). The repository drives transitions; the UI labels each stage based
 * on [AiSourceKind] (e.g. "Uploading audio" vs "Fetching transcript").
 */
enum class GenerationStage { Preparing, Analysing, Formatting }

/**
 * Internal repository state for the in-flight pipeline. Pinned to a top-level
 * type so it can be carried across the `combine` boundary inside [AiSummaryRepository]
 * without leaking through the public [AiSummaryUiState] sealed interface.
 */
internal data class GenerationProgress(
    val stage: GenerationStage,
    val sizeBytes: Long?,
    /**
     * Cumulative bytes the Files API has confirmed for the in-flight chunked
     * upload. `null` when the pipeline isn't on the audio path, hasn't started
     * uploading yet, or is on a cache hit (no fresh upload in progress).
     * Reaches [sizeBytes] just before the stage flips to [GenerationStage.Analysing].
     */
    val uploadedBytes: Long? = null,
)

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

    /**
     * Request in flight. Source is locked at the moment generate() was called.
     *
     * @property stage which step of the pipeline is currently running. Drives the
     *   stages list in [GeneratingCard] — earlier stages render as "done", the
     *   current one as in-progress, later ones as pending.
     * @property sizeBytes byte count of the upload payload, surfaced next to the
     *   "Uploading audio" row. Audio path only — null for transcript (we don't
     *   know the body size up-front and a HEAD probe is more code than the cue
     *   is worth).
     */
    data class Generating(
        val sourceKind: AiSourceKind,
        val stage: GenerationStage = GenerationStage.Preparing,
        val sizeBytes: Long? = null,
        /**
         * Confirmed-uploaded byte count for an audio-path Generating state.
         * Surfaces a determinate progress bar on the "Uploading audio" row
         * when both this and [sizeBytes] are non-null. Null on transcript
         * runs, on cache hits, and during the first tick before any chunk
         * has landed (the row falls back to the size-only label).
         */
        val uploadedBytes: Long? = null,
    ) : AiSummaryUiState

    /**
     * Cached summary present. [stale] is true when the current best-available source's
     * fingerprint differs from the cached one — UI shows a "Source updated" hint above
     * the body and surfaces Regenerate.
     */
    data class Ready(val summary: AiSummary, val stale: Boolean) : AiSummaryUiState

    data class Error(val error: AiError) : AiSummaryUiState
}
