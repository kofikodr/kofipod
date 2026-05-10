// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playlists

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartPlaylistResolverTest {
    private val nowMs = 1_715_000_000_000L

    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
        }

    private class FakeFacts(val flow: MutableStateFlow<List<EpisodeFacts>>) : EpisodeFactsRepository {
        override fun observeAll() = flow
    }

    private fun fact(
        id: String,
        publishedAtMs: Long = nowMs,
        state: PlayState = PlayState.Unplayed,
    ) = EpisodeFacts(
        episodeId = id,
        episodeTitle = "Title $id",
        podcastId = "p",
        publishedAtMs = publishedAtMs,
        durationSec = 1800,
        transcriptUrl = null,
        hasCachedTranscript = false,
        hasSnippets = false,
        isDownloaded = false,
        playState = state,
    )

    @Test fun emitsMatchedFactsForPredicate() =
        runTest {
            val facts =
                MutableStateFlow(
                    listOf(
                        fact("u", state = PlayState.Unplayed),
                        fact("c", state = PlayState.Completed),
                    ),
                )
            val resolver = SmartPlaylistResolver(FakeFacts(facts), PredicateEvaluator(), fixedClock)
            val result = resolver.observe(SmartPlaylistPredicate(state = PlayState.Unplayed)).first()
            assertEquals(listOf("u"), result.map { it.episodeId })
        }

    @Test fun reEmitsWhenFactsChange() =
        runTest {
            val facts = MutableStateFlow<List<EpisodeFacts>>(emptyList())
            val resolver = SmartPlaylistResolver(FakeFacts(facts), PredicateEvaluator(), fixedClock)
            // Take 2 emissions: initial empty, then post-update list.
            val collected =
                async(start = CoroutineStart.UNDISPATCHED) {
                    resolver.observe(SmartPlaylistPredicate.EMPTY).take(2).toList()
                }
            // Allow the cold flow to settle on the initial empty before pushing the update.
            yield()
            facts.value = listOf(fact("a"))
            val emissions = collected.await().map { it.map { f -> f.episodeId } }
            assertEquals(listOf(emptyList(), listOf("a")), emissions)
        }

    @Test fun resolvesAgeRelativeToInjectedClock() =
        runTest {
            val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
            val facts =
                MutableStateFlow(
                    listOf(
                        fact("recent", publishedAtMs = nowMs - sevenDaysMs / 2),
                        fact("old", publishedAtMs = nowMs - sevenDaysMs * 2),
                    ),
                )
            val resolver = SmartPlaylistResolver(FakeFacts(facts), PredicateEvaluator(), fixedClock)
            val result = resolver.observe(SmartPlaylistPredicate(maxAgeDays = 7)).first()
            assertEquals(listOf("recent"), result.map { it.episodeId })
        }
}
