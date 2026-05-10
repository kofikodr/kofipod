// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playlists

/**
 * Pure-Kotlin in-memory resolver for [SmartPlaylistPredicate].
 *
 * All filter dimensions are AND-combined; a `null` predicate field means
 * "user did not pick this chip" and is treated as a pass-through. Empty (but
 * non-null) `podcastIds` is also a pass-through to model the editor's
 * "no podcasts selected" UX state.
 *
 * `nowMs` is supplied by the caller so commonMain stays free of JVM-only
 * `System.currentTimeMillis()`. Results are returned newest-first by
 * `publishedAtMs` to match the Library row's expected ordering.
 */
class PredicateEvaluator {
    fun evaluate(
        predicate: SmartPlaylistPredicate,
        facts: List<EpisodeFacts>,
        nowMs: Long,
    ): List<EpisodeFacts> {
        val cutoff = predicate.maxAgeDays?.let { nowMs - it * MS_PER_DAY }
        return facts
            .filter { f ->
                val statePass = predicate.state?.let { it == f.playState } ?: true
                val minPass = predicate.durationRange?.minSec?.let { f.durationSec >= it } ?: true
                val maxPass = predicate.durationRange?.maxSec?.let { f.durationSec <= it } ?: true
                val podPass = predicate.podcastIds?.let { ids -> ids.isEmpty() || f.podcastId in ids } ?: true
                val agePass = cutoff?.let { f.publishedAtMs >= it } ?: true
                val transcriptPass =
                    predicate.hasTranscript?.let { wanted ->
                        val has = !f.transcriptUrl.isNullOrBlank() || f.hasCachedTranscript
                        has == wanted
                    } ?: true
                val dlPass = if (predicate.downloadedOnly == true) f.isDownloaded else true
                val snipPass = predicate.hasSnippets?.let { it == f.hasSnippets } ?: true
                statePass && minPass && maxPass && podPass && agePass && transcriptPass && dlPass && snipPass
            }
            .sortedByDescending { it.publishedAtMs }
    }

    private companion object {
        const val MS_PER_DAY: Long = 24L * 60 * 60 * 1000
    }
}
