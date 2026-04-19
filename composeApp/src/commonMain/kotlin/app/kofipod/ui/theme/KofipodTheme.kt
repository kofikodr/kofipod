// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalKofipodColors = compositionLocalOf { LightKofipodColors }
val LocalKofipodRadii = staticCompositionLocalOf { DefaultKofipodRadii }

enum class KofipodThemeMode { System, Light, Dark }

private const val THEME_TRANSITION_MS = 500

@Composable
fun KofipodTheme(
    mode: KofipodThemeMode = KofipodThemeMode.System,
    content: @Composable () -> Unit,
) {
    val isDark =
        when (mode) {
            KofipodThemeMode.System -> isSystemInDarkTheme()
            KofipodThemeMode.Light -> false
            KofipodThemeMode.Dark -> true
        }
    val target = if (isDark) DarkKofipodColors else LightKofipodColors
    val colors = rememberAnimatedKofipodColors(target)

    val materialScheme =
        if (isDark) {
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
    SystemBarAppearance(isDark = colors.isDark, barColor = colors.bg)
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

@Composable
private fun rememberAnimatedKofipodColors(target: KofipodColors): KofipodColors {
    val transition = updateTransition(target, label = "KofipodColors")
    val bg by transition.animateThemeColor("bg") { it.bg }
    val bgSubtle by transition.animateThemeColor("bgSubtle") { it.bgSubtle }
    val surface by transition.animateThemeColor("surface") { it.surface }
    val surfaceAlt by transition.animateThemeColor("surfaceAlt") { it.surfaceAlt }
    val border by transition.animateThemeColor("border") { it.border }
    val borderStrong by transition.animateThemeColor("borderStrong") { it.borderStrong }
    val text by transition.animateThemeColor("text") { it.text }
    val textSoft by transition.animateThemeColor("textSoft") { it.textSoft }
    val textMute by transition.animateThemeColor("textMute") { it.textMute }
    val purple by transition.animateThemeColor("purple") { it.purple }
    val purpleDeep by transition.animateThemeColor("purpleDeep") { it.purpleDeep }
    val purpleSoft by transition.animateThemeColor("purpleSoft") { it.purpleSoft }
    val purpleTint by transition.animateThemeColor("purpleTint") { it.purpleTint }
    val pink by transition.animateThemeColor("pink") { it.pink }
    val pinkSoft by transition.animateThemeColor("pinkSoft") { it.pinkSoft }
    val success by transition.animateThemeColor("success") { it.success }
    val warn by transition.animateThemeColor("warn") { it.warn }
    val danger by transition.animateThemeColor("danger") { it.danger }
    return KofipodColors(
        bg = bg,
        bgSubtle = bgSubtle,
        surface = surface,
        surfaceAlt = surfaceAlt,
        border = border,
        borderStrong = borderStrong,
        text = text,
        textSoft = textSoft,
        textMute = textMute,
        purple = purple,
        purpleDeep = purpleDeep,
        purpleSoft = purpleSoft,
        purpleTint = purpleTint,
        pink = pink,
        pinkSoft = pinkSoft,
        success = success,
        warn = warn,
        danger = danger,
        isDark = target.isDark,
    )
}

@Composable
private fun Transition<KofipodColors>.animateThemeColor(
    label: String,
    selector: @Composable (KofipodColors) -> Color,
) = animateColor(
    transitionSpec = { themeColorSpec() },
    label = label,
    targetValueByState = selector,
)

private fun themeColorSpec(): FiniteAnimationSpec<Color> = tween(durationMillis = THEME_TRANSITION_MS, easing = LinearOutSlowInEasing)
