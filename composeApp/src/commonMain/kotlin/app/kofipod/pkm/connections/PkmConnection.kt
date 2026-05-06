// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

data class PkmConnection(
    val id: String,
    val kind: ConnectionKind,
    val tokenRef: String?,
    val folderUri: String?,
    val enabledAtMs: Long,
    val lastSyncAtMs: Long?,
)
