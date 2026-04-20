// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kofipod.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import app.kofipod.network.NetworkMonitor
import app.kofipod.network.NetworkType

/**
 * Network-aware [DefaultLoadControl]. Caps how far ahead the player buffers (and therefore how
 * much the [PlaybackCache] writes to disk) based on current connectivity: up to 60 min on
 * Wi-Fi/ethernet, 5 min on metered. The inner DefaultLoadControl sets the wifi ceiling; the
 * override enforces the stricter metered ceiling on top.
 */
class AdaptiveLoadControl(
    private val networkMonitor: NetworkMonitor,
) : DefaultLoadControl(
        DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        MIN_BUFFER_MS,
        MAX_BUFFER_MS_WIFI,
        BUFFER_FOR_PLAYBACK_MS,
        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        C.LENGTH_UNSET,
        false,
        0,
        false,
    ) {
    override fun shouldContinueLoading(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
    ): Boolean {
        val maxUs =
            when (networkMonitor.type.value) {
                NetworkType.Wifi -> MAX_BUFFER_MS_WIFI.toLong() * 1_000L
                NetworkType.Metered -> MAX_BUFFER_MS_METERED.toLong() * 1_000L
                // No network: don't burn battery on futile load retries. Play from what's buffered.
                NetworkType.None -> return false
            }
        if (bufferedDurationUs >= maxUs) return false
        return super.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)
    }

    companion object {
        private const val MIN_BUFFER_MS: Int = 50_000
        private const val MAX_BUFFER_MS_WIFI: Int = 60 * 60 * 1000
        private const val MAX_BUFFER_MS_METERED: Int = 5 * 60 * 1000
        private const val BUFFER_FOR_PLAYBACK_MS: Int = 2_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 5_000
    }
}
