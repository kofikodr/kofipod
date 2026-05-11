// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import com.kofikodr.kofipod.ui.layout.TabletSize

/**
 * Outcome of tapping a search result row.
 *  - Phone (`null`) + tablet portraits: navigate to `Route.PodcastDetail` — there's no
 *    second pane that could host the detail.
 *  - Tablet landscapes: set the master-detail selection; the right pane embeds
 *    [com.kofikodr.kofipod.ui.screens.detail.PodcastDetailScreen] for the picked id.
 */
internal sealed class SearchResultTapAction {
    data class Navigate(val podcastId: String) : SearchResultTapAction()

    data class Select(val podcastId: String) : SearchResultTapAction()
}

internal fun routeSearchResultTap(
    size: TabletSize?,
    podcastId: String,
): SearchResultTapAction =
    when (size) {
        null,
        TabletSize.Tablet8Port,
        TabletSize.Tablet10Port,
        -> SearchResultTapAction.Navigate(podcastId)
        TabletSize.Tablet8Land,
        TabletSize.Tablet10Land,
        -> SearchResultTapAction.Select(podcastId)
    }
