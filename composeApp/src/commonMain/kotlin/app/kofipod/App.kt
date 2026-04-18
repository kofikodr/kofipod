// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import androidx.compose.runtime.Composable
import app.kofipod.ui.shell.AppShell
import app.kofipod.ui.theme.KofipodTheme
import org.koin.compose.KoinContext

@Composable
fun App() {
    KoinContext {
        KofipodTheme {
            AppShell()
        }
    }
}
