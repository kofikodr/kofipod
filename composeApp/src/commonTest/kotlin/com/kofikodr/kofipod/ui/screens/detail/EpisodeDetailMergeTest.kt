// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import com.kofikodr.kofipod.data.repo.RemoteEpisodeCache
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeDetailMergeTest {
    @Test
    fun `db null and cache null emits null`() =
        runTest {
            val merged = mergeEpisodeWithCache(MutableStateFlow(null), MutableStateFlow(null))
            assertNull(merged.first())
        }

    @Test
    fun `db null and cache hit emits cached episode`() =
        runTest {
            val cached = entry("e1", "p1", "from-cache")
            val merged =
                mergeEpisodeWithCache(MutableStateFlow<Episode?>(null), MutableStateFlow(cached))
            assertEquals("from-cache", merged.first()?.title)
        }

    @Test
    fun `db hit overrides cache hit`() =
        runTest {
            val db = entry("e1", "p1", "from-db").episode
            val cached = entry("e1", "p1", "from-cache")
            val merged = mergeEpisodeWithCache(MutableStateFlow(db), MutableStateFlow(cached))
            assertEquals("from-db", merged.first()?.title)
        }

    @Test
    fun `db hit and cache miss emits db episode`() =
        runTest {
            val db = entry("e1", "p1", "from-db").episode
            val merged =
                mergeEpisodeWithCache(MutableStateFlow(db), MutableStateFlow<RemoteEpisodeCache.Entry?>(null))
            assertEquals("from-db", merged.first()?.title)
        }

    @Test
    fun `later cache emission is pushed to an existing subscriber`() =
        runTest(UnconfinedTestDispatcher()) {
            val dbFlow = MutableStateFlow<Episode?>(null)
            val cacheFlow = MutableStateFlow<RemoteEpisodeCache.Entry?>(null)
            val merged = mergeEpisodeWithCache(dbFlow, cacheFlow)
            val update = CompletableDeferred<Episode?>()
            // Subscribe BEFORE the cache fill so we test reactive propagation, not
            // a cold re-read. drop(1) skips the initial (null, null) → null.
            val job = launch { update.complete(merged.drop(1).first()) }
            cacheFlow.value = entry("e1", "p1", "from-cache")
            assertEquals("from-cache", update.await()?.title)
            job.cancel()
        }
}

private fun entry(
    episodeId: String,
    podcastId: String,
    title: String,
): RemoteEpisodeCache.Entry =
    RemoteEpisodeCache.Entry(
        episode =
            Episode(
                id = episodeId,
                podcastId = podcastId,
                guid = "guid",
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
                title = "Pod",
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
            ),
    )
