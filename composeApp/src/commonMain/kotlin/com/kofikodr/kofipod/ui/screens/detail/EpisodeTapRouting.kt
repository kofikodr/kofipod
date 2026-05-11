// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import com.kofikodr.kofipod.ui.layout.TabletSize

/**
 * Outcome of tapping an episode row in the Podcast detail screen — either navigate to the
 * full Episode detail route or update the master-detail preview selection. Centralising
 * the decision here (vs. each call site rewiring its own callback) keeps Phase 8 Task 8.4
 * testable. Mirrors [com.kofikodr.kofipod.ui.screens.library.PodcastTapAction].
 */
internal sealed class EpisodeTapAction {
    data class Navigate(val episodeId: String) : EpisodeTapAction()

    data class Select(val episodeId: String) : EpisodeTapAction()
}

/**
 * Size-aware routing for episode-row taps on the Podcast detail screen:
 *  - Phone (`null` size) and tablet portraits navigate straight to the Episode detail
 *    route — there's no second pane that could host a preview.
 *  - Tablet landscapes preview-first: set the master-detail selection and let the
 *    detail pane's "Open" CTA commit to navigation as a separate gesture.
 */
internal fun routeEpisodeTap(
    size: TabletSize?,
    episodeId: String,
): EpisodeTapAction =
    when (size) {
        null,
        TabletSize.Tablet8Port,
        TabletSize.Tablet10Port,
        -> EpisodeTapAction.Navigate(episodeId)
        TabletSize.Tablet8Land,
        TabletSize.Tablet10Land,
        -> EpisodeTapAction.Select(episodeId)
    }
