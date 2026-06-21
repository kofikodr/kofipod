// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Locks the recovery decision for a failed downloaded (`file://`) episode (issue #19):
 * when no streaming enclosure remains the player must NOTIFY the user rather than
 * silently stalling in STATE_ERROR; when one remains it must re-stream it.
 *
 * Note: the coroutine dispatch in `onPlayerError` (Dispatchers.Default for the DB +
 * filesystem work, Main for player mutations) is intentionally not covered here — the
 * listener is wired to a live ExoPlayer inside onCreate() with no injectable seam, and
 * the dispatch is unconditional (visually auditable in review). [fileRecoveryDecision]
 * is the only branching logic and is fully covered below. If the threading path becomes
 * a recurring regression target, extract a `handleFileError(...)` taking an injectable
 * scope + fake repositories and assert the dispatchers there.
 */
class PlaybackErrorRecoveryTest {
    @Test
    fun missingEpisode_nullEnclosure_notifiesInsteadOfStallingSilently() {
        // episodeNow(...) returned null → enclosureUrl is null.
        val decision = assertIs<PlaybackRecovery.Notify>(fileRecoveryDecision(null))
        assertEquals(PLAYBACK_SOURCE_UNAVAILABLE_MESSAGE, decision.message)
    }

    @Test
    fun blankEnclosure_notifiesInsteadOfStallingSilently() {
        val empty = assertIs<PlaybackRecovery.Notify>(fileRecoveryDecision(""))
        assertEquals(PLAYBACK_SOURCE_UNAVAILABLE_MESSAGE, empty.message)
        val whitespace = assertIs<PlaybackRecovery.Notify>(fileRecoveryDecision("   "))
        assertEquals(PLAYBACK_SOURCE_UNAVAILABLE_MESSAGE, whitespace.message)
    }

    @Test
    fun presentEnclosure_reStreamsFromThatUrl() {
        val decision = assertIs<PlaybackRecovery.Restream>(fileRecoveryDecision("https://host/ep.mp3"))
        assertEquals("https://host/ep.mp3", decision.streamUrl)
    }
}
