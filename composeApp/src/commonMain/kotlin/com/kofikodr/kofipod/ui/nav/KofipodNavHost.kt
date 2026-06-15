// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.kofikodr.kofipod.ui.screens.askgemini.AskGeminiScreen
import com.kofikodr.kofipod.ui.screens.bookmarks.BookmarksScreen
import com.kofikodr.kofipod.ui.screens.connections.ConnectionsScreen
import com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailScreen
import com.kofikodr.kofipod.ui.screens.detail.PodcastDetailScreen
import com.kofikodr.kofipod.ui.screens.downloads.DownloadsScreen
import com.kofikodr.kofipod.ui.screens.library.LibraryDetailScreen
import com.kofikodr.kofipod.ui.screens.library.LibraryScreen
import com.kofikodr.kofipod.ui.screens.library.StarterPackScreen
import com.kofikodr.kofipod.ui.screens.player.PlayerScreen
import com.kofikodr.kofipod.ui.screens.playlists.SmartPlaylistDetailScreen
import com.kofikodr.kofipod.ui.screens.playlists.SmartPlaylistEditorScreen
import com.kofikodr.kofipod.ui.screens.scheduler.SchedulerInfoScreen
import com.kofikodr.kofipod.ui.screens.search.LibrarySearchScreen
import com.kofikodr.kofipod.ui.screens.search.SearchScreen
import com.kofikodr.kofipod.ui.screens.settings.SettingsScreen
import com.kofikodr.kofipod.ui.screens.settings.ai.AiSetupScreen
import com.kofikodr.kofipod.ui.screens.settings.podcastindex.PodcastIndexSetupScreen
import com.kofikodr.kofipod.ui.screens.snippet.SnippetEditorScreen
import com.kofikodr.kofipod.ui.screens.stats.StatsScreen

@Composable
fun KofipodNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Route.Library) {
        composable<Route.Search> {
            SearchScreen(
                onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
                onOpenPlayer = {
                    navController.navigate(
                        Route.Player,
                        NavOptions.Builder().setLaunchSingleTop(true).build(),
                    )
                },
                onOpenEpisode = { episodeId -> navController.navigate(Route.EpisodeDetail(episodeId)) },
            )
        }
        composable<Route.Library> {
            LibraryScreen(
                onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
                onOpenList = { listId -> navController.navigate(Route.LibraryDetail(listId)) },
                onOpenSearch = { navController.navigate(Route.Search) },
                onOpenStarterPack = { navController.navigate(Route.StarterPack) },
                onOpenBookmarks = { navController.navigate(Route.Bookmarks) },
                onOpenStats = { navController.navigate(Route.Stats) },
                onOpenLibrarySearch = { navController.navigate(Route.LibrarySearch) },
                onOpenSmartPlaylistEditor = { id, initialName ->
                    navController.navigate(
                        Route.SmartPlaylistEditor(playlistId = id, initialName = initialName),
                    )
                },
                onOpenSmartPlaylistDetail = { id ->
                    navController.navigate(Route.SmartPlaylistDetail(playlistId = id))
                },
            )
        }
        composable<Route.Bookmarks> {
            BookmarksScreen(
                onBack = { navController.popBackStack() },
                onOpenPlayer = {
                    navController.navigate(
                        Route.Player,
                        NavOptions.Builder().setLaunchSingleTop(true).build(),
                    )
                },
            )
        }
        composable<Route.LibrarySearch> {
            LibrarySearchScreen(
                onBack = { navController.popBackStack() },
                onOpenEpisode = { episodeId ->
                    navController.navigate(Route.EpisodeDetail(episodeId))
                },
                onSeekBookmark = { episodeId, _ ->
                    // Mirror BookmarksScreen's tap-to-seek wiring: navigate to the
                    // episode detail. The actual seek-on-resume is handled by
                    // the existing BookmarksViewModel.openAt path; for search,
                    // we route through episode detail because the search result
                    // doesn't carry the same VM context. If a follow-up slice
                    // adds shared seek-and-play helpers, prefer those.
                    navController.navigate(Route.EpisodeDetail(episodeId))
                },
            )
        }
        composable<Route.Stats> {
            StatsScreen(
                onBack = { navController.popBackStack() },
                onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
            )
        }
        composable<Route.StarterPack> {
            StarterPackScreen(
                onBack = { navController.popBackStack() },
                onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
            )
        }
        composable<Route.LibraryDetail> { entry ->
            val detail = entry.toRoute<Route.LibraryDetail>()
            LibraryDetailScreen(
                listId = detail.listId,
                onBack = { navController.popBackStack() },
                onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
            )
        }
        composable<Route.Downloads> {
            DownloadsScreen(
                onOpenEpisode = { episodeId ->
                    navController.navigate(Route.EpisodeDetail(episodeId))
                },
            )
        }
        composable<Route.Settings> {
            SettingsScreen(
                onOpenScheduler = { navController.navigate(Route.SchedulerInfo) },
                onOpenAiSetup = { navController.navigate(Route.AiSetup) },
                onOpenPodcastIndexSetup = { navController.navigate(Route.PodcastIndexSetup) },
                onOpenConnections = { navController.navigate(Route.Connections) },
            )
        }
        composable<Route.SchedulerInfo> {
            SchedulerInfoScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.AiSetup> {
            AiSetupScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.PodcastIndexSetup> {
            PodcastIndexSetupScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.PodcastDetail> { entry ->
            val detail = entry.toRoute<Route.PodcastDetail>()
            PodcastDetailScreen(
                podcastId = detail.podcastId,
                onBack = { navController.popBackStack() },
                onOpenPlayer = {
                    navController.navigate(
                        Route.Player,
                        NavOptions.Builder().setLaunchSingleTop(true).build(),
                    )
                },
                onOpenEpisode = { episodeId ->
                    navController.navigate(Route.EpisodeDetail(episodeId))
                },
                onOpenAiSetup = { navController.navigate(Route.AiSetup) },
                onOpenAskGemini = { episodeId -> navController.navigate(Route.AskGemini(episodeId)) },
                onOpenSnippetEditor = { id ->
                    navController.navigate(Route.SnippetEditor(snippetId = id))
                },
            )
        }
        composable<Route.EpisodeDetail> { entry ->
            val detail = entry.toRoute<Route.EpisodeDetail>()
            EpisodeDetailScreen(
                episodeId = detail.episodeId,
                onBack = { navController.popBackStack() },
                onOpenPlayer = {
                    navController.navigate(
                        Route.Player,
                        NavOptions.Builder().setLaunchSingleTop(true).build(),
                    )
                },
                onOpenAiSetup = { navController.navigate(Route.AiSetup) },
                onOpenAskGemini = { episodeId -> navController.navigate(Route.AskGemini(episodeId)) },
                onOpenSnippetEditor = { id ->
                    navController.navigate(Route.SnippetEditor(snippetId = id))
                },
            )
        }
        composable<Route.AskGemini> { entry ->
            val ask = entry.toRoute<Route.AskGemini>()
            AskGeminiScreen(
                episodeId = ask.episodeId,
                onBack = { navController.popBackStack() },
                onOpenPlayer = {
                    navController.navigate(
                        Route.Player,
                        NavOptions.Builder().setLaunchSingleTop(true).build(),
                    )
                },
            )
        }
        composable<Route.Player>(
            enterTransition = {
                slideInVertically(animationSpec = tween(300)) { it }
            },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = {
                slideOutVertically(animationSpec = tween(300)) { it }
            },
        ) {
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenPodcast = { id ->
                    navController.popBackStack()
                    navController.navigate(Route.PodcastDetail(id))
                },
                onOpenSnippetEditor = { id ->
                    navController.navigate(Route.SnippetEditor(snippetId = id))
                },
            )
        }
        composable<Route.SnippetEditor> { entry ->
            val args = entry.toRoute<Route.SnippetEditor>()
            SnippetEditorScreen(
                snippetId = args.snippetId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.Connections> {
            ConnectionsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.SmartPlaylistEditor> { entry ->
            val args = entry.toRoute<Route.SmartPlaylistEditor>()
            SmartPlaylistEditorScreen(
                playlistId = args.playlistId,
                initialName = args.initialName,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.SmartPlaylistDetail> { entry ->
            val args = entry.toRoute<Route.SmartPlaylistDetail>()
            SmartPlaylistDetailScreen(
                playlistId = args.playlistId,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Route.SmartPlaylistEditor(playlistId = id)) },
                onOpenEpisode = { id -> navController.navigate(Route.EpisodeDetail(id)) },
            )
        }
    }
}
