// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Search : Route

    @Serializable data object Library : Route

    @Serializable data object Downloads : Route

    @Serializable data object Settings : Route

    @Serializable data object SchedulerInfo : Route

    @Serializable data object StarterPack : Route

    @Serializable data class PodcastDetail(val podcastId: String) : Route

    @Serializable data class EpisodeDetail(val episodeId: String) : Route

    @Serializable data class LibraryDetail(val listId: String? = null) : Route

    @Serializable data object Player : Route

    @Serializable data object Stats : Route

    @Serializable data object AiSetup : Route

    @Serializable data object PodcastIndexSetup : Route

    @Serializable data class AskGemini(val episodeId: String) : Route

    @Serializable data object Bookmarks : Route

    @Serializable data object LibrarySearch : Route

    @Serializable data class SnippetEditor(val snippetId: String) : Route

    @Serializable data object Connections : Route

    @Serializable data class SmartPlaylistEditor(
        val playlistId: String? = null,
        val initialName: String? = null,
    ) : Route

    @Serializable data class SmartPlaylistDetail(val playlistId: String) : Route
}
