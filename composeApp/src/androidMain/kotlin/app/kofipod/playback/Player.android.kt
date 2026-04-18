// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

actual class KofipodPlayer(private val context: Context) {

    private val _state = MutableStateFlow(PlayerState())
    actual val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var tickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var pendingEpisode: PlayableEpisode? = null

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(
            context,
            ComponentName(context, KofipodPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val c = future.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()
                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int,
                    ) = pushState()
                    override fun onPlaybackStateChanged(playbackState: Int) = pushState()
                })
                startTicker()
                pendingEpisode?.let { doPlay(c, it) }
                pendingEpisode = null
                pushState()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(500)
                pushState()
            }
        }
    }

    private fun pushState() {
        val c = controller ?: return
        val meta = c.currentMediaItem?.mediaMetadata
        _state.value = PlayerState(
            episodeId = c.currentMediaItem?.mediaId,
            title = meta?.title?.toString().orEmpty(),
            podcastTitle = meta?.artist?.toString().orEmpty(),
            artworkUrl = meta?.artworkUri?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
        )
    }

    private fun doPlay(c: MediaController, episode: PlayableEpisode) {
        val item = MediaItem.Builder()
            .setMediaId(episode.episodeId)
            .setUri(episode.sourceUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle)
                    .setArtworkUri(
                        if (episode.artworkUrl.isNotBlank()) Uri.parse(episode.artworkUrl) else null,
                    )
                    .build(),
            )
            .build()
        c.setMediaItem(item, episode.startPositionMs)
        c.prepare()
        c.play()
    }

    actual fun play(episode: PlayableEpisode) {
        val c = controller
        if (c != null) doPlay(c, episode) else pendingEpisode = episode
    }

    actual fun pause() { controller?.pause() }
    actual fun resume() { controller?.play() }
    actual fun seekTo(ms: Long) { controller?.seekTo(ms) }
    actual fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }
    actual fun skipForward() {
        controller?.let { it.seekTo(it.currentPosition + 30_000) }
    }
    actual fun skipBack() {
        controller?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
    }
    actual fun stop() { controller?.stop() }
}
