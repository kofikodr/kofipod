// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.palette

import androidx.compose.ui.graphics.Color

/**
 * Platform port that extracts a 2-color gradient pair from an image source.
 *
 * Android implementation uses Coil's cache + AndroidX Palette. iOS returns null
 * (caller falls back to seeded gradient). Returning null for transient failures is
 * cached per-podcast at the [PaletteCache] level so we don't retry forever.
 */
interface PalettePort {
    suspend fun extract(model: Any?): Pair<Color, Color>?
}
