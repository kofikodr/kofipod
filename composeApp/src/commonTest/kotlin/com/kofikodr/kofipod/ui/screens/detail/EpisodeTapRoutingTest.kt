// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import com.kofikodr.kofipod.ui.layout.TabletSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks Phase 8 Task 8.4's episode-tap routing decision exhaustively, one assertion per
 * [TabletSize] value (plus the phone `null` case). If a new TabletSize is added, the
 * helper's `when` becomes non-exhaustive and these tests need updating in lockstep —
 * that's the intentional design.
 */
class EpisodeTapRoutingTest {
    @Test
    fun phone_null_size_navigates() {
        assertEquals(
            EpisodeTapAction.Navigate("e1"),
            routeEpisodeTap(size = null, episodeId = "e1"),
        )
    }

    @Test
    fun tablet_8_portrait_navigates() {
        assertEquals(
            EpisodeTapAction.Navigate("e1"),
            routeEpisodeTap(size = TabletSize.Tablet8Port, episodeId = "e1"),
        )
    }

    @Test
    fun tablet_10_portrait_navigates() {
        assertEquals(
            EpisodeTapAction.Navigate("e1"),
            routeEpisodeTap(size = TabletSize.Tablet10Port, episodeId = "e1"),
        )
    }

    @Test
    fun tablet_8_landscape_selects_for_master_detail_preview() {
        assertEquals(
            EpisodeTapAction.Select("e1"),
            routeEpisodeTap(size = TabletSize.Tablet8Land, episodeId = "e1"),
        )
    }

    @Test
    fun tablet_10_landscape_selects_for_master_detail_preview() {
        assertEquals(
            EpisodeTapAction.Select("e1"),
            routeEpisodeTap(size = TabletSize.Tablet10Land, episodeId = "e1"),
        )
    }
}
