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
}
