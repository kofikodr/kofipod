// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
actual fun SystemBarAppearance(isDark: Boolean, barColor: Color) {
    // iOS manages status bar via UIViewController; no-op for now.
}
