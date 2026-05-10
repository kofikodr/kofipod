// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.domain.PodcastSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

class LibraryRepository(
    private val db: KofipodDatabase,
    // Override for tests so SQLDelight `mapToList`/`mapToOneOrNull` query work
    // runs on the test scheduler instead of the real `Dispatchers.Default` pool —
    // the latter races test schedulers and turns flow-driven VM tests flaky.
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun listsFlow(): Flow<List<PodcastList>> = db.podcastListQueries.selectAll().asFlow().mapToList(queryDispatcher)

    fun podcastsFlow(): Flow<List<Podcast>> = db.podcastQueries.selectAll().asFlow().mapToList(queryDispatcher)

    fun podcastsInList(listId: String?): Flow<List<Podcast>> = db.podcastQueries.selectByList(listId).asFlow().mapToList(queryDispatcher)

    fun podcastFlow(id: String): Flow<Podcast?> = db.podcastQueries.selectById(id).asFlow().mapToOneOrNull(queryDispatcher)

    fun podcastNow(id: String): Podcast? = db.podcastQueries.selectById(id).executeAsOneOrNull()

    fun podcastsNow(): List<Podcast> = db.podcastQueries.selectAll().executeAsList()

    fun listsNow(): List<PodcastList> = db.podcastListQueries.selectAll().executeAsList()

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
            primaryCategory = summary.category,
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
