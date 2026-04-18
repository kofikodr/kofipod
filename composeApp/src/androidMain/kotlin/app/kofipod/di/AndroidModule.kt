// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.data.db.DatabaseFactory
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidPlatformModule = module {
    single { DatabaseFactory(androidContext()) }
}
