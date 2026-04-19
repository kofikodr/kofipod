// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun SystemBarAppearance(
    isDark: Boolean,
    barColor: Color,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as Activity).window
        val argb = barColor.toArgb()
        window.statusBarColor = argb
        window.navigationBarColor = argb
        val insets = WindowCompat.getInsetsController(window, view)
        insets.isAppearanceLightStatusBars = !isDark
        insets.isAppearanceLightNavigationBars = !isDark
    }
}
