// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import app.kofipod.update.UpdateInfo

/** iOS doesn't sideload — these are dead code at runtime, but keep Koin's graph identical. */
class IosUpdateActionPort : UpdateActionPort {
    override suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String = ""

    override fun installApk(path: String) {}

    override fun canInstall(): Boolean = false

    override fun openInstallPermissionSettings() {}
}
