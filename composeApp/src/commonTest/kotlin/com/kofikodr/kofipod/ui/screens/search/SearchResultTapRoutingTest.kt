// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import com.kofikodr.kofipod.ui.layout.TabletSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks Task 3.4's result-tap routing decision exhaustively, one assertion per
 * [TabletSize] value (plus the phone `null` case). If a new TabletSize is added,
 * the helper's `when` becomes non-exhaustive and these tests need updating in
 * lockstep — that's the intentional design.
 */
class SearchResultTapRoutingTest {
    @Test
    fun phone_null_size_navigates() {
        assertEquals(
            SearchResultTapAction.Navigate("p1"),
            routeSearchResultTap(size = null, podcastId = "p1"),
        )
    }

    @Test
    fun tablet_8_portrait_navigates() {
        assertEquals(
            SearchResultTapAction.Navigate("p1"),
            routeSearchResultTap(size = TabletSize.Tablet8Port, podcastId = "p1"),
        )
    }

    @Test
    fun tablet_10_portrait_navigates() {
        assertEquals(
            SearchResultTapAction.Navigate("p1"),
            routeSearchResultTap(size = TabletSize.Tablet10Port, podcastId = "p1"),
        )
    }

    @Test
    fun tablet_8_landscape_selects_for_master_detail_preview() {
        assertEquals(
            SearchResultTapAction.Select("p1"),
            routeSearchResultTap(size = TabletSize.Tablet8Land, podcastId = "p1"),
        )
    }

    @Test
    fun tablet_10_landscape_selects_for_master_detail_preview() {
        assertEquals(
            SearchResultTapAction.Select("p1"),
            routeSearchResultTap(size = TabletSize.Tablet10Land, podcastId = "p1"),
        )
    }
}
