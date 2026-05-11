// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import com.kofikodr.kofipod.ui.layout.TabletSize

/**
 * Outcome of tapping a search result row — either commit to navigation or just update
 * the master-detail selection. Mirrors [com.kofikodr.kofipod.ui.screens.library.PodcastTapAction]
 * from Library Task 2.4; centralising the decision here keeps Search Task 3.4 testable
 * and the call sites free of size-aware branching.
 */
internal sealed class SearchResultTapAction {
    data class Navigate(val podcastId: String) : SearchResultTapAction()

    data class Select(val podcastId: String) : SearchResultTapAction()
}

/**
 * Size-aware routing for Search result taps:
 *  - Phone (`null` size) and tablet portraits navigate straight to the podcast detail
 *    route — there's no second pane that could host a preview.
 *  - Tablet landscapes preview-first: set the master-detail selection and let the
 *    detail pane's "Latest" CTA commit to navigation as a separate gesture.
 */
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
