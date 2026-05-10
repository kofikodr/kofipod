// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

/**
 * Per-feature seam that [SnippetCaptionRepository] depends on. Production
 * wiring (CommonModule, Task 16 DI step) provides an adapter that delegates
 * to [com.kofikodr.kofipod.data.repo.DownloadRepository],
 * [com.kofikodr.kofipod.ai.AudioUploadCoordinator], [com.kofikodr.kofipod.ai.GeminiClient],
 * and [com.kofikodr.kofipod.ai.AiConfigRepository]. Tests fake [CaptionDeps] directly
 * — none of those four production classes are interfaces, so a small seam
 * is the only way to keep this repo unit-testable without MockK / DB driver.
 */
interface CaptionDeps {
    /** True iff the episode's audio is on local disk (i.e. fully downloaded). */
    suspend fun isAudioReadyFor(episodeId: String): Boolean

    /** The user's Gemini API key, or null when disconnected / not configured. */
    suspend fun currentGeminiKey(): String?

    /**
     * One-shot upload-then-transcribe. The implementation:
     *   1. resolves the API key (returns failure if missing),
     *   2. uses [com.kofikodr.kofipod.ai.AudioUploadCoordinator.acquire] to upload-or-cache
     *      the episode audio to Gemini Files API,
     *   3. calls [com.kofikodr.kofipod.ai.GeminiClient.generateFromAudio] with [prompt].
     *
     * Returns the transcribed text on success, a failure on any pipeline error.
     * The repository does not need to inspect *which* step failed —
     * `Result.failure` collapses all of them into [CaptionResolution.None]
     * with reason [CaptionResolution.NoneReason.GeminiFailed].
     */
    suspend fun transcribeForCaption(
        episodeId: String,
        prompt: String,
    ): Result<String>
}
