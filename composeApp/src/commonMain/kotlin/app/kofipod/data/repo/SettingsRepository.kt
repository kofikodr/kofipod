// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import app.kofipod.ui.theme.KofipodThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val db: KofipodDatabase) {

    private fun metaFlow(key: String): Flow<String?> =
        db.syncMetaQueries.get(key).asFlow().mapToOneOrNull(Dispatchers.Default)

    fun put(key: String, value: String) = db.syncMetaQueries.put(key, value)

    fun storageCapBytes(): Flow<Long> =
        metaFlow(KEY_STORAGE_CAP).map { it?.toLongOrNull() ?: DEFAULT_CAP_BYTES }

    fun setStorageCapBytes(bytes: Long) = put(KEY_STORAGE_CAP, bytes.toString())

    fun themeMode(): Flow<KofipodThemeMode> =
        metaFlow(KEY_THEME).map {
            runCatching { KofipodThemeMode.valueOf(it ?: "System") }
                .getOrDefault(KofipodThemeMode.System)
        }

    fun setThemeMode(mode: KofipodThemeMode) = put(KEY_THEME, mode.name)

    fun dailyCheckEnabled(): Flow<Boolean> =
        metaFlow(KEY_DAILY_CHECK).map { it?.toBoolean() ?: true }

    fun setDailyCheckEnabled(enabled: Boolean) = put(KEY_DAILY_CHECK, enabled.toString())

    fun skipForwardSeconds(): Flow<Int> =
        metaFlow(KEY_SKIP_FWD).map { it?.toIntOrNull() ?: 30 }

    fun skipBackSeconds(): Flow<Int> =
        metaFlow(KEY_SKIP_BACK).map { it?.toIntOrNull() ?: 10 }

    fun setSkipForward(sec: Int) = put(KEY_SKIP_FWD, sec.toString())
    fun setSkipBack(sec: Int) = put(KEY_SKIP_BACK, sec.toString())

    fun backupEnabled(): Flow<Boolean> =
        metaFlow(KEY_BACKUP_ENABLED).map { it?.toBoolean() ?: false }

    fun setBackupEnabled(enabled: Boolean) = put(KEY_BACKUP_ENABLED, enabled.toString())

    fun googleEmail(): Flow<String?> = metaFlow(KEY_GOOGLE_EMAIL)

    fun setGoogleEmail(email: String?) {
        if (email == null) db.syncMetaQueries.put(KEY_GOOGLE_EMAIL, "")
        else put(KEY_GOOGLE_EMAIL, email)
    }

    fun getDriveAccessTokenNow(): String? =
        getMetaNow(KEY_DRIVE_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }

    fun setDriveAccessToken(token: String?) {
        put(KEY_DRIVE_ACCESS_TOKEN, token.orEmpty())
    }

    fun lastBackupAt(): Flow<Long?> =
        metaFlow(KEY_LAST_BACKUP_AT).map { it?.toLongOrNull() }

    fun setLastBackupAt(millis: Long) = put(KEY_LAST_BACKUP_AT, millis.toString())

    fun getBackupVersionNow(): Long? = getMetaNow(KEY_BACKUP_VERSION)?.toLongOrNull()

    fun setBackupVersion(version: Long) = put(KEY_BACKUP_VERSION, version.toString())

    fun getMetaNow(key: String): String? =
        db.syncMetaQueries.get(key).executeAsOneOrNull()

    fun onboardedNow(): Boolean = getMetaNow(KEY_ONBOARDED)?.toBoolean() ?: false

    fun setOnboarded(done: Boolean) = put(KEY_ONBOARDED, done.toString())

    companion object {
        const val DEFAULT_CAP_BYTES: Long = 2L * 1024 * 1024 * 1024 // 2 GB
        const val KEY_STORAGE_CAP = "storage_cap_bytes"
        const val KEY_THEME = "theme_mode"
        const val KEY_DAILY_CHECK = "daily_check_enabled"
        const val KEY_SKIP_FWD = "skip_forward_sec"
        const val KEY_SKIP_BACK = "skip_back_sec"
        const val KEY_SCHEDULER_RUNS = "scheduler_runs"
        const val KEY_BACKUP_ENABLED = "backup_enabled"
        const val KEY_GOOGLE_EMAIL = "google_email"
        const val KEY_DRIVE_ACCESS_TOKEN = "drive_access_token"
        const val KEY_LAST_BACKUP_AT = "last_backup_at_millis"
        const val KEY_BACKUP_VERSION = "drive_backup_version"
        const val KEY_ONBOARDED = "onboarded"
    }
}
