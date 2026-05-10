// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.ui.shell.AppShell
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import org.koin.compose.KoinContext
import org.koin.compose.koinInject

@Composable
fun App() {
    KoinContext {
        val settings = koinInject<SettingsRepository>()
        val mode by settings.themeMode().collectAsState(KofipodThemeMode.System)
        KofipodTheme(mode) {
            AppShell()
        }
    }
}
