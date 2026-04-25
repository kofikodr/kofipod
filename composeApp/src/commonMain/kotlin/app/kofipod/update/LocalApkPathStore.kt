// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

import kotlinx.coroutines.flow.Flow

/**
 * Device-local pointer to the currently downloaded update APK. Deliberately stored
 * **outside** the backed-up SQLDelight database: the APK file itself lives under
 * `filesDir/updates/` (not in any Auto Backup `<include>`), so the pointer must not
 * survive a device restore either — otherwise we'd "remember" a downloaded APK on
 * a fresh device where the file no longer exists.
 *
 * Boundary interface so commonMain code (notably [app.kofipod.data.repo.UpdateRepository])
 * and JVM unit tests can use it without taking a hard dependency on Android's
 * SharedPreferences / Context. Android: [AndroidLocalApkPathStore] backs it with a
 * SharedPreferences file (`kofipod_local`) explicitly excluded from Auto Backup.
 * iOS: [IosLocalApkPathStore] is a no-op (iOS doesn't sideload).
 */
interface LocalApkPathStore {
    fun pathNow(): String?

    fun setPath(path: String?)

    fun pathFlow(): Flow<String?>
}
