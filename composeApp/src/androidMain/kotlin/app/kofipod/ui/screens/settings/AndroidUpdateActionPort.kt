// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import app.kofipod.update.UpdateInfo
import app.kofipod.update.UpdateInstaller

class AndroidUpdateActionPort(
    private val installer: UpdateInstaller,
) : UpdateActionPort {
    override suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String = installer.download(info, onProgress)

    override fun installApk(path: String) {
        installer.install(path)
    }

    override fun canInstall(): Boolean = installer.canRequestInstall()

    override fun openInstallPermissionSettings() = installer.openInstallPermissionSettings()
}
