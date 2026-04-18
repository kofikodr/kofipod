// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.nav

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
import app.kofipod.ui.screens.onboarding.OnboardingScreen
import app.kofipod.ui.screens.player.PlayerScreen
import app.kofipod.ui.screens.scheduler.SchedulerInfoScreen
import app.kofipod.ui.screens.search.SearchScreen
import app.kofipod.ui.screens.settings.SettingsScreen
import app.kofipod.ui.screens.splash.SplashScreen

@Composable
fun KofipodNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Route.Splash) {
        composable<Route.Splash> {
            SplashScreen(
                onContinue = { goOnboarding ->
                    navController.navigate(
                        if (goOnboarding) Route.Onboarding else Route.Search,
                        NavOptions.Builder().setPopUpTo(Route.Splash, inclusive = true).build(),
                    )
                },
            )
        }
        composable<Route.Onboarding> {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(
                        Route.Search,
                        NavOptions.Builder().setPopUpTo(Route.Onboarding, inclusive = true).build(),
                    )
                },
            )
        }
        composable<Route.Search> {
            SearchScreen(onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) })
        }
        composable<Route.Library> {
            LibraryScreen(
                onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
                onOpenList = { listId -> navController.navigate(Route.LibraryDetail(listId)) },
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
