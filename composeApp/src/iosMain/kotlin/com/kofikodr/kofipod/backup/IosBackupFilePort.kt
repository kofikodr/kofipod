// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

/**
 * No-op iOS binding so Koin's graph stays consistent with Android. The SAF backup
 * surface isn't wired into iOS UI in v1; calling these returns "cancelled" / throws,
 * which is the closest harmless fallback if the surface ever does get exposed.
 */
class IosBackupFilePort : BackupFilePort {
    override suspend fun pickFolder(): String? = null

    override suspend fun pickAndReadBackup(): ByteArray? = null

    override suspend fun writeBackup(
        treeUri: String,
        content: ByteArray,
    ) {
        error("backup not supported on iOS")
    }
}
