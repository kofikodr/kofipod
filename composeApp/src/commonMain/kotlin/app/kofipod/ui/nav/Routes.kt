// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Search : Route

    @Serializable data object Library : Route

    @Serializable data object Downloads : Route

    @Serializable data object Settings : Route

    @Serializable data object SchedulerInfo : Route

    @Serializable data object StarterPack : Route

    @Serializable data class PodcastDetail(val podcastId: String) : Route

    @Serializable data class LibraryDetail(val listId: String? = null) : Route

    @Serializable data object Player : Route

    @Serializable data object Stats : Route
}
