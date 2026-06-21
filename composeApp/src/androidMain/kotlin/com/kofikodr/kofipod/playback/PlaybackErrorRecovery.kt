// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback

/** Outcome of trying to recover a failed `file://` playback by falling back to streaming. */
internal sealed interface PlaybackRecovery {
    /** A streaming fallback exists; re-point the media item at [streamUrl]. */
    data class Restream(val streamUrl: String) : PlaybackRecovery

    /** No usable source remains; show [message] instead of stalling silently. */
    data class Notify(val message: String) : PlaybackRecovery
}

/** Shown when a downloaded file is gone AND no streaming enclosure remains. */
internal const val PLAYBACK_SOURCE_UNAVAILABLE_MESSAGE = "Couldn't play episode. Audio source unavailable."

/**
 * Decide how to recover when a downloaded `file://` episode fails to open. A missing
 * episode row or a blank enclosure URL both surface here as a null/blank [enclosureUrl],
 * which means there is no streaming fallback — return [PlaybackRecovery.Notify] so the
 * user is told, mirroring the streaming-failure branch, instead of the player stalling
 * in STATE_ERROR with no visible error (issue #19). Otherwise re-stream the enclosure.
 */
internal fun fileRecoveryDecision(enclosureUrl: String?): PlaybackRecovery =
    if (enclosureUrl.isNullOrBlank()) {
        PlaybackRecovery.Notify(PLAYBACK_SOURCE_UNAVAILABLE_MESSAGE)
    } else {
        PlaybackRecovery.Restream(enclosureUrl)
    }
