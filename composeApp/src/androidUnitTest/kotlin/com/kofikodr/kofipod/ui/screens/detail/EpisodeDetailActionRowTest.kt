// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the action-row state machine. The Episode Detail screen reads three
 * independent state inputs (player, download, played) and produces a label
 * plus a visibility flag for the tertiary circle. The icon shown inside the
 * circle (Download / arc / Trash) is now driven entirely by
 * [com.kofikodr.kofipod.ui.primitives.DownloadButtonState]; this helper only
 * decides whether the slot renders at all.
 *
 * These cases were previously only exercised through Paparazzi snapshots —
 * which catch visual regressions but say nothing about the decision logic.
 * Behavioural pinning here is the cheap layer of defence: if someone reorders
 * the `when` branches the wrong way, this fires.
 */
class EpisodeDetailActionRowTest {
    // ---- playButtonLabel ----------------------------------------------------

    @Test
    fun playButtonLabel_isPlayEpisode_whenNotTheCurrentEpisode() {
        // Default state — opening any episode that isn't the one currently loaded
        // in the player. The label needs to read "Play episode" so the action is
        // unambiguous (vs. "Resume", which would imply a saved position).
        assertEquals(
            "Play episode",
            playButtonLabel(isPlayingThis = false, isCurrentEpisode = false),
        )
    }

    @Test
    fun playButtonLabel_isResume_whenCurrentButPaused() {
        // The user has the episode loaded but paused. Distinct from "Play episode"
        // because it signals continuity — same buffer, same position.
        assertEquals(
            "Resume",
            playButtonLabel(isPlayingThis = false, isCurrentEpisode = true),
        )
    }

    @Test
    fun playButtonLabel_isPause_whenCurrentAndPlaying() {
        assertEquals(
            "Pause",
            playButtonLabel(isPlayingThis = true, isCurrentEpisode = true),
        )
    }

    @Test
    fun playButtonLabel_prefersPause_overResume_evenIfFlagsCombineUnexpectedly() {
        // Defensive: isPlayingThis=true semantically implies isCurrentEpisode=true,
        // but if a future state-machine refactor breaks that invariant we want
        // "Pause" to still win — pausing must always be reachable from a playing
        // state, otherwise the user sees "Resume" on a playing episode.
        assertEquals(
            "Pause",
            playButtonLabel(isPlayingThis = true, isCurrentEpisode = false),
        )
    }

    // ---- showTertiaryDownload ----------------------------------------------

    @Test
    fun showTertiaryDownload_visible_whenDownloaded() {
        // A downloaded episode always shows the slot — the button renders the
        // Done state (Trash icon, tap-to-delete) regardless of whether a fresh
        // enclosure URL is still resolvable.
        assertTrue(showTertiaryDownload(downloaded = true, canDownload = true))
        assertTrue(showTertiaryDownload(downloaded = true, canDownload = false))
    }

    @Test
    fun showTertiaryDownload_visible_whenNotDownloadedAndCanDownload() {
        // Fresh enclosure available, nothing local yet — the button renders
        // Idle (Download icon, tap-to-fetch).
        assertTrue(showTertiaryDownload(downloaded = false, canDownload = true))
    }

    @Test
    fun showTertiaryDownload_hidden_whenNotDownloadedAndNoEnclosure() {
        // Not all feed items have an enclosure (e.g. text-only podcast posts or
        // podroll entries surfaced via the API). The screen hides the circle
        // entirely instead of showing a disabled "Download" button.
        assertFalse(showTertiaryDownload(downloaded = false, canDownload = false))
    }
}
