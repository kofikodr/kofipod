// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.nav

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
import app.kofipod.ui.screens.detail.PodcastDetailScreen
import app.kofipod.ui.screens.downloads.DownloadsScreen
import app.kofipod.ui.screens.library.LibraryDetailScreen
import app.kofipod.ui.screens.library.LibraryScreen
import app.kofipod.ui.screens.library.StarterPackScreen
import app.kofipod.ui.screens.player.PlayerScreen
import app.kofipod.ui.screens.scheduler.SchedulerInfoScreen
import app.kofipod.ui.screens.search.SearchScreen
import app.kofipod.ui.screens.settings.SettingsScreen

@Composable
fun KofipodNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Route.Library) {
        composable<Route.Search> {
            SearchScreen(onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) })
        }
        composable<Route.Library> {
            LibraryScreen(
                onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
                onOpenList = { listId -> navController.navigate(Route.LibraryDetail(listId)) },
                onOpenSearch = { navController.navigate(Route.Search) },
                onOpenStarterPack = { navController.navigate(Route.StarterPack) },
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
                onOpenPlayer = {
                    navController.navigate(
                        Route.Player,
                        NavOptions.Builder().setLaunchSingleTop(true).build(),
                    )
                },
            )
        }
        composable<Route.Settings> {
            SettingsScreen(onOpenScheduler = { navController.navigate(Route.SchedulerInfo) })
        }
        composable<Route.SchedulerInfo> {
            SchedulerInfoScreen(onBack = { navController.popBackStack() })
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
            )
        }
    }
}
