// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.db.KofipodDatabase
import app.kofipod.db.PlaybackState

data class InProgressEpisode(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val episodeTitle: String,
    val podcastId: String,
    val podcastTitle: String,
    val artworkUrl: String,
)

class PlaybackRepository(private val db: KofipodDatabase) {
    fun save(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        updatedAt: Long,
        episodeTitle: String,
        podcastId: String,
        podcastTitle: String,
        artworkUrl: String,
        sourceUrl: String,
        episodeNumber: Int?,
    ) {
        db.playbackStateQueries.upsert(
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
            completedAt = null,
            playbackSpeed = speed.toDouble(),
            updatedAt = updatedAt,
            episodeTitle = episodeTitle,
            podcastId = podcastId,
            podcastTitle = podcastTitle,
            artworkUrl = artworkUrl,
            sourceUrl = sourceUrl,
            episodeNumber = episodeNumber?.toLong(),
        )
    }

    fun positionFor(episodeId: String): Long =
        db.playbackStateQueries
            .selectByEpisode(episodeId)
            .executeAsOneOrNull()
            ?.positionMs ?: 0L

    fun mostRecentIncomplete(): PlaybackState? =
        db.playbackStateQueries
            .selectMostRecentIncomplete()
            .executeAsOneOrNull()

    fun inProgressNow(maxRows: Long = 25L): List<InProgressEpisode> =
        db.playbackStateQueries
            .selectInProgressWithMeta(maxRows)
            .executeAsList()
            .map {
                InProgressEpisode(
                    episodeId = it.episodeId,
                    positionMs = it.positionMs,
                    durationMs = it.durationMs,
                    episodeTitle = it.episodeTitle,
                    podcastId = it.podcastId,
                    podcastTitle = it.podcastTitle,
                    artworkUrl = it.artworkUrl,
                )
            }

    fun markCompleted(
        episodeId: String,
        nowMillis: Long,
        currentDurationMs: Long = 0L,
    ) {
        val existing = db.playbackStateQueries.selectByEpisode(episodeId).executeAsOneOrNull()
        val dur = existing?.durationMs?.takeIf { it > 0 } ?: currentDurationMs
        db.playbackStateQueries.upsert(
            episodeId = episodeId,
            positionMs = dur,
            durationMs = dur,
            completedAt = nowMillis,
            playbackSpeed = existing?.playbackSpeed ?: 1.0,
            updatedAt = nowMillis,
            episodeTitle = existing?.episodeTitle.orEmpty(),
            podcastId = existing?.podcastId.orEmpty(),
            podcastTitle = existing?.podcastTitle.orEmpty(),
            artworkUrl = existing?.artworkUrl.orEmpty(),
            sourceUrl = existing?.sourceUrl.orEmpty(),
            episodeNumber = existing?.episodeNumber,
        )
    }
}
