// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The Kofipod design calls for Plus Jakarta Sans (UI) and JetBrains Mono (metadata).
// We wire these as FontFamily.Default for now so the app compiles without font
// resources; swap in bundled TTFs under composeResources/font/ in a follow-up.
@Composable
fun plusJakartaSans(): FontFamily = FontFamily.Default

@Composable
fun jetBrainsMono(): FontFamily = FontFamily.Monospace

@Composable
fun kofipodTypography(): Typography {
    val sans = plusJakartaSans()
    return Typography(
        displayLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, letterSpacing = NEG_TIGHT),
        displayMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = NEG_TIGHTER),
        headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = NEG_TIGHTER),
        titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
        bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = WIDE_CTA),
    )
}

private val NEG_TIGHT: TextUnit = (-0.03).em
private val NEG_TIGHTER: TextUnit = (-0.02).em
private val WIDE_CTA: TextUnit = 0.08.em
