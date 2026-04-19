// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.backup

import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Merges a [BackupBlob] into the local database.
 *
 * Conflict strategy:
 *  - **Lists** are upserted by id. A remote rename wins over a local rename
 *    of the same id (we only have `INSERT` / `rename` / `delete` queries on
 *    PodcastList; since the current schema has no updatedAt column, newer-wins
 *    by timestamp isn't available — the blob is treated as authoritative).
 *  - **Podcasts** use `INSERT OR REPLACE`, which restores all columns
 *    including `listId`, `autoDownloadEnabled`, `notifyNewEpisodesEnabled`.
 *    A local change made since the backup was taken will be overwritten — the
 *    user explicitly opted in by tapping Restore.
 *  - **Episodes** use `INSERT OR IGNORE`, which preserves whatever the local
 *    DB already has (episodes are content-addressed by podcast feed; the
 *    feed refresh is the source of truth). The restorer only fills gaps so
 *    [PlaybackState] rows have a valid FK target.
 *  - **PlaybackState** uses newer-wins by `updatedAt` — otherwise restoring
 *    could overwrite a position the user just set on this device.
 *  - **Meta** keys from [BackupService.SYNCED_META_KEYS] are written
 *    unconditionally; auth-bearing keys are never backed up so they're
 *    unaffected.
 */
class BackupRestorer(
    private val db: KofipodDatabase,
    private val settings: SettingsRepository,
) {
    suspend fun restore(blob: BackupBlob): RestoreSummary =
        withContext(Dispatchers.Default) {
            var listsInserted = 0
            var podcastsInserted = 0
            var podcastsUpdated = 0
            var episodesInserted = 0
            var playbackApplied = 0

            db.transaction {
                val existingListIds =
                    db.podcastListQueries.selectAll().executeAsList()
                        .associateBy { it.id }
                for (l in blob.lists) {
                    val existing = existingListIds[l.id]
                    if (existing == null) {
                        db.podcastListQueries.insert(l.id, l.name, l.position, l.createdAt)
                        listsInserted++
                    } else if (existing.name != l.name) {
                        db.podcastListQueries.rename(l.name, l.id)
                    }
                }

                val existingPodcastIds =
                    db.podcastQueries.selectAll().executeAsList()
                        .map { it.id }.toSet()
                for (p in blob.podcasts) {
                    db.podcastQueries.insert(
                        id = p.id,
                        title = p.title,
                        author = p.author,
                        description = p.description,
                        artworkUrl = p.artworkUrl,
                        feedUrl = p.feedUrl,
                        listId = p.listId,
                        autoDownloadEnabled = if (p.autoDownloadEnabled) 1 else 0,
                        notifyNewEpisodesEnabled = if (p.notifyNewEpisodesEnabled) 1 else 0,
                        lastCheckedAt = p.lastCheckedAt,
                        addedAt = p.addedAt,
                    )
                    if (p.id in existingPodcastIds) podcastsUpdated++ else podcastsInserted++
                }

                for (e in blob.episodes) {
                    val beforeCount = db.episodeQueries.selectById(e.id).executeAsOneOrNull()
                    if (beforeCount != null) continue
                    db.episodeQueries.insert(
                        id = e.id,
                        podcastId = e.podcastId,
                        guid = e.guid,
                        title = e.title,
                        description = e.description,
                        publishedAt = e.publishedAt,
                        durationSec = e.durationSec,
                        enclosureUrl = e.enclosureUrl,
                        enclosureMimeType = e.enclosureMimeType,
                        fileSizeBytes = e.fileSizeBytes,
                        seasonNumber = e.seasonNumber,
                        episodeNumber = e.episodeNumber,
                    )
                    episodesInserted++
                }

                for (s in blob.playbackStates) {
                    val existing =
                        db.playbackStateQueries.selectByEpisode(s.episodeId)
                            .executeAsOneOrNull()
                    // Only write if the remote row is strictly newer — this preserves
                    // progress the user made on this device after the backup was taken.
                    if (existing != null && existing.updatedAt >= s.updatedAt) continue
                    // Guard the FK: if we don't have the episode row (no blob entry
                    // and not locally known), skip rather than error. The feed
                    // refresh may fill it in later; we'd rather lose a position than
                    // crash the whole restore.
                    db.episodeQueries.selectById(s.episodeId).executeAsOneOrNull()
                        ?: continue
                    db.playbackStateQueries.upsert(
                        episodeId = s.episodeId,
                        positionMs = s.positionMs,
                        durationMs = s.durationMs,
                        completedAt = s.completedAt,
                        playbackSpeed = s.playbackSpeed,
                        updatedAt = s.updatedAt,
                    )
                    playbackApplied++
                }

                for ((key, value) in blob.meta) {
                    if (key !in BackupService.SYNCED_META_KEYS) continue
                    settings.put(key, value)
                }

                settings.setBackupVersion(blob.backupVersion)
            }

            RestoreSummary(
                listsInserted = listsInserted,
                podcastsInserted = podcastsInserted,
                podcastsUpdated = podcastsUpdated,
                episodesInserted = episodesInserted,
                playbackApplied = playbackApplied,
            )
        }
}

data class RestoreSummary(
    val listsInserted: Int,
    val podcastsInserted: Int,
    val podcastsUpdated: Int,
    val episodesInserted: Int,
    val playbackApplied: Int,
)
