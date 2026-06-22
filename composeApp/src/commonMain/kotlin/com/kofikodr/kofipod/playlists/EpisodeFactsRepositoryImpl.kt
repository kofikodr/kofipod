// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playlists

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.snippets.FileCheckerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * SQLDelight-backed [EpisodeFactsRepository] for the Smart Playlist resolver.
 *
 * Combines five reactive query streams (Episode, PlaybackState, Download,
 * Snippet-episode-ids, TranscriptCache) into a single [Flow] of `List<EpisodeFacts>`.
 * Every stream is observed via SQLDelight's `asFlow()` + `mapToList()` so the
 * combined output re-emits whenever any participating table changes — that is
 * the contract the resolver in Task 7 relies on to keep matched-count and
 * preview rows live as the user listens / downloads / snips.
 *
 * The combine block builds two `Map`s and two `Set`s once per emission and
 * then walks `episodes` exactly once. Episode count is the dominant scale here
 * (one row per episode across every subscribed show); the auxiliary tables
 * are sparse subsets keyed by `episodeId`, so the per-emission work is O(N)
 * with small constants. We intentionally avoid pre-computing in SQL because
 * the predicate evaluator (Task 4) wants the union of all signals as plain
 * Kotlin data — pushing the join into SQLDelight would force a custom row type
 * for every predicate combination.
 *
 * `Dispatchers.Default` rather than `Dispatchers.IO` because `IO` is JVM-only
 * and the playlists package must stay iOS-compatible.
 *
 * `isDownloaded` is gated on the file actually existing on disk via [fileChecker],
 * not just on `state='Completed'` (issue #33). A `Download` row can claim
 * `Completed` with a `localPath` while the file is gone (external deletion,
 * DB-only restore, OS storage pruning); without the existence check a
 * `downloadedOnly` smart playlist would surface an episode that cannot play
 * offline — disagreeing with `DownloadRepository.localPathFor`, which already
 * treats a missing file as not-downloaded. The check here is read-only: it does
 * NOT delete the orphaned row (that would be a write inside a reactive `combine`,
 * risking a re-emission loop). The destructive self-heal stays in `localPathFor`,
 * which runs lazily on the next play attempt; both now agree that a missing file
 * means "not downloaded".
 *
 * On iOS this always reports `isDownloaded = false`: the platform has no download
 * engine and `FileChecker`'s iOS actual returns `false`, which is the correct
 * answer (there is never a downloaded file to surface).
 */
class EpisodeFactsRepositoryImpl(
    private val db: KofipodDatabase,
    private val fileChecker: FileCheckerApi,
) : EpisodeFactsRepository {
    override fun observeAll(): Flow<List<EpisodeFacts>> =
        combine(
            db.episodeQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
            db.playbackStateQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
            db.downloadQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
            db.snippetQueries.selectEpisodeIdsWithSnippets().asFlow().mapToList(Dispatchers.Default),
            db.transcriptCacheQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
        ) { episodes, playbackStates, downloads, snippetEpisodeIds, transcripts ->
            val playbackByEp = playbackStates.associateBy { it.episodeId }
            val downloadByEp = downloads.associateBy { it.episodeId }
            val snippetEps: Set<String> = snippetEpisodeIds.toHashSet()
            val transcriptEps: Set<String> = transcripts.map { it.episodeId }.toHashSet()
            episodes.map { e ->
                val ps = playbackByEp[e.id]
                val state =
                    when {
                        ps?.completedAt != null -> PlayState.Completed
                        (ps?.positionMs ?: 0L) > 0L -> PlayState.InProgress
                        else -> PlayState.Unplayed
                    }
                val dl = downloadByEp[e.id]
                EpisodeFacts(
                    episodeId = e.id,
                    episodeTitle = e.title,
                    podcastId = e.podcastId,
                    publishedAtMs = e.publishedAt,
                    durationSec = e.durationSec.toInt(),
                    transcriptUrl = e.transcriptUrl,
                    hasCachedTranscript = e.id in transcriptEps,
                    hasSnippets = e.id in snippetEps,
                    isDownloaded = dl?.state == "Completed" && dl.localPath?.let(fileChecker::exists) == true,
                    playState = state,
                )
            }
        }
}
