// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kofipod.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.kofipod.EXTRA_OPEN_PLAYER
import app.kofipod.MainActivity
import app.kofipod.network.NetworkMonitor
import org.koin.android.ext.android.inject

class KofipodPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val playbackCache: PlaybackCache by inject()
    private val networkMonitor: NetworkMonitor by inject()

    override fun onCreate() {
        super.onCreate()
        val cacheDataSourceFactory =
            CacheDataSource.Factory()
                .setCache(playbackCache.cache)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val player =
            ExoPlayer.Builder(this)
                .setLoadControl(AdaptiveLoadControl(networkMonitor))
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
                .build()
        session =
            MediaSession.Builder(this, player)
                .setSessionActivity(openPlayerPendingIntent())
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
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
}
