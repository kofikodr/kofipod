// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.kofipod.ui.screens.DownloadsScreen
import app.kofipod.ui.screens.OnboardingScreen
import app.kofipod.ui.screens.SchedulerInfoScreen
import app.kofipod.ui.screens.detail.PodcastDetailScreen
import app.kofipod.ui.screens.library.LibraryScreen
import app.kofipod.ui.screens.player.PlayerScreen
import app.kofipod.ui.screens.search.SearchScreen
import app.kofipod.ui.screens.settings.SettingsScreen

@Composable
fun KofipodNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Route.Search) {
        composable<Route.Onboarding> {
            OnboardingScreen(onContinue = { navController.navigate(Route.Search) })
        }
        composable<Route.Search> {
            SearchScreen(onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) })
        }
        composable<Route.Library> {
            LibraryScreen(onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) })
        }
        composable<Route.Downloads> { DownloadsScreen() }
        composable<Route.Settings> {
            SettingsScreen(onOpenScheduler = { navController.navigate(Route.SchedulerInfo) })
        }
        composable<Route.SchedulerInfo> {
            SchedulerInfoScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.PodcastDetail> { entry ->
            val detail = entry.toRoute<Route.PodcastDetail>()
            PodcastDetailScreen(podcastId = detail.podcastId, onBack = { navController.popBackStack() })
        }
        composable<Route.Player> { PlayerScreen(onBack = { navController.popBackStack() }) }
    }
}
