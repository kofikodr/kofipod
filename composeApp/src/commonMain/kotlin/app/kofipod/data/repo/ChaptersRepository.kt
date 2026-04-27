// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.data.net.kofipodJson
import app.kofipod.db.EpisodeChapter
import app.kofipod.db.KofipodDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
) {
    fun chaptersFlow(episodeId: String): Flow<List<EpisodeChapter>> =
        db.episodeChapterQueries.selectByEpisode(episodeId).asFlow().mapToList(Dispatchers.Default)

    fun hasCached(episodeId: String): Boolean = db.episodeChapterQueries.countByEpisode(episodeId).executeAsOne() > 0L

    /**
     * Hits [url], parses the chapters JSON, and replaces any cached rows for
     * [episodeId]. No-ops on network/parse failure — callers should observe the
     * persisted Flow rather than awaiting this result.
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
                val parsed = kofipodJson.decodeFromString<ChaptersJson>(body)
                val rows =
                    parsed.chapters.orEmpty().mapIndexedNotNull { index, chapter ->
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
