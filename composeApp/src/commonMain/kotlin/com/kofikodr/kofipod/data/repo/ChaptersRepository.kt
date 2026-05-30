// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.kofikodr.kofipod.data.net.kofipodJson
import com.kofikodr.kofipod.db.EpisodeChapter
import com.kofikodr.kofipod.db.KofipodDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * Fetches and caches Podcasting 2.0 chapter data per episode. The chapters JSON URL
 * lives on the Episode row (`chaptersUrl`); we only hit the network on demand and
 * persist to [EpisodeChapter] so subsequent opens are instant + offline-friendly.
 *
 * Spec: https://github.com/Podcastindex-org/podcast-namespace/blob/main/proposal-docs/chapters/jsonChapters.md
 */
class ChaptersRepository(
    private val db: KofipodDatabase,
    private val http: HttpClient,
    // Injectable so flow-driven VM tests can route SQLDelight emissions through a test
    // scheduler (mirrors LibraryRepository.queryDispatcher). Production uses Default.
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // Single Mutex covers all refreshes — chapter fetches are rare (only on detail-screen
    // open of an episode with chaptersUrl + no cached rows), so contention is negligible
    // and a per-episode keyed map would be over-engineered.
    private val refreshLock = Mutex()

    fun chaptersFlow(episodeId: String): Flow<List<EpisodeChapter>> =
        db.episodeChapterQueries.selectByEpisode(episodeId).asFlow().mapToList(queryDispatcher)

    fun hasCached(episodeId: String): Boolean = db.episodeChapterQueries.countByEpisode(episodeId).executeAsOne() > 0L

    /**
     * Idempotent entry point used by detail-screen open. Atomically checks the cache
     * and, on miss, fetches + replaces. Two concurrent callers for the same episode see
     * a single network round-trip — the second will observe a populated cache and bail.
     */
    suspend fun ensureCached(
        episodeId: String,
        url: String,
    ): Result<Int> =
        refreshLock.withLock {
            if (hasCached(episodeId)) return@withLock Result.success(0)
            refresh(episodeId, url)
        }

    /**
     * Hits [url], parses the chapters JSON, and replaces any cached rows for
     * [episodeId] in a single transaction so observers never see a partial list.
     * No-ops on network/parse failure — callers should observe the persisted Flow
     * rather than awaiting this result. Prefer [ensureCached] for the common path;
     * call this directly only when you mean "force a refetch even if cached".
     */
    suspend fun refresh(
        episodeId: String,
        url: String,
    ): Result<Int> =
        runCatching {
            withContext(Dispatchers.Default) {
                val response = http.get(url)
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}")
                }
                val body = response.bodyAsText()
                val rows = parseChapters(episodeId, body)
                db.episodeChapterQueries.transaction {
                    db.episodeChapterQueries.deleteByEpisode(episodeId)
                    rows.forEach {
                        db.episodeChapterQueries.insert(
                            episodeId = it.episodeId,
                            seq = it.seq,
                            startMs = it.startMs,
                            title = it.title,
                            imageUrl = it.imageUrl,
                            linkUrl = it.linkUrl,
                        )
                    }
                }
                rows.size
            }
        }
}

@Serializable
private data class ChaptersJson(
    @SerialName("version") val version: String? = null,
    @SerialName("chapters") val chapters: List<ChapterEntry>? = null,
)

@Serializable
private data class ChapterEntry(
    @SerialName("startTime") val startTime: Double? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("img") val img: String? = null,
    @SerialName("url") val url: String? = null,
)

/**
 * Pure conversion: chapters JSON → DB rows for [episodeId]. Internal so the test suite
 * can exercise the rounding / null / blank-title branches without an HTTP layer.
 *
 * Invariants:
 *  - `startTime` is converted from float seconds to Long milliseconds via [roundToLong];
 *    negative values are clamped to 0 (the spec is non-negative but defensive).
 *  - Chapters with a null or blank title are dropped — the spec says title is required,
 *    and we'd rather hide a chapter than render an empty row.
 *  - `seq` is the source-array index, not the post-filter index, so dropped chapters
 *    create gaps. The DB ordering uses `seq ASC`, so this is fine.
 */
internal fun parseChapters(
    episodeId: String,
    json: String,
): List<EpisodeChapter> {
    val parsed = kofipodJson.decodeFromString<ChaptersJson>(json)
    return parsed.chapters.orEmpty().mapIndexedNotNull { index, chapter ->
        val title = chapter.title?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        EpisodeChapter(
            episodeId = episodeId,
            seq = index.toLong(),
            startMs = (chapter.startTime ?: 0.0).coerceAtLeast(0.0).times(1000.0).roundToLong(),
            title = title,
            imageUrl = chapter.img.orEmpty(),
            linkUrl = chapter.url.orEmpty(),
        )
    }
}
