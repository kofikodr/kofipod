// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class KofipodRadii(
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 28.dp,
    val pill: Dp = 999.dp,
)

val DefaultKofipodRadii = KofipodRadii()
