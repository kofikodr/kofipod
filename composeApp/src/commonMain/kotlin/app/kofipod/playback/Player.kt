// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import kotlinx.coroutines.flow.StateFlow

data class PlayableEpisode(
    val episodeId: String,
    val podcastTitle: String,
    val title: String,
    val artworkUrl: String,
    val sourceUrl: String,
    val startPositionMs: Long,
)

data class PlayerState(
    val episodeId: String? = null,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

expect class KofipodPlayer {
    val state: StateFlow<PlayerState>
    fun play(episode: PlayableEpisode)
    fun pause()
    fun resume()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun skipForward()
    fun skipBack()
    fun stop()
}
