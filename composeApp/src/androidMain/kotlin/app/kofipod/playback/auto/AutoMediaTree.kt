// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.playback.EXTRA_EPISODE_NUMBER
import app.kofipod.playback.EXTRA_PODCAST_ID
import app.kofipod.playback.MEDIA_ID_EPISODE_PREFIX

private const val ROOT_ID = "kp:/"
private const val SECTION_SUBSCRIPTIONS = "kp:/subscriptions"
private const val SECTION_CONTINUE = "kp:/continue"
private const val SECTION_DOWNLOADS = "kp:/downloads"
private const val PODCAST_PREFIX = "kp:/podcast/"

/**
 * Builds [MediaItem] trees for the Android Auto browse experience from the repositories.
 * All operations are synchronous DB reads; callers must invoke this on a background
 * executor (the Media3 session executor or [java.util.concurrent.Executors.newSingleThreadExecutor]).
 */
class AutoMediaTree(
    private val context: Context,
    private val library: LibraryRepository,
    private val episodes: EpisodesRepository,
    private val downloads: DownloadRepository,
    private val playback: PlaybackRepository,
) {
    fun root(): MediaItem =
        browsableItem(
            mediaId = ROOT_ID,
            title = "Kofipod",
            subtitle = null,
            artworkUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        )

    fun children(parentId: String): List<MediaItem> =
        when {
            parentId == ROOT_ID -> rootSections()
            parentId == SECTION_SUBSCRIPTIONS -> subscriptions()
            parentId == SECTION_CONTINUE -> continueListening()
            parentId == SECTION_DOWNLOADS -> downloadsBrowse()
            parentId.startsWith(PODCAST_PREFIX) -> episodesOf(parentId.removePrefix(PODCAST_PREFIX))
            else -> emptyList()
        }

    fun item(mediaId: String): MediaItem? =
        when {
            mediaId == ROOT_ID -> root()
            mediaId == SECTION_SUBSCRIPTIONS -> rootSections()[0]
            mediaId == SECTION_CONTINUE -> rootSections()[1]
            mediaId == SECTION_DOWNLOADS -> rootSections()[2]
            mediaId.startsWith(PODCAST_PREFIX) ->
                library.podcastNow(mediaId.removePrefix(PODCAST_PREFIX))?.let { podcastItem(it) }
            mediaId.startsWith(MEDIA_ID_EPISODE_PREFIX) ->
                resolveEpisode(mediaId.removePrefix(MEDIA_ID_EPISODE_PREFIX))?.let { (ep, podcast) ->
                    playableEpisodeItem(mediaId, ep, podcast, includeUri = false)
                }
            else -> null
        }

    /**
     * Resolves a browse [MediaItem] into a full playable item with a streaming or local
     * URI and an artist/artwork/extras payload suitable for the player session.
     * Returns null if the media id is unrecognized or the episode can't be resolved.
     */
    fun resolveForPlayback(mediaItem: MediaItem): MediaItem? {
        val id = mediaItem.mediaId
        if (!id.startsWith(MEDIA_ID_EPISODE_PREFIX)) return null
        val episodeId = id.removePrefix(MEDIA_ID_EPISODE_PREFIX)
        val (episode, podcast) = resolveEpisode(episodeId) ?: return null
        return playableEpisodeItem(id, episode, podcast, includeUri = true)
    }

    fun startPositionFor(mediaId: String): Long =
        if (mediaId.startsWith(MEDIA_ID_EPISODE_PREFIX)) {
            playback.positionFor(mediaId.removePrefix(MEDIA_ID_EPISODE_PREFIX))
        } else {
            0L
        }

    private fun rootSections(): List<MediaItem> =
        listOf(
            browsableItem(
                mediaId = SECTION_SUBSCRIPTIONS,
                title = "Subscriptions",
                subtitle = null,
                artworkUri = null,
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
            ),
            browsableItem(
                mediaId = SECTION_CONTINUE,
                title = "Continue Listening",
                subtitle = null,
                artworkUri = null,
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            ),
            browsableItem(
                mediaId = SECTION_DOWNLOADS,
                title = "Downloads",
                subtitle = null,
                artworkUri = null,
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            ),
        )

    private fun subscriptions(): List<MediaItem> = library.podcastsNow().map { podcastItem(it) }

    private fun episodesOf(podcastId: String): List<MediaItem> {
        val podcast = library.podcastNow(podcastId) ?: return emptyList()
        return episodes.episodesNow(podcastId).map { ep ->
            playableEpisodeItem("$MEDIA_ID_EPISODE_PREFIX${ep.id}", ep, podcast, includeUri = false)
        }
    }

    private fun continueListening(): List<MediaItem> =
        playback.inProgressNow().map { row ->
            playableItem(
                mediaId = "$MEDIA_ID_EPISODE_PREFIX${row.episodeId}",
                title = row.episodeTitle,
                subtitle = row.podcastTitle,
                artworkUri = artworkUriOrNull(row.artworkUrl),
                podcastId = row.podcastId,
                episodeNumber = null,
            )
        }

    private fun downloadsBrowse(): List<MediaItem> =
        downloads.completedWithMetaNow().map { row ->
            playableItem(
                mediaId = "$MEDIA_ID_EPISODE_PREFIX${row.episodeId}",
                title = row.episodeTitle,
                subtitle = row.podcastTitle,
                artworkUri = artworkUriOrNull(row.artworkUrl),
                podcastId = row.podcastId,
                episodeNumber = null,
            )
        }

    private fun resolveEpisode(episodeId: String): Pair<Episode, Podcast>? {
        val episode = episodes.episodeNow(episodeId) ?: return null
        val podcast = library.podcastNow(episode.podcastId) ?: return null
        return episode to podcast
    }

    private fun podcastItem(podcast: Podcast): MediaItem =
        browsableItem(
            mediaId = "$PODCAST_PREFIX${podcast.id}",
            title = podcast.title,
            subtitle = podcast.author.takeIf { it.isNotBlank() },
            artworkUri = artworkUriOrNull(podcast.artworkUrl),
            mediaType = MediaMetadata.MEDIA_TYPE_PODCAST,
        )

    private fun playableEpisodeItem(
        mediaId: String,
        episode: Episode,
        podcast: Podcast,
        includeUri: Boolean,
    ): MediaItem {
        // resolvedSourceUrl returns either an http(s) URL for a stream or a file:// URI for a
        // downloaded copy — both are valid arguments to Uri.parse, so no branching needed.
        val uri =
            if (includeUri) {
                downloads.resolvedSourceUrl(episode.id, episode.enclosureUrl)?.let { Uri.parse(it) }
            } else {
                null
            }
        return playableItem(
            mediaId = mediaId,
            title = episode.title,
            subtitle = podcast.title,
            artworkUri = artworkUriOrNull(podcast.artworkUrl),
            podcastId = podcast.id,
            episodeNumber = episode.episodeNumber?.toInt(),
            uri = uri,
        )
    }

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
        artworkUri: Uri?,
        mediaType: Int,
    ): MediaItem {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(artworkUri)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun playableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
        artworkUri: Uri?,
        podcastId: String,
        episodeNumber: Int?,
        uri: Uri? = null,
    ): MediaItem {
        val extras =
            Bundle().apply {
                if (podcastId.isNotBlank()) putString(EXTRA_PODCAST_ID, podcastId)
                episodeNumber?.let { putInt(EXTRA_EPISODE_NUMBER, it) }
            }
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(artworkUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setExtras(extras)
                .build()
        val builder =
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(metadata)
        if (uri != null) builder.setUri(uri)
        return builder.build()
    }

    private fun artworkUriOrNull(url: String?): Uri? = if (!url.isNullOrBlank()) ArtworkProvider.uriFor(context, url) else null
}
