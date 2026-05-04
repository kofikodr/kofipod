// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory empty implementation. iOS doesn't surface backup UI in v1; this exists only
 * to keep the Koin graph consistent across targets so iOS compile stays green.
 */
class IosBackupFolderStore : BackupFolderStore {
    override fun treeUriNow(): String? = null

    override fun setTreeUri(uri: String?) {
        // No-op
    }

    override fun treeUriFlow(): Flow<String?> = flowOf(null)

    override fun lastBackupAtNow(): Long? = null

    override fun setLastBackupAt(ms: Long?) {
        // No-op
    }

    override fun lastBackupAtFlow(): Flow<Long?> = flowOf(null)

    override fun pendingRestoreFilenameNow(): String? = null

    override fun setPendingRestoreFilename(name: String?) {
        // No-op
    }

    override fun displayNameForTreeUri(uri: String): String? = null

    override fun consumeRestoreCompletedFlag(): Boolean = false
}
