// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlin.test.Test
import kotlin.test.assertEquals

class PredicateEvaluatorTest {
    private val evaluator = PredicateEvaluator()
    private val nowMs = 1_715_000_000_000L

    private fun fact(
        id: String,
        podcastId: String = "pod1",
        publishedAtMs: Long = nowMs,
        durationSec: Int = 1800,
        transcriptUrl: String? = null,
        hasCachedTranscript: Boolean = false,
        hasSnippets: Boolean = false,
        isDownloaded: Boolean = false,
        state: PlayState = PlayState.Unplayed,
    ) = EpisodeFacts(
        episodeId = id,
        episodeTitle = "Title $id",
        podcastId = podcastId,
        publishedAtMs = publishedAtMs,
        durationSec = durationSec,
        transcriptUrl = transcriptUrl,
        hasCachedTranscript = hasCachedTranscript,
        hasSnippets = hasSnippets,
        isDownloaded = isDownloaded,
        playState = state,
    )

    @Test fun emptyPredicateMatchesAll() {
        val facts = listOf(fact("e1"), fact("e2"))
        assertEquals(2, evaluator.evaluate(SmartPlaylistPredicate.EMPTY, facts, nowMs).size)
    }

    @Test fun stateUnplayedFilters() {
        val facts =
            listOf(
                fact("u", state = PlayState.Unplayed),
                fact("ip", state = PlayState.InProgress),
                fact("c", state = PlayState.Completed),
            )
        val result = evaluator.evaluate(SmartPlaylistPredicate(state = PlayState.Unplayed), facts, nowMs)
        assertEquals(listOf("u"), result.map { it.episodeId })
    }

    @Test fun durationRangeMinMax() {
        val facts =
            listOf(
                fact("short", durationSec = 300),
                fact("mid", durationSec = 1800),
                fact("long", durationSec = 7200),
            )
        val result =
            evaluator.evaluate(
                SmartPlaylistPredicate(durationRange = DurationRange(minSec = 600, maxSec = 3600)),
                facts,
                nowMs,
            )
        assertEquals(listOf("mid"), result.map { it.episodeId })
    }

    @Test fun durationRangeOnlyMin() {
        val facts = listOf(fact("short", durationSec = 300), fact("long", durationSec = 7200))
        val result =
            evaluator.evaluate(
                SmartPlaylistPredicate(durationRange = DurationRange(minSec = 600, maxSec = null)),
                facts,
                nowMs,
            )
        assertEquals(listOf("long"), result.map { it.episodeId })
    }

    @Test fun podcastIdsFilter() {
        val facts = listOf(fact("a", podcastId = "p1"), fact("b", podcastId = "p2"))
        val result =
            evaluator.evaluate(
                SmartPlaylistPredicate(podcastIds = setOf("p1")),
                facts,
                nowMs,
            )
        assertEquals(listOf("a"), result.map { it.episodeId })
    }

    @Test fun emptyPodcastIdsSetMatchesAll() {
        // Empty (not null) set is a "no chip filter applied" UX state — should match all.
        val facts = listOf(fact("a"), fact("b"))
        val result =
            evaluator.evaluate(
                SmartPlaylistPredicate(podcastIds = emptySet()),
                facts,
                nowMs,
            )
        assertEquals(2, result.size)
    }

    @Test fun maxAgeDaysCutoff() {
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val facts =
            listOf(
                fact("recent", publishedAtMs = nowMs - sevenDaysMs / 2),
                fact("old", publishedAtMs = nowMs - sevenDaysMs * 2),
            )
        val result = evaluator.evaluate(SmartPlaylistPredicate(maxAgeDays = 7), facts, nowMs)
        assertEquals(listOf("recent"), result.map { it.episodeId })
    }

    @Test fun hasTranscriptViaUrlOrCache() {
        val facts =
            listOf(
                fact("none"),
                fact("urlOnly", transcriptUrl = "https://x"),
                fact("cacheOnly", hasCachedTranscript = true),
                fact("both", transcriptUrl = "https://x", hasCachedTranscript = true),
            )
        val result = evaluator.evaluate(SmartPlaylistPredicate(hasTranscript = true), facts, nowMs)
        assertEquals(setOf("urlOnly", "cacheOnly", "both"), result.map { it.episodeId }.toSet())
    }

    @Test fun hasTranscriptFalseFiltersOnlyMissing() {
        val facts =
            listOf(
                fact("none"),
                fact("urlOnly", transcriptUrl = "https://x"),
            )
        val result = evaluator.evaluate(SmartPlaylistPredicate(hasTranscript = false), facts, nowMs)
        assertEquals(listOf("none"), result.map { it.episodeId })
    }

    @Test fun downloadedOnlyTrueFilters() {
        val facts = listOf(fact("dl", isDownloaded = true), fact("nodl"))
        val result = evaluator.evaluate(SmartPlaylistPredicate(downloadedOnly = true), facts, nowMs)
        assertEquals(listOf("dl"), result.map { it.episodeId })
    }

    @Test fun downloadedOnlyFalseIsNoOp() {
        // `false` semantically means "the user did NOT pick this chip" — match all.
        val facts = listOf(fact("dl", isDownloaded = true), fact("nodl"))
        val result = evaluator.evaluate(SmartPlaylistPredicate(downloadedOnly = false), facts, nowMs)
        assertEquals(2, result.size)
    }

    @Test fun hasSnippetsFiltersBothDirections() {
        val facts = listOf(fact("withS", hasSnippets = true), fact("noS"))
        assertEquals(
            listOf("withS"),
            evaluator.evaluate(SmartPlaylistPredicate(hasSnippets = true), facts, nowMs).map { it.episodeId },
        )
        assertEquals(
            listOf("noS"),
            evaluator.evaluate(SmartPlaylistPredicate(hasSnippets = false), facts, nowMs).map { it.episodeId },
        )
    }

    @Test fun multiplePredicatesAreAndCombined() {
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val facts =
            listOf(
                fact("match", publishedAtMs = nowMs - sevenDaysMs / 2, isDownloaded = true, state = PlayState.Unplayed),
                fact("wrongState", publishedAtMs = nowMs - sevenDaysMs / 2, isDownloaded = true, state = PlayState.Completed),
                fact("notDownloaded", publishedAtMs = nowMs - sevenDaysMs / 2, isDownloaded = false, state = PlayState.Unplayed),
                fact("tooOld", publishedAtMs = nowMs - sevenDaysMs * 2, isDownloaded = true, state = PlayState.Unplayed),
            )
        val result =
            evaluator.evaluate(
                SmartPlaylistPredicate(state = PlayState.Unplayed, maxAgeDays = 7, downloadedOnly = true),
                facts,
                nowMs,
            )
        assertEquals(listOf("match"), result.map { it.episodeId })
    }

    @Test fun resultsSortedByPublishedAtDesc() {
        val facts =
            listOf(
                fact("oldest", publishedAtMs = 100L),
                fact("middle", publishedAtMs = 200L),
                fact("newest", publishedAtMs = 300L),
            )
        val result = evaluator.evaluate(SmartPlaylistPredicate.EMPTY, facts, nowMs)
        assertEquals(listOf("newest", "middle", "oldest"), result.map { it.episodeId })
    }
}
