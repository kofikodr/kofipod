// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.palette

import androidx.compose.ui.graphics.Color
import app.kofipod.db.Podcast
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide memoization layer in front of [PalettePort]. Keyed by [Podcast.id] —
 * the artwork URL is treated as stable per podcast for the lifetime of a row, so we
 * don't re-extract on recomposition or scroll.
 *
 * A null result is cached as "tried" so a failed extraction doesn't get retried in
 * a tight Compose loop.
 */
class PaletteCache(private val port: PalettePort) {
    private val mutex = Mutex()
    private val resolved = mutableMapOf<String, Pair<Color, Color>>()
    private val tried = mutableSetOf<String>()

    suspend fun resolve(podcast: Podcast): Pair<Color, Color>? {
        mutex.withLock {
            resolved[podcast.id]?.let { return it }
            if (podcast.id in tried) return null
        }
        val model =
            podcast.artworkUrl.ifBlank { null } ?: run {
                mutex.withLock { tried += podcast.id }
                return null
            }
        val pair = runCatching { port.extract(model) }.getOrNull()
        mutex.withLock {
            tried += podcast.id
            if (pair != null) resolved[podcast.id] = pair
        }
        return pair
    }
}
