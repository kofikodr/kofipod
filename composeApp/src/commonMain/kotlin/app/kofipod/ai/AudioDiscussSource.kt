// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.kofipod.db.Download
import app.kofipod.db.Episode

/**
 * [DiscussSource] backed by a downloaded audio file. Reports
 * [DiscussContext.AudioReady] when the episode has a completed download with
 * a non-blank local path; otherwise [DiscussContext.NotAvailable].
 *
 * Does **not** open the file or call the network. Upload responsibility lives
 * in [AudioUploadCoordinator] — keeping this class side-effect-free preserves
 * the strategy seam contract `DiscussSource` shares with [TranscriptDiscussSource].
 *
 * Phase 1 in `commonMain` is Android-only at runtime — `iosMain` does not
 * provide a real [openLocalFileChannel] yet. The composite source binding in
 * `CommonModule` doesn't gate on platform here because [DiscussRepository]'s
 * `runSend` only reaches the audio branch if the user has a downloaded
 * episode, and downloads are an Android-only feature today.
 */
class AudioDiscussSource : DiscussSource {
    override suspend fun loadContext(
        episode: Episode,
        download: Download?,
    ): Result<DiscussContext> {
        val ready =
            download != null &&
                download.state == DOWNLOAD_STATE_COMPLETED &&
                !download.localPath.isNullOrBlank()
        if (!ready) return Result.success(DiscussContext.NotAvailable)
        return Result.success(
            DiscussContext.AudioReady(
                localPath = download!!.localPath!!,
                mimeType = episode.enclosureMimeType.ifBlank { DEFAULT_AUDIO_MIME },
                sizeBytes = download.downloadedBytes,
                // Same convention AiSummaryRepository uses on the Audio path:
                // re-downloaded (re-encoded, repaired) files get a new byte
                // count, which invalidates the cached Files API URI.
                fingerprint = download.downloadedBytes.toString(),
            ),
        )
    }
}
