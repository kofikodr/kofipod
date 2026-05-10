// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

/**
 * Live progress payload for the **first** audio-backed Discuss send in a
 * session — the only chat turn that's allowed to take ~30s of upload time.
 * Subsequent turns reuse the cached Files API URI and use the regular
 * [DiscussUiState.Ready.inFlight] spinner instead.
 *
 * Mirrors [GenerationProgress]'s shape but kept as a separate type so a
 * future audio-only stage (e.g. "Transcribing locally") doesn't pollute the
 * summary pipeline's stage enum.
 */
data class DiscussProgress(
    val stage: DiscussProgressStage,
    val sizeBytes: Long? = null,
)

enum class DiscussProgressStage {
    /** PUTting bytes to the Files API resumable upload URL. */
    Uploading,

    /** Polling the Files API until the file flips from PROCESSING → ACTIVE. */
    Analysing,
}

/** Bridge from a Summary-side [GenerationStage] to its Discuss equivalent. */
internal fun GenerationStage.toDiscussProgressStage(): DiscussProgressStage =
    when (this) {
        GenerationStage.Preparing -> DiscussProgressStage.Uploading
        GenerationStage.Analysing -> DiscussProgressStage.Analysing
        // Formatting is a Summary-only stage (parsing the JSON envelope).
        // Discuss's analogue is just "waiting for chat" which the in-flight
        // spinner already covers, so collapse onto Analysing.
        GenerationStage.Formatting -> DiscussProgressStage.Analysing
    }
