// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build

actual class ThemeSystem(private val context: Context) {
    actual fun apply(mode: KofipodThemeMode) {
        context.themePrefs().edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS = "kofipod_theme"
        private const val KEY_MODE = "theme_mode"
        private const val KEY_LAST_SYNCED = "theme_mode_last_synced"

        /**
         * Push the stored theme preference to the OS before any activity exists,
         * so the built-in splash resolves night-qualified resources on the next
         * cold start. Call once from [android.app.Application.onCreate].
         */
        fun applyPersistedToProcess(context: Context) {
            syncOsOverride(context, skipIfAlreadySynced = false)
        }

        /**
         * If the stored preference differs from the value we last pushed to the OS,
         * push it now. Intended for [android.app.Activity.onStop] so the Configuration
         * change that may follow happens while the activity isn't visible, avoiding
         * an in-session recreation flash.
         */
        fun syncPendingMode(context: Context) {
            syncOsOverride(context, skipIfAlreadySynced = true)
        }

        private fun syncOsOverride(
            context: Context,
            skipIfAlreadySynced: Boolean,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val prefs = context.themePrefs()
            val raw = prefs.getString(KEY_MODE, null) ?: return
            val mode = runCatching { KofipodThemeMode.valueOf(raw) }.getOrNull() ?: return
            if (skipIfAlreadySynced && prefs.getString(KEY_LAST_SYNCED, null) == mode.name) return
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
            ui.setApplicationNightMode(
                when (mode) {
                    KofipodThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                    KofipodThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                    KofipodThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
                },
            )
            prefs.edit().putString(KEY_LAST_SYNCED, mode.name).apply()
        }

        private fun Context.themePrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
