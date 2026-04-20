// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.kofipod.EXTRA_OPEN_PLAYER
import app.kofipod.MainActivity
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.playback.auto.AutoMediaTree
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.koin.android.ext.android.inject

class KofipodPlaybackService : MediaLibraryService() {
    private val library by inject<LibraryRepository>()
    private val episodes by inject<EpisodesRepository>()
    private val downloads by inject<DownloadRepository>()
    private val playback by inject<PlaybackRepository>()
    private val settings by inject<SettingsRepository>()

    private var session: MediaLibrarySession? = null
    private lateinit var tree: AutoMediaTree

    override fun onCreate() {
        super.onCreate()
        tree = AutoMediaTree(applicationContext, library, episodes, downloads, playback)
        val skipForwardMs = settings.getMetaNow(SettingsRepository.KEY_SKIP_FWD)?.toLongOrNull()?.times(1_000L) ?: 30_000L
        val skipBackMs = settings.getMetaNow(SettingsRepository.KEY_SKIP_BACK)?.toLongOrNull()?.times(1_000L) ?: 10_000L
        val player =
            ExoPlayer.Builder(this)
                .setSeekForwardIncrementMs(skipForwardMs)
                .setSeekBackIncrementMs(skipBackMs)
                .build()
        session =
            MediaLibrarySession.Builder(this, player, LibraryCallback())
                .setSessionActivity(openPlayerPendingIntent())
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = ImmutableList.copyOf(tree.children(parentId))
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item =
                tree.item(mediaId)
                    ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val resolved = mediaItems.mapNotNull { tree.resolveForPlayback(it) ?: passthroughIfPlayable(it) }
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.mapNotNull { tree.resolveForPlayback(it) ?: passthroughIfPlayable(it) }
            val resumePosition =
                if (startPositionMs == C.TIME_UNSET && resolved.size == 1) {
                    tree.startPositionFor(resolved[0].mediaId)
                } else {
                    startPositionMs
                }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, resumePosition),
            )
        }

        private fun passthroughIfPlayable(item: MediaItem): MediaItem? = if (item.localConfiguration != null) item else null
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
