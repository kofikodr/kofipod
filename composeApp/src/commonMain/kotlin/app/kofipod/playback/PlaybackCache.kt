// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

/**
 * Streaming playback cache. Stores bytes the player reads so pause/resume, scrub-back, and
 * lost-signal-continues-from-cache all work without re-hitting the network. Bounded by LRU
 * eviction. Lives under the app's cacheDir so the OS can reclaim it under storage pressure.
 */
expect class PlaybackCache {
    fun sizeBytes(): Long

    fun clear()

    fun release()
}
