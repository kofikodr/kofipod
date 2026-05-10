// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
expect fun SystemBarAppearance(
    isDark: Boolean,
    barColor: Color,
)
