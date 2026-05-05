// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * Owner of the Bookmark table.
 *
 * Reads expose Flows so the UI re-renders on every insert / delete without
 * needing to know which mutation happened. Writes are synchronous — bookmarks
 * are tiny (timestamp + optional note) and add() is a single INSERT.
 */
class BookmarkRepository(
    private val db: KofipodDatabase,
) {
    /**
     * Insert a new bookmark and return its generated id. Caller is responsible
     * for ensuring [episodeId] is in the library (the FK will reject otherwise).
     *
     * [nowMs] is injectable so unit tests can pin createdAtMs without faking a clock.
     */
    fun add(
        episodeId: String,
        podcastId: String,
        timestampMs: Long,
        note: String?,
        nowMs: Long,
    ): String {
        val id = generateId(nowMs)
        db.bookmarkQueries.insert(
            id = id,
            episodeId = episodeId,
            podcastId = podcastId,
            timestampMs = timestampMs,
            note = note?.takeIf { it.isNotBlank() },
            createdAtMs = nowMs,
        )
        return id
    }

    fun deleteById(id: String) {
        db.bookmarkQueries.deleteById(id)
    }

    fun updateNote(
        id: String,
        note: String?,
    ) {
        db.bookmarkQueries.updateNote(note?.takeIf { it.isNotBlank() }, id)
    }

    fun observeForEpisode(episodeId: String): Flow<List<Bookmark>> =
        db.bookmarkQueries
            .selectByEpisode(episodeId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map {
                    Bookmark(
                        id = it.id,
                        episodeId = it.episodeId,
                        podcastId = it.podcastId,
                        timestampMs = it.timestampMs,
                        note = it.note,
                        createdAtMs = it.createdAtMs,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    fun observeAll(): Flow<List<BookmarkWithContext>> =
        db.bookmarkQueries
            .selectAllWithContext()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    BookmarkWithContext(
                        bookmark =
                            Bookmark(
                                id = row.id,
                                episodeId = row.episodeId,
                                podcastId = row.podcastId,
                                timestampMs = row.timestampMs,
                                note = row.note,
                                createdAtMs = row.createdAtMs,
                            ),
                        episodeTitle = row.episodeTitle,
                        podcastTitle = row.podcastTitle,
                        artworkUrl = row.artworkUrl,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    fun observeForPodcast(podcastId: String): Flow<List<BookmarkWithContext>> =
        db.bookmarkQueries
            .selectByPodcastWithContext(podcastId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    BookmarkWithContext(
                        bookmark =
                            Bookmark(
                                id = row.id,
                                episodeId = row.episodeId,
                                podcastId = row.podcastId,
                                timestampMs = row.timestampMs,
                                note = row.note,
                                createdAtMs = row.createdAtMs,
                            ),
                        episodeTitle = row.episodeTitle,
                        podcastTitle = row.podcastTitle,
                        artworkUrl = row.artworkUrl,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    private fun generateId(nowMs: Long): String {
        val rand = Random.nextLong(0L, Long.MAX_VALUE)
        return nowMs.toString(36).padStart(8, '0') + "-" + rand.toString(36).take(8)
    }
}
