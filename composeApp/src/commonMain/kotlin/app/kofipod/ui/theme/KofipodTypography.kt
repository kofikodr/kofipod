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
import kofipod.composeapp.generated.resources.Res
import kofipod.composeapp.generated.resources.jetbrains_mono_bold
import kofipod.composeapp.generated.resources.jetbrains_mono_medium
import kofipod.composeapp.generated.resources.jetbrains_mono_regular
import kofipod.composeapp.generated.resources.jetbrains_mono_semibold
import kofipod.composeapp.generated.resources.plus_jakarta_sans_variable
import org.jetbrains.compose.resources.Font

@Composable
fun plusJakartaSans(): FontFamily {
    val file = Res.font.plus_jakarta_sans_variable
    // Variable font: one TTF, Android's font system picks the right weight from the wght axis.
    return FontFamily(
        Font(file, FontWeight.Normal),
        Font(file, FontWeight.Medium),
        Font(file, FontWeight.SemiBold),
        Font(file, FontWeight.Bold),
        Font(file, FontWeight.ExtraBold),
    )
}

@Composable
fun jetBrainsMono(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(Res.font.jetbrains_mono_bold, FontWeight.Bold),
)

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
