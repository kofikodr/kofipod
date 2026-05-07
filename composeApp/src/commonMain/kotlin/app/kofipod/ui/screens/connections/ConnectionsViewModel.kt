// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.connections

import androidx.lifecycle.ViewModel
import app.kofipod.pkm.connections.ConnectionKind
import app.kofipod.pkm.connections.PkmConnection
import app.kofipod.pkm.connections.PkmConnectionRepository
import app.kofipod.pkm.sinks.ReadwiseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Thin seam over [PkmConnectionRepository] so [ConnectionsViewModel] can be
 * unit-tested without a real SQLDelight DB or its `withContext(Dispatchers.Default)`
 * internals. The production binding delegates directly to [PkmConnectionRepository].
 */
interface ConnectionsSource {
    fun observeAll(): Flow<List<PkmConnection>>

    suspend fun connect(
        kind: ConnectionKind,
        tokenRef: String?,
        tokenValue: String?,
        folderUri: String?,
        nowMs: Long,
    )

    suspend fun disconnect(kind: ConnectionKind)
}

/** Adapter bridging [PkmConnectionRepository] to [ConnectionsSource]. */
class PkmConnectionsSource(
    private val repo: PkmConnectionRepository,
) : ConnectionsSource {
    override fun observeAll(): Flow<List<PkmConnection>> = repo.observeAll()

    override suspend fun connect(
        kind: ConnectionKind,
        tokenRef: String?,
        tokenValue: String?,
        folderUri: String?,
        nowMs: Long,
    ) = repo.connect(kind, tokenRef, tokenValue, folderUri, nowMs)

    override suspend fun disconnect(kind: ConnectionKind) = repo.disconnect(kind)
}

class ConnectionsViewModel(
    private val connections: ConnectionsSource,
    private val readwiseClient: ReadwiseClient,
    private val appScope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectionsUiState(rows = buildInitialRows()))
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    init {
        appScope.launch {
            connections.observeAll().collect { liveRows ->
                _uiState.update { current ->
                    current.copy(rows = mergeRows(liveRows))
                }
            }
        }
    }

    fun openReadwiseDialog() {
        _uiState.update { it.copy(readwiseDialogOpen = true, readwiseError = null) }
    }

    fun closeReadwiseDialog() {
        _uiState.update {
            it.copy(
                readwiseDialogOpen = false,
                readwiseTokenInput = "",
                readwiseError = null,
                readwiseValidating = false,
            )
        }
    }

    fun onReadwiseTokenChange(value: String) {
        _uiState.update { it.copy(readwiseTokenInput = value, readwiseError = null) }
    }

    fun connectReadwise() {
        val token = _uiState.value.readwiseTokenInput.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(readwiseError = "Paste your Readwise API token first.") }
            return
        }
        if (_uiState.value.readwiseValidating) return

        _uiState.update { it.copy(readwiseValidating = true, readwiseError = null) }
        appScope.launch {
            val valid = runCatching { readwiseClient.verify(token) }.getOrElse { false }
            if (valid) {
                connections.connect(
                    kind = ConnectionKind.Readwise,
                    tokenRef = "readwise.token",
                    tokenValue = token,
                    folderUri = null,
                    nowMs = clock.now().toEpochMilliseconds(),
                )
                _uiState.update {
                    it.copy(
                        readwiseDialogOpen = false,
                        readwiseTokenInput = "",
                        readwiseValidating = false,
                        readwiseError = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        readwiseValidating = false,
                        readwiseError = "Token rejected. Check your token at readwise.io/access_token.",
                    )
                }
            }
        }
    }

    fun connectObsidian(treeUri: String) {
        appScope.launch {
            connections.connect(
                kind = ConnectionKind.Obsidian,
                tokenRef = null,
                tokenValue = null,
                folderUri = treeUri,
                nowMs = clock.now().toEpochMilliseconds(),
            )
        }
    }

    fun disconnect(kind: ConnectionKind) {
        appScope.launch { connections.disconnect(kind) }
    }

    private fun buildInitialRows(): List<ConnectionRow> =
        ConnectionKind.entries.filter { it != ConnectionKind.Notion }.map { kind ->
            ConnectionRow(
                kind = kind,
                displayName = kind.displayName(),
                status = ConnectionStatus.Disconnected,
                lastSyncAtMs = null,
            )
        }

    private fun mergeRows(liveRows: List<PkmConnection>): List<ConnectionRow> {
        val byKind = liveRows.associateBy { it.kind }
        return ConnectionKind.entries.filter { it != ConnectionKind.Notion }.map { kind ->
            val conn = byKind[kind]
            ConnectionRow(
                kind = kind,
                displayName = kind.displayName(),
                status =
                    if (conn != null) {
                        val detail = conn.folderUri?.substringAfterLast('/')
                        ConnectionStatus.Connected(detail ?: "Connected")
                    } else {
                        ConnectionStatus.Disconnected
                    },
                lastSyncAtMs = conn?.lastSyncAtMs,
            )
        }
    }
}

private fun ConnectionKind.displayName(): String =
    when (this) {
        ConnectionKind.Markdown -> "Markdown (built-in)"
        ConnectionKind.Obsidian -> "Obsidian"
        ConnectionKind.Readwise -> "Readwise Reader"
        ConnectionKind.Notion -> "Notion"
    }
