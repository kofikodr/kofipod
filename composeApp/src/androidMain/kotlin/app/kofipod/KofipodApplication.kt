// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.app.Application
import app.kofipod.di.androidPlatformModule
import app.kofipod.di.commonDataModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KofipodApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@KofipodApplication)
            modules(commonDataModule, androidPlatformModule)
        }
    }
}
