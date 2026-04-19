// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.backup

import kotlinx.serialization.Serializable

/**
 * Versioned on-the-wire shape of the Drive backup blob.
 *
 * Any schema change that breaks compatibility MUST bump [BackupBlob.schemaVersion]
 * and add a migration path in `BackupService.readBlob` — old clients reading a
 * newer blob should refuse to restore rather than corrupt the DB.
 */
@Serializable
data class BackupBlob(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val createdAtMillis: Long,
    val backupVersion: Long,
    val lists: List<BackupList> = emptyList(),
    val podcasts: List<BackupPodcast> = emptyList(),
    val episodes: List<BackupEpisode> = emptyList(),
    val playbackStates: List<BackupPlayback> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class BackupList(
    val id: String,
    val name: String,
    val position: Long,
    val createdAt: Long,
)

@Serializable
data class BackupPodcast(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val feedUrl: String,
    val listId: String? = null,
    val autoDownloadEnabled: Boolean,
    val notifyNewEpisodesEnabled: Boolean,
    val lastCheckedAt: Long? = null,
    val addedAt: Long,
)

@Serializable
data class BackupEpisode(
    val id: String,
    val podcastId: String,
    val guid: String,
    val title: String,
    val description: String,
    val publishedAt: Long,
    val durationSec: Long,
    val enclosureUrl: String,
    val enclosureMimeType: String,
    val fileSizeBytes: Long,
    val seasonNumber: Long? = null,
    val episodeNumber: Long? = null,
)

@Serializable
data class BackupPlayback(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completedAt: Long? = null,
    val playbackSpeed: Double,
    val updatedAt: Long,
)
