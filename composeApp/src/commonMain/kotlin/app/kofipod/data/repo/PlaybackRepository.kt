// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.db.KofipodDatabase

class PlaybackRepository(private val db: KofipodDatabase) {

    fun save(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        updatedAt: Long,
    ) {
        db.playbackStateQueries.upsert(
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
            completedAt = null,
            playbackSpeed = speed.toDouble(),
            updatedAt = updatedAt,
        )
    }

    fun positionFor(episodeId: String): Long =
        db.playbackStateQueries
            .selectByEpisode(episodeId)
            .executeAsOneOrNull()
            ?.positionMs ?: 0L

    fun markCompleted(episodeId: String, nowMillis: Long, currentDurationMs: Long = 0L) {
        val existing = db.playbackStateQueries.selectByEpisode(episodeId).executeAsOneOrNull()
        val dur = existing?.durationMs?.takeIf { it > 0 } ?: currentDurationMs
        db.playbackStateQueries.upsert(
            episodeId = episodeId,
            positionMs = dur,
            durationMs = dur,
            completedAt = nowMillis,
            playbackSpeed = existing?.playbackSpeed ?: 1.0,
            updatedAt = nowMillis,
        )
    }
}
