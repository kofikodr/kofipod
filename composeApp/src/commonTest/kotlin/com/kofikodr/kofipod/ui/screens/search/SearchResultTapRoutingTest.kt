// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import com.kofikodr.kofipod.ui.layout.TabletSize
import kotlin.test.Test
import kotlin.test.assertEquals

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
            SearchResultTapAction.Navigate("p8-port"),
            routeSearchResultTap(size = TabletSize.Tablet8Port, podcastId = "p8-port"),
        )
    }

    @Test
    fun tablet_10_portrait_navigates() {
        assertEquals(
            SearchResultTapAction.Navigate("p10-port"),
            routeSearchResultTap(size = TabletSize.Tablet10Port, podcastId = "p10-port"),
        )
    }

    @Test
    fun tablet_8_landscape_selects() {
        assertEquals(
            SearchResultTapAction.Select("abc-123"),
            routeSearchResultTap(size = TabletSize.Tablet8Land, podcastId = "abc-123"),
        )
    }

    @Test
    fun tablet_10_landscape_selects() {
        assertEquals(
            SearchResultTapAction.Select("xyz-999"),
            routeSearchResultTap(size = TabletSize.Tablet10Land, podcastId = "xyz-999"),
        )
    }
}
