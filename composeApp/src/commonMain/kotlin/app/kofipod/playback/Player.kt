// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import kotlinx.coroutines.flow.StateFlow

data class PlayableEpisode(
    val episodeId: String,
    val podcastId: String,
    val podcastTitle: String,
    val title: String,
    val artworkUrl: String,
    val sourceUrl: String,
    val startPositionMs: Long,
    val episodeNumber: Int? = null,
)

data class PlayerState(
    val episodeId: String? = null,
    val podcastId: String = "",
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String = "",
    val episodeNumber: Int? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepRemainingMs: Long? = null,
)

/**
 * Number of frequency bars the visualizer renders. Shared by analyzer and UI so
 * both sides agree on the [FloatArray] shape that flows through [KofipodPlayer.audioLevels].
 */
const val AUDIO_LEVEL_BAR_COUNT: Int = 24

expect class KofipodPlayer {
    val state: StateFlow<PlayerState>

    /**
     * Emits [AUDIO_LEVEL_BAR_COUNT]-sized arrays of 0..1 magnitudes derived from the
     * currently playing audio (log-spaced FFT bins, peak-hold smoothed). Paused or silent
     * playback decays to zero.
     */
    val audioLevels: StateFlow<FloatArray>

    fun play(episode: PlayableEpisode)

    fun pause()

    fun resume()

    fun seekTo(ms: Long)

    fun setSpeed(speed: Float)

    fun skipForward()

    fun skipBack()

    fun setSleepTimer(ms: Long?)

    fun stop()

    fun release()
}
