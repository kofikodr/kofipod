// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.db.buildDatabase
import app.kofipod.data.net.buildHttpClient
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.SearchRepository
import app.kofipod.data.repo.SettingsRepository
import org.koin.dsl.module

val commonDataModule = module {
    single { buildHttpClient() }
    single { PodcastIndexApi.create() }
    single { buildDatabase(get<DatabaseFactory>()) }
    single { LibraryRepository(get()) }
    single { SearchRepository(get()) }
    single { EpisodesRepository(get(), get()) }
    single { SettingsRepository(get()) }
}
