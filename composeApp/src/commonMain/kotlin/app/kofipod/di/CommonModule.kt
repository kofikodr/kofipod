// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.db.buildDatabase
import app.kofipod.data.net.buildHttpClient
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.RecentlyViewedRepository
import app.kofipod.data.repo.SearchRepository
import app.kofipod.data.repo.SearchSource
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.ui.screens.detail.PodcastDetailViewModel
import app.kofipod.ui.screens.downloads.DownloadsViewModel
import app.kofipod.ui.screens.library.LibraryDetailViewModel
import app.kofipod.ui.screens.library.LibraryViewModel
import app.kofipod.ui.screens.onboarding.OnboardingViewModel
import app.kofipod.ui.screens.player.PlayerViewModel
import app.kofipod.ui.screens.scheduler.SchedulerInfoViewModel
import app.kofipod.ui.screens.search.SearchViewModel
import app.kofipod.ui.screens.settings.SettingsViewModel
import app.kofipod.ui.screens.splash.SplashViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val commonDataModule =
    module {
        single { buildHttpClient() }
        single { PodcastIndexApi.create() }
        single { buildDatabase(get<DatabaseFactory>()) }
        single { LibraryRepository(get()) }
        single { RecentlyViewedRepository(get()) }
        single { SearchRepository(get()) }
        single<SearchSource> { get<SearchRepository>() }
        single { EpisodesRepository(get(), get()) }
        single<EpisodeSource> { get<EpisodesRepository>() }
        single { SettingsRepository(get()) }
        single { app.kofipod.data.repo.PlaybackRepository(get()) }
        single<CoroutineScope>(qualifier = org.koin.core.qualifier.named("appScope")) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        single {
            DownloadRepository(
                db = get(),
                engine = get(),
                scope = get(org.koin.core.qualifier.named("appScope")),
            )
        }

        viewModel { SearchViewModel(get()) }
        viewModel { LibraryViewModel(get()) }
        viewModel { (listId: String?) -> LibraryDetailViewModel(listId, get(), get(), get()) }
        viewModel { OnboardingViewModel(get(), get()) }
        viewModel { SplashViewModel(get()) }
        viewModel { SettingsViewModel(get(), get(), get()) }
        viewModel { DownloadsViewModel(get()) }
        viewModel { SchedulerInfoViewModel(get()) }
        viewModel { (podcastId: String) ->
            PodcastDetailViewModel(podcastId, get(), get(), get(), get(), get(), get(), get(), get())
        }
        viewModel { PlayerViewModel(get(), get(), get(), get(), get()) }
    }
