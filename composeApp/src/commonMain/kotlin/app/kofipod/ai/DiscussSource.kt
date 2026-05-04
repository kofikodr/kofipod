// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.kofipod.db.Download
import app.kofipod.db.Episode

/**
 * Strategy seam for resolving the textual / audio context the Discuss / Q&A
 * pipeline sends to Gemini for one episode. Two production implementations:
 * [TranscriptDiscussSource] (publisher transcript) and [AudioDiscussSource]
 * (downloaded audio file). The composite binding in `CommonModule` picks
 * per-episode — transcript wins when present, audio fallback when the
 * episode is downloaded.
 *
 * Returns a [DiscussLoad] sealed type rather than `kotlin.Result<T>` because
 * the latter is an inline value class and returning it across a `suspend`
 * `fun interface` triggers a Kotlin compiler bug that emits a bad cast in
 * the call site (manifested as `ClassCastException: kotlin.Result cannot be
 * cast to app.kofipod.ai.DiscussContext` in [DiscussRepository.runSend]).
 * The sealed-type indirection is one extra `when`-branch and immune to the
 * value-class boxing footgun.
 */
fun interface DiscussSource {
    suspend fun loadContext(
        episode: Episode,
        download: Download?,
    ): DiscussLoad
}

/** Outcome of [DiscussSource.loadContext]. See the KDoc on [DiscussSource] for why this is not `Result<DiscussContext>`. */
sealed interface DiscussLoad {
    data class Success(val context: DiscussContext) : DiscussLoad

    data class Failure(val error: Throwable) : DiscussLoad
}

/**
 * Output of [DiscussSource.loadContext]. Carries enough information for
 * [DiscussRepository] to either send the chat call directly (transcript) or
 * route through [AudioUploadCoordinator] first (audio). [NotAvailable] is the
 * explicit "this episode can't be discussed yet" signal that drives the
 * no-source UI state.
 *
 * The [Available] variant is retained for transcript callers as a stable
 * shape; [AudioReady] is the new audio variant added alongside it.
 */
sealed interface DiscussContext {
    /** Transcript text already in hand; can be sent to chat directly. */
    data class Available(
        val transcript: String,
        val fingerprint: String,
    ) : DiscussContext

    /**
     * Episode is downloaded; an audio file is on local disk and ready to be
     * uploaded (or reused from cache) by [AudioUploadCoordinator]. The repo
     * runs the upload, then sends the chat call with [ChatContext.Audio].
     *
     * [fingerprint] mirrors [Available.fingerprint]'s purpose — it lets the
     * coordinator's cache distinguish "same episode, same bytes" (reuse) from
     * "same episode, redownloaded" (fresh upload).
     */
    data class AudioReady(
        val localPath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val fingerprint: String,
    ) : DiscussContext

    data object NotAvailable : DiscussContext
}

/**
 * Phase-1 [DiscussSource] backed by [TranscriptFetcher]. Falls through to
 * [DiscussContext.NotAvailable] when the episode has no transcript URL —
 * the composite source then asks the audio sibling. Failure to fetch the
 * transcript URL surfaces as a [Result.failure] so the caller can map it
 * to [AiError.TranscriptUnavailable].
 */
class TranscriptDiscussSource(
    private val transcripts: TranscriptFetcher,
) : DiscussSource {
    override suspend fun loadContext(
        episode: Episode,
        download: Download?,
    ): DiscussLoad {
        val url =
            episode.transcriptUrl?.takeIf { it.isNotBlank() }
                ?: return DiscussLoad.Success(DiscussContext.NotAvailable)
        return transcripts.fetch(url).fold(
            onSuccess = { body ->
                DiscussLoad.Success(DiscussContext.Available(transcript = body, fingerprint = url))
            },
            onFailure = { DiscussLoad.Failure(it) },
        )
    }
}
