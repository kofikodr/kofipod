// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.config.AppInfo
import app.kofipod.update.LocalApkPathStore
import app.kofipod.update.UpdateInfo
import app.kofipod.update.compareSemver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * State machine for the in-app update banner. Persistence is split:
 *
 * - **Backed-up state** (latest known version, dismissed version, last check time, the
 *   release notes / URL / size we discovered) lives on [SettingsRepository] (SyncMeta),
 *   so Auto Backup carries "I was at v1.1.2 and skipped v1.2.0" across reinstalls.
 *
 * - **Local-only state** — currently just the absolute path of the downloaded APK on
 *   this device — lives in [LocalApkPathStore], which is explicitly excluded from
 *   Auto Backup. This pairs with `filesDir/updates/` (also not backed up) so the path
 *   pointer cannot survive a device restore and reference a vanished file.
 */
sealed interface UpdateUiState {
    data class UpToDate(val lastCheckedAtMs: Long?) : UpdateUiState

    data class Available(
        val info: UpdateInfo,
        val lastCheckedAtMs: Long?,
    ) : UpdateUiState

    /** APK has been downloaded to local storage and is ready to be installed. */
    data class ReadyToInstall(
        val info: UpdateInfo,
        val apkPath: String,
        val lastCheckedAtMs: Long?,
    ) : UpdateUiState
}

class UpdateRepository(
    private val settings: SettingsRepository,
    private val localApk: LocalApkPathStore,
) {
    fun state(): Flow<UpdateUiState> =
        combine(
            settings.metaFlowPublic(KEY_UPDATE_LATEST_VERSION),
            settings.metaFlowPublic(KEY_UPDATE_DISMISSED_VERSION),
            localApk.pathFlow(),
            settings.metaFlowPublic(KEY_UPDATE_LAST_CHECK_AT),
        ) { latest, dismissed, apkPath, lastCheck ->
            val lastMs = lastCheck?.toLongOrNull()
            val info = readUpdateInfoSnapshot()
            val cleanApkPath = apkPath?.takeIf { it.isNotEmpty() }
            val cleanDismissed = dismissed?.takeIf { it.isNotEmpty() }
            // If the user has installed an APK out-of-band (or via our installer) and the
            // running version already matches/exceeds the cached "available" version, the
            // banner must flip back to "up to date" without waiting for the next periodic
            // check. Clear the stale row so it doesn't reappear after a process restart.
            val installedCoversAvailable = info != null && compareSemver(AppInfo.versionName, info.version) >= 0
            if (installedCoversAvailable) {
                clearAvailable()
            }
            when {
                info == null || installedCoversAvailable -> UpdateUiState.UpToDate(lastMs)
                cleanDismissed == latest -> UpdateUiState.UpToDate(lastMs)
                cleanApkPath != null -> UpdateUiState.ReadyToInstall(info, cleanApkPath, lastMs)
                else -> UpdateUiState.Available(info, lastMs)
            }
        }

    fun readUpdateInfoSnapshot(): UpdateInfo? {
        val version = settings.getMetaNow(KEY_UPDATE_LATEST_VERSION)?.takeIf { it.isNotEmpty() } ?: return null
        val releaseUrl = settings.getMetaNow(KEY_UPDATE_RELEASE_URL)?.takeIf { it.isNotEmpty() } ?: return null
        val apkUrl = settings.getMetaNow(KEY_UPDATE_APK_URL)?.takeIf { it.isNotEmpty() } ?: return null
        val size = settings.getMetaNow(KEY_UPDATE_APK_SIZE)?.toLongOrNull() ?: 0L
        val notes = settings.getMetaNow(KEY_UPDATE_RELEASE_NOTES).orEmpty()
        return UpdateInfo(
            version = version,
            releaseUrl = releaseUrl,
            apkUrl = apkUrl,
            apkSizeBytes = size,
            releaseNotes = notes,
        )
    }

    fun dismissedVersionNow(): String? = settings.getMetaNow(KEY_UPDATE_DISMISSED_VERSION)?.takeIf { it.isNotEmpty() }

    fun downloadedApkPathNow(): String? = localApk.pathNow()

    fun lastCheckAtMsNow(): Long? = settings.getMetaNow(KEY_UPDATE_LAST_CHECK_AT)?.toLongOrNull()

    fun storeAvailable(info: UpdateInfo) {
        // If the discovered version actually changed, invalidate the cached APK path so
        // a half-downloaded older release can't be installed against a newer release row.
        // When the worker re-confirms the same pending release we leave the path alone,
        // otherwise every 24h cycle would silently wipe a successful download.
        val existing = settings.getMetaNow(KEY_UPDATE_LATEST_VERSION)?.takeIf { it.isNotEmpty() }
        settings.put(KEY_UPDATE_LATEST_VERSION, info.version)
        settings.put(KEY_UPDATE_RELEASE_URL, info.releaseUrl)
        settings.put(KEY_UPDATE_APK_URL, info.apkUrl)
        settings.put(KEY_UPDATE_APK_SIZE, info.apkSizeBytes.toString())
        settings.put(KEY_UPDATE_RELEASE_NOTES, info.releaseNotes)
        if (existing != info.version) {
            localApk.setPath(null)
        }
    }

    fun clearAvailable() {
        settings.put(KEY_UPDATE_LATEST_VERSION, "")
        settings.put(KEY_UPDATE_RELEASE_URL, "")
        settings.put(KEY_UPDATE_APK_URL, "")
        settings.put(KEY_UPDATE_APK_SIZE, "")
        settings.put(KEY_UPDATE_RELEASE_NOTES, "")
        localApk.setPath(null)
    }

    fun markChecked(timestampMs: Long) {
        settings.put(KEY_UPDATE_LAST_CHECK_AT, timestampMs.toString())
    }

    fun markApkDownloaded(absolutePath: String) {
        localApk.setPath(absolutePath.takeIf { it.isNotEmpty() })
    }

    fun dismissCurrentVersion() {
        val version = settings.getMetaNow(KEY_UPDATE_LATEST_VERSION)?.takeIf { it.isNotEmpty() } ?: return
        settings.put(KEY_UPDATE_DISMISSED_VERSION, version)
    }

    private companion object {
        // Temp-inlined from SettingsRepository as part of pre-Slice-0 updater cleanup
        // Task 5. The whole UpdateRepository class is deleted in Task 9; these constants
        // exist only to keep the file compiling in the meantime.
        const val KEY_UPDATE_LATEST_VERSION = "update_latest_version"
        const val KEY_UPDATE_RELEASE_URL = "update_release_url"
        const val KEY_UPDATE_APK_URL = "update_apk_url"
        const val KEY_UPDATE_APK_SIZE = "update_apk_size_bytes"
        const val KEY_UPDATE_RELEASE_NOTES = "update_release_notes"
        const val KEY_UPDATE_DISMISSED_VERSION = "update_dismissed_version"
        const val KEY_UPDATE_LAST_CHECK_AT = "update_last_check_at_ms"
    }
}
