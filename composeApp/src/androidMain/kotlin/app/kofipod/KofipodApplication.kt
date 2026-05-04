// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.app.Application
import app.kofipod.ai.AiSummaryRepository
import app.kofipod.di.androidPlatformModule
import app.kofipod.di.commonDataModule
import app.kofipod.di.flavorPlatformModule
import app.kofipod.pro.ProEntitlementRepository
import app.kofipod.ui.theme.ThemeSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.get

class KofipodApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeSystem.applyPersistedToProcess(this)
        startKoin {
            androidLogger()
            androidContext(this@KofipodApplication)
            modules(commonDataModule, androidPlatformModule, flavorPlatformModule)
        }
        // Recover any AI summary requests that were mid-flight when the previous
        // process died. Worker is the primary backstop while the app is killed,
        // but we also kick on every cold start so a worker that was throttled
        // (or never reached the OS scheduler) doesn't leave the marker stuck.
        get<AiSummaryRepository>(AiSummaryRepository::class.java).resumePendingAsync()
        // Kick Pro entitlement reconciliation eagerly. Repository hydrates from cache first,
        // then refreshes from Play Billing — so paywall-gated UI sees the right tier within
        // a few hundred ms of process start. Failure here is non-fatal (UI shows Unknown
        // until the user retries via Settings).
        val appScope = GlobalContext.get().get<CoroutineScope>(named("appScope"))
        appScope.launch {
            GlobalContext.get().get<ProEntitlementRepository>().refreshOnStart()
        }
    }
}
