// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.app.Application
import app.kofipod.di.androidPlatformModule
import app.kofipod.di.commonDataModule
import app.kofipod.ui.theme.ThemeSystem
import app.kofipod.update.UpdateInstaller
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get

class KofipodApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeSystem.applyPersistedToProcess(this)
        startKoin {
            androidLogger()
            androidContext(this@KofipodApplication)
            modules(commonDataModule, androidPlatformModule)
        }
        // The downloaded-APK pointer rides Auto Backup but the file itself doesn't —
        // clear it on cold start if the file isn't where the pointer says it is, so a
        // restored device doesn't crash on "Install".
        get<UpdateInstaller>(UpdateInstaller::class.java).reconcileDownloadedApk()
    }
}
