// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

/**
 * Applies the app's theme preference to the OS so the system splash screen
 * (drawn before app code runs) picks the matching light/dark resources on the
 * next process start. Android-only behavior; other platforms are no-ops.
 */
expect class ThemeSystem {
    fun apply(mode: KofipodThemeMode)
}
