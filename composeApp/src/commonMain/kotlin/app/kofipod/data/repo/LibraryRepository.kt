// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import app.kofipod.domain.PodcastSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

class LibraryRepository(private val db: KofipodDatabase) {
    fun listsFlow(): Flow<List<PodcastList>> = db.podcastListQueries.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun podcastsFlow(): Flow<List<Podcast>> = db.podcastQueries.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun podcastsInList(listId: String?): Flow<List<Podcast>> =
        db.podcastQueries.selectByList(listId).asFlow().mapToList(Dispatchers.Default)

    fun podcastFlow(id: String): Flow<Podcast?> = db.podcastQueries.selectById(id).asFlow().mapToOneOrNull(Dispatchers.Default)

    fun podcastNow(id: String): Podcast? = db.podcastQueries.selectById(id).executeAsOneOrNull()

    fun podcastsNow(): List<Podcast> = db.podcastQueries.selectAll().executeAsList()

    fun hasArtworkUrl(url: String): Boolean = db.podcastQueries.countByArtworkUrl(url).executeAsOne() > 0L

    fun savePodcast(
        summary: PodcastSummary,
        listId: String?,
        now: Long,
    ) {
        db.podcastQueries.insert(
            id = summary.id,
            title = summary.title,
            author = summary.author,
            description = summary.description,
            artworkUrl = summary.artworkUrl,
            feedUrl = summary.feedUrl,
            listId = listId,
            autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1,
            lastCheckedAt = null,
            addedAt = now,
        )
    }

    fun createList(
        id: String,
        name: String,
        position: Int,
        now: Long,
    ) {
        db.podcastListQueries.insert(id, name, position.toLong(), now)
    }

    fun renameList(
        id: String,
        name: String,
    ) = db.podcastListQueries.rename(name, id)

    fun deleteList(id: String) = db.podcastListQueries.delete(id)

    fun movePodcastToList(
        podcastId: String,
        listId: String?,
    ) = db.podcastQueries.moveToList(listId, podcastId)

    fun setAutoDownload(
        podcastId: String,
        enabled: Boolean,
    ) = db.podcastQueries.setAutoDownload(if (enabled) 1 else 0, podcastId)

    fun setNotifyNewEpisodes(
        podcastId: String,
        enabled: Boolean,
    ) = db.podcastQueries.setNotifyNewEpisodes(if (enabled) 1 else 0, podcastId)

    fun setLastChecked(
        podcastId: String,
        atMillis: Long,
    ) = db.podcastQueries.setLastChecked(atMillis, podcastId)

    fun deletePodcast(podcastId: String) = db.podcastQueries.delete(podcastId)
}

fun Podcast.autoDownloadEnabledBool(): Boolean = autoDownloadEnabled != 0L

fun Podcast.notifyNewEpisodesEnabledBool(): Boolean = notifyNewEpisodesEnabled != 0L
