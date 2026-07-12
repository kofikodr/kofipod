// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteEpisodeCacheTest {
    @Test
    fun `get returns null for unknown id`() {
        val cache = RemoteEpisodeCache()
        assertNull(cache.get("missing"))
    }

    @Test
    fun `put then get round-trips the entry`() {
        val cache = RemoteEpisodeCache()
        val entry = entry(episodeId = "e1", podcastId = "p1")
        cache.put(listOf(entry))
        assertEquals(entry, cache.get("e1"))
    }

    @Test
    fun `put with overlapping ids overwrites the existing entry`() {
        val cache = RemoteEpisodeCache()
        val first = entry(episodeId = "e1", podcastId = "p1", title = "First")
        val second = entry(episodeId = "e1", podcastId = "p1", title = "Second")
        cache.put(listOf(first))
        cache.put(listOf(second))
        assertEquals("Second", cache.get("e1")?.episode?.title)
    }

    @Test
    fun `put with empty list is a no-op`() {
        val cache = RemoteEpisodeCache()
        val seed = entry(episodeId = "e1", podcastId = "p1")
        cache.put(listOf(seed))
        cache.put(emptyList())
        assertEquals(seed, cache.get("e1"))
    }

    @Test
    fun `observe pushes updates to an existing subscriber`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RemoteEpisodeCache()
            val seed = entry(episodeId = "e1", podcastId = "p1")
            val received = CompletableDeferred<RemoteEpisodeCache.Entry?>()
            // Subscribe BEFORE the put so we exercise reactive delivery, not a cold re-read.
            // drop(1) skips the initial `null` emission so the assertion targets the
            // post-put update.
            val job = launch { received.complete(cache.observe("e1").drop(1).first()) }
            cache.put(listOf(seed))
            assertEquals(seed, received.await())
            job.cancel()
        }

    @Test
    fun `concurrent puts from many coroutines all land in the cache`() =
        runTest {
            val cache = RemoteEpisodeCache()
            val n = 50
            (0 until n)
                .map { i -> async { cache.put(listOf(entry(episodeId = "e$i", podcastId = "p"))) } }
                .awaitAll()
            (0 until n).forEach { i ->
                assertEquals("Title e$i", cache.get("e$i")?.episode?.title, "missing entry e$i")
            }
        }

    @Test
    fun `multiple ids stored independently`() {
        val cache = RemoteEpisodeCache()
        val a = entry(episodeId = "a", podcastId = "p1")
        val b = entry(episodeId = "b", podcastId = "p2")
        cache.put(listOf(a, b))
        assertEquals(a, cache.get("a"))
        assertEquals(b, cache.get("b"))
        assertNull(cache.get("c"))
    }
}

private fun entry(
    episodeId: String,
    podcastId: String,
    title: String = "Title $episodeId",
): RemoteEpisodeCache.Entry =
    RemoteEpisodeCache.Entry(
        episode =
            Episode(
                id = episodeId,
                podcastId = podcastId,
                guid = "guid-$episodeId",
                title = title,
                description = "",
                publishedAt = 0L,
                durationSec = 0L,
                enclosureUrl = "",
                enclosureMimeType = "",
                fileSizeBytes = 0L,
                seasonNumber = null,
                episodeNumber = null,
                imageUrl = "",
                chaptersUrl = null,
                transcriptUrl = null,
            ),
        podcast =
            Podcast(
                id = podcastId,
                title = "Podcast $podcastId",
                author = "",
                description = "",
                artworkUrl = "",
                feedUrl = "",
                listId = null,
                autoDownloadEnabled = 0L,
                notifyNewEpisodesEnabled = 1L,
                lastCheckedAt = null,
                addedAt = 0L,
                primaryCategory = "",
                lastSeenAt = null,
            ),
    )
