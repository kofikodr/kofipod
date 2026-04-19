// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build

actual class ThemeSystem(private val context: Context) {
    actual fun apply(mode: KofipodThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
        ui.setApplicationNightMode(
            when (mode) {
                KofipodThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                KofipodThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                KofipodThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }
}
