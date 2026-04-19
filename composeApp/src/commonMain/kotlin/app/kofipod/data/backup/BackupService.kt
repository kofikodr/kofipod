// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.backup

import app.kofipod.data.drive.DriveClient
import app.kofipod.data.drive.DriveException
import app.kofipod.data.drive.DriveFileRef
import app.kofipod.data.drive.DriveTokenProvider
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Packages a local library snapshot into a [BackupBlob], serialises as JSON,
 * and round-trips it through Drive's `appDataFolder`.
 *
 * The service is deliberately thin — VMs call [runBackup] / [readRemoteBlob]
 * and get back a result object. Restore merge logic lives in [BackupRestorer]
 * (next slice) to keep serialisation and DB-mutation concerns separate.
 */
class BackupService(
    private val db: KofipodDatabase,
    private val drive: DriveClient,
    private val tokens: DriveTokenProvider,
    private val settings: SettingsRepository,
) {

    /**
     * Builds the blob from the current DB, uploads it to Drive, bumps the
     * backup version, and stamps [SettingsRepository.KEY_LAST_BACKUP_AT].
     *
     * Returns the remote [DriveFileRef] so callers can display its
     * modifiedTime if they want. On 401, the token is forcibly refreshed
     * and the upload retried once.
     */
    suspend fun runBackup(): BackupOutcome = withContext(Dispatchers.Default) {
        val snapshot = buildSnapshot()
        val json = BACKUP_JSON.encodeToString(BackupBlob.serializer(), snapshot)

        val existing = tryFindExisting()
        val uploaded = try {
            drive.upload(
                name = DriveClient.BACKUP_FILE_NAME,
                content = json,
                existingFileId = existing?.id,
            )
        } catch (e: DriveException.Unauthorized) {
            // 401: access token expired or revoked. Force a refresh and retry
            // exactly once. Silent re-authorize works as long as the scope
            // is still granted; otherwise the caller gets the failure.
            tokens.refresh()
            drive.upload(
                name = DriveClient.BACKUP_FILE_NAME,
                content = json,
                existingFileId = existing?.id,
            )
        }

        settings.setLastBackupAt(Clock.System.now().toEpochMilliseconds())
        settings.setBackupVersion(snapshot.backupVersion)
        BackupOutcome(
            file = uploaded,
            backupVersion = snapshot.backupVersion,
            sizeBytes = json.length.toLong(),
        )
    }

    /**
     * Downloads and decodes the remote blob. Returns null if no backup exists
     * yet for this account. Throws [BackupSchemaException] when the remote
     * blob's schema version is newer than we can read.
     */
    suspend fun readRemoteBlob(): RemoteBlob? = withContext(Dispatchers.Default) {
        val existing = tryFindExisting() ?: return@withContext null
        val text = try {
            drive.downloadText(existing.id)
        } catch (e: DriveException.Unauthorized) {
            tokens.refresh()
            drive.downloadText(existing.id)
        }
        val blob = try {
            BACKUP_JSON.decodeFromString(BackupBlob.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupSchemaException("Backup blob is malformed: ${e.message}")
        }
        if (blob.schemaVersion > BackupBlob.CURRENT_SCHEMA_VERSION) {
            throw BackupSchemaException(
                "Backup schema ${blob.schemaVersion} is newer than supported " +
                    "(${BackupBlob.CURRENT_SCHEMA_VERSION}). Update the app to restore.",
            )
        }
        RemoteBlob(blob = blob, file = existing)
    }

    private suspend fun tryFindExisting(): DriveFileRef? =
        try {
            drive.findAppDataFile(DriveClient.BACKUP_FILE_NAME)
        } catch (e: DriveException.Unauthorized) {
            tokens.refresh()
            drive.findAppDataFile(DriveClient.BACKUP_FILE_NAME)
        }

    private fun buildSnapshot(): BackupBlob {
        // SQLDelight queries are synchronous; wrap the whole read in a single
        // call to minimise time the user waits. DB reads are fast — the slow
        // step will be the Drive upload.
        val lists = db.podcastListQueries.selectAll().executeAsList().map {
            BackupList(
                id = it.id,
                name = it.name,
                position = it.position,
                createdAt = it.createdAt,
            )
        }
        val podcasts = db.podcastQueries.selectAll().executeAsList().map {
            BackupPodcast(
                id = it.id,
                title = it.title,
                author = it.author,
                description = it.description,
                artworkUrl = it.artworkUrl,
                feedUrl = it.feedUrl,
                listId = it.listId,
                autoDownloadEnabled = it.autoDownloadEnabled != 0L,
                notifyNewEpisodesEnabled = it.notifyNewEpisodesEnabled != 0L,
                lastCheckedAt = it.lastCheckedAt,
                addedAt = it.addedAt,
            )
        }
        val episodes = podcasts.flatMap { p ->
            db.episodeQueries.selectByPodcast(p.id).executeAsList().map { e ->
                BackupEpisode(
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
            }
        }
        val playback = db.playbackStateQueries.selectAll().executeAsList().map {
            BackupPlayback(
                episodeId = it.episodeId,
                positionMs = it.positionMs,
                durationMs = it.durationMs,
                completedAt = it.completedAt,
                playbackSpeed = it.playbackSpeed,
                updatedAt = it.updatedAt,
            )
        }
        val meta = SYNCED_META_KEYS.mapNotNull { key ->
            settings.getMetaNow(key)?.let { key to it }
        }.toMap()

        val nextVersion = (settings.getBackupVersionNow() ?: 0L) + 1
        return BackupBlob(
            createdAtMillis = Clock.System.now().toEpochMilliseconds(),
            backupVersion = nextVersion,
            lists = lists,
            podcasts = podcasts,
            episodes = episodes,
            playbackStates = playback,
            meta = meta,
        )
    }

    companion object {
        /**
         * Keys safe to round-trip through backup. Auth-bearing keys
         * (`drive_access_token`, `google_email`) and device-local state
         * (`scheduler_runs`) are intentionally excluded.
         */
        val SYNCED_META_KEYS: Set<String> = setOf(
            SettingsRepository.KEY_THEME,
            SettingsRepository.KEY_DAILY_CHECK,
            SettingsRepository.KEY_STORAGE_CAP,
            SettingsRepository.KEY_SKIP_FWD,
            SettingsRepository.KEY_SKIP_BACK,
        )

        val BACKUP_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

data class RemoteBlob(val blob: BackupBlob, val file: DriveFileRef)

data class BackupOutcome(
    val file: DriveFileRef,
    val backupVersion: Long,
    val sizeBytes: Long,
)

class BackupSchemaException(message: String) : Exception(message)
