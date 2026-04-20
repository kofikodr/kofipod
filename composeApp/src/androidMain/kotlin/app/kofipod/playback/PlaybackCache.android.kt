// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kofipod.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

actual class PlaybackCache(private val context: Context, private val capBytes: Long) {
    // Media3 enforces one SimpleCache instance per directory per process. The Koin `single { }`
    // binding guarantees that across the wrapper. release() drops the inner SimpleCache so a
    // subsequent access rebuilds it, which lets the playback service flush its database
    // provider in onDestroy without breaking the next onCreate.
    private var _cache: SimpleCache? = null

    internal val cache: SimpleCache
        get() = _cache ?: build().also { _cache = it }

    private fun build(): SimpleCache =
        SimpleCache(
            File(context.cacheDir, "media").apply { mkdirs() },
            LeastRecentlyUsedCacheEvictor(capBytes),
            StandaloneDatabaseProvider(context),
        )

    actual fun sizeBytes(): Long = _cache?.cacheSpace ?: 0L

    actual fun clear() {
        val c = _cache ?: return
        c.keys.toList().forEach { key ->
            c.getCachedSpans(key).forEach { c.removeSpan(it) }
        }
    }

    actual fun release() {
        _cache?.release()
        _cache = null
    }
}
