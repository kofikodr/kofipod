// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.ui.shell.AppShell
import app.kofipod.ui.theme.KofipodTheme
import app.kofipod.ui.theme.KofipodThemeMode
import org.koin.compose.KoinContext
import org.koin.compose.koinInject

@Composable
fun App() {
    KoinContext {
        val settings = koinInject<SettingsRepository>()
        val mode by settings.themeMode().collectAsState(KofipodThemeMode.System)
        val startOnboarding = remember { !settings.onboardedNow() }
        KofipodTheme(mode) {
            AppShell(startOnboarding = startOnboarding)
        }
    }
}
