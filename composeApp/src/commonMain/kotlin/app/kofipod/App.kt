// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.kofipod.ui.theme.KofipodTheme
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun App() {
    KofipodTheme {
        val c = LocalKofipodColors.current
        Box(
            modifier = Modifier.fillMaxSize().background(c.bg),
            contentAlignment = Alignment.Center,
        ) {
            Text("Kofipod", color = c.text)
        }
    }
}
