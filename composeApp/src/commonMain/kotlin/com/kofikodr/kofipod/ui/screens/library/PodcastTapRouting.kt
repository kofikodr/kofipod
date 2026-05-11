// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.library

import com.kofikodr.kofipod.ui.layout.TabletSize

/**
 * Outcome of tapping a podcast tile in the Library — either commit to navigation or
 * just update the master-detail selection. Centralising the decision here (vs. each
 * call site rewiring its own callback) keeps Task 2.4 of the tablet plan testable.
 */
internal sealed class PodcastTapAction {
    data class Navigate(val podcastId: String) : PodcastTapAction()

    data class Select(val podcastId: String) : PodcastTapAction()
}

/**
 * Size-aware routing for Library tile taps:
 *  - Phone (`null` size) and tablet portraits navigate straight to the podcast detail
 *    route — there's no second pane that could host a preview.
 *  - Tablet landscapes preview-first: set the master-detail selection and let the
 *    detail pane's "Open" CTA commit to navigation as a separate gesture.
 */
internal fun routeLibraryPodcastTap(
    size: TabletSize?,
    podcastId: String,
): PodcastTapAction =
    when (size) {
        null,
        TabletSize.Tablet8Port,
        TabletSize.Tablet10Port,
        -> PodcastTapAction.Navigate(podcastId)
        TabletSize.Tablet8Land,
        TabletSize.Tablet10Land,
        -> PodcastTapAction.Select(podcastId)
    }
