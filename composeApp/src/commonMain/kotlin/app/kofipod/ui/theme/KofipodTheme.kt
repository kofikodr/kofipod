// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalKofipodColors = staticCompositionLocalOf { LightKofipodColors }
val LocalKofipodRadii = staticCompositionLocalOf { DefaultKofipodRadii }

enum class KofipodThemeMode { System, Light, Dark }

@Composable
fun KofipodTheme(
    mode: KofipodThemeMode = KofipodThemeMode.System,
    content: @Composable () -> Unit,
) {
    val isDark = when (mode) {
        KofipodThemeMode.System -> isSystemInDarkTheme()
        KofipodThemeMode.Light -> false
        KofipodThemeMode.Dark -> true
    }
    val colors = if (isDark) DarkKofipodColors else LightKofipodColors
    val materialScheme = if (isDark) {
        darkColorScheme(
            primary = colors.purple,
            onPrimary = colors.text,
            secondary = colors.pink,
            onSecondary = colors.text,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.purple,
            onPrimary = colors.surface,
            secondary = colors.pink,
            onSecondary = colors.surface,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(
        LocalKofipodColors provides colors,
        LocalKofipodRadii provides DefaultKofipodRadii,
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = kofipodTypography(),
            content = content,
        )
    }
}
