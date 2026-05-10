// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.connections

import com.kofikodr.kofipod.pkm.connections.ConnectionKind

data class ConnectionsUiState(
    val rows: List<ConnectionRow>,
    val readwiseDialogOpen: Boolean = false,
    val readwiseTokenInput: String = "",
    val readwiseValidating: Boolean = false,
    val readwiseError: String? = null,
)

data class ConnectionRow(
    val kind: ConnectionKind,
    val displayName: String,
    val status: ConnectionStatus,
    val lastSyncAtMs: Long?,
)

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus

    data class Connected(val detail: String?) : ConnectionStatus

    data class Error(val message: String) : ConnectionStatus
}
