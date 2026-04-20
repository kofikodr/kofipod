// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kofipod.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

actual class PlaybackCache(context: Context, capBytes: Long) {
    // Media3 enforces one SimpleCache instance per directory per process. The service and any
    // debug probes must share this singleton — the Koin `single { }` binding guarantees that.
    internal val cache: SimpleCache =
        SimpleCache(
            File(context.cacheDir, "media").apply { mkdirs() },
            LeastRecentlyUsedCacheEvictor(capBytes),
            StandaloneDatabaseProvider(context),
        )

    actual fun sizeBytes(): Long = cache.cacheSpace

    actual fun clear() {
        cache.keys.toList().forEach { key ->
            cache.getCachedSpans(key).forEach { cache.removeSpan(it) }
        }
    }
}
