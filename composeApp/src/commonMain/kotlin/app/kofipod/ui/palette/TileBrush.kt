// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.kofipod.db.Podcast
import app.kofipod.ui.primitives.artGradient
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.koinInject

/** Result returned by [rememberTileVisuals] — pair of brush + whether colors came from real cover art. */
data class TileVisuals(val brush: Brush, val sampled: Boolean)

/**
 * Returns a gradient brush for a Library tile background.
 *
 * - Empty members → seeded gradient (matches the existing decorative artwork).
 * - 1 cover → that cover's vibrant + dark-muted swatches.
 * - 2+ covers → first two covers' palettes blended (4 stops, top-left → bottom-right).
 *
 * Extraction happens in a [PaletteCache] so the same podcast doesn't get re-processed
 * on recomposition or scroll. While extraction is pending, the seeded gradient is shown
 * and replaced once the suspend resolves — Compose recomposes via `produceState`.
 */
@Composable
fun rememberTileVisuals(
    members: List<Podcast>,
    fallbackSeed: Int,
): TileVisuals {
    val isDark = LocalKofipodColors.current.isDark
    val cache: PaletteCache = koinInject()

    val keys = members.take(2).map { it.id }
    val palettes by produceState(
        initialValue = emptyList<Pair<Color, Color>>(),
        keys,
        cache,
    ) {
        value = members.take(2).mapNotNull { cache.resolve(it) }
    }

    return remember(palettes, fallbackSeed, isDark) {
        if (palettes.isEmpty()) {
            val (a, b) = artGradient(fallbackSeed * 7 + 3, isDark)
            TileVisuals(brush = Brush.linearGradient(listOf(a, b)), sampled = false)
        } else {
            val stops = palettes.flatMap { listOf(it.first, it.second) }
            TileVisuals(brush = Brush.linearGradient(stops), sampled = true)
        }
    }
}
