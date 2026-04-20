// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.kofipod.EXTRA_OPEN_PLAYER
import app.kofipod.MainActivity
import app.kofipod.data.repo.PlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.mp.KoinPlatform

class KofipodPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var persistJob: Job? = null

    private val playback: PlaybackRepository by lazy { KoinPlatform.getKoin().get() }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    persistPosition(player)
                    if (isPlaying) startPersistTicker(player) else stopPersistTicker()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) persistPosition(player)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) persistPosition(player)
                }
            },
        )
        session =
            MediaSession.Builder(this, player)
                .setSessionActivity(openPlayerPendingIntent())
                .build()
        restoreLastSession(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let { persistPosition(it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.player?.let { persistPosition(it) }
        stopPersistTicker()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun startPersistTicker(player: Player) {
        persistJob?.cancel()
        persistJob =
            scope.launch {
                while (true) {
                    delay(PERSIST_INTERVAL_MS)
                    persistPosition(player)
                }
            }
    }

    private fun stopPersistTicker() {
        persistJob?.cancel()
        persistJob = null
    }

    private fun persistPosition(player: Player) {
        val item = player.currentMediaItem ?: return
        val episodeId = item.mediaId.takeIf { it.isNotBlank() } ?: return
        val sourceUrl = item.localConfiguration?.uri?.toString().orEmpty()
        if (sourceUrl.isEmpty()) return
        val meta = item.mediaMetadata
        val extras = meta.extras
        playback.save(
            episodeId = episodeId,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            speed = player.playbackParameters.speed,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            episodeTitle = meta.title?.toString().orEmpty(),
            podcastId = extras?.getString(EXTRA_PODCAST_ID).orEmpty(),
            podcastTitle = meta.artist?.toString().orEmpty(),
            artworkUrl = meta.artworkUri?.toString().orEmpty(),
            sourceUrl = sourceUrl,
            episodeNumber = extras?.getInt(EXTRA_EPISODE_NUMBER, -1)?.takeIf { it > 0 },
        )
    }

    private fun restoreLastSession(player: Player) {
        val last = playback.mostRecentIncomplete() ?: return
        if (last.sourceUrl.isBlank()) return
        val extras =
            Bundle().apply {
                if (last.podcastId.isNotBlank()) putString(EXTRA_PODCAST_ID, last.podcastId)
                last.episodeNumber?.toInt()?.takeIf { it > 0 }?.let {
                    putInt(EXTRA_EPISODE_NUMBER, it)
                }
            }
        val item =
            MediaItem.Builder()
                .setMediaId(last.episodeId)
                .setUri(last.sourceUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(last.episodeTitle)
                        .setArtist(last.podcastTitle)
                        .setArtworkUri(
                            if (last.artworkUrl.isNotBlank()) Uri.parse(last.artworkUrl) else null,
                        )
                        .setExtras(extras)
                        .build(),
                )
                .build()
        player.setMediaItem(item, last.positionMs)
        player.setPlaybackSpeed(last.playbackSpeed.toFloat())
        player.prepare()
    }

    private fun openPlayerPendingIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_PLAYER, true)
            }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 5_000L
    }
}
