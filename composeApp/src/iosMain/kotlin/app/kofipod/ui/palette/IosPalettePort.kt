// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.palette

import androidx.compose.ui.graphics.Color

/** iOS has no AndroidX Palette equivalent and is secondary; tile backgrounds always fall back to the seeded gradient. */
class IosPalettePort : PalettePort {
    override suspend fun extract(model: Any?): Pair<Color, Color>? = null
}
