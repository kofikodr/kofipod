// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import app.kofipod.update.UpdateInfo

/**
 * Surfaces APK download/install operations to the common-main ViewModel without
 * pulling Android types into commonMain. Android binds this to [app.kofipod.update.UpdateInstaller];
 * iOS binds a no-op (the UI itself isn't shown on iOS but the binding keeps Koin happy).
 */
interface UpdateActionPort {
    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String

    fun installApk(path: String)

    fun canInstall(): Boolean

    fun openInstallPermissionSettings()
}
