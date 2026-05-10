// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

/**
 * Wire string [com.kofikodr.kofipod.data.repo.DownloadRepository] writes to the
 * `Download.state` column when a download finishes. Mirrors
 * [com.kofikodr.kofipod.downloads.DownloadProgress.State.Completed] downstream, but
 * we duplicate the literal here rather than depending on the downloads
 * package so the AI module's source-eligibility checks don't have to import
 * the engine. Keep in sync — a typo at any of the gate sites
 * ([AiSummaryRepository.pickSource], [DiscussRepository.observeFor],
 * [AudioDiscussSource.loadContext]) would silently disable the audio path.
 */
internal const val DOWNLOAD_STATE_COMPLETED = "Completed"

/** Default audio MIME when an episode's RSS enclosure doesn't declare one. */
internal const val DEFAULT_AUDIO_MIME = "audio/mpeg"
