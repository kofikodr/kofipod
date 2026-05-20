// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.pkm.connections.ConnectionKind
import com.kofikodr.kofipod.pkm.connections.PkmConnection
import com.kofikodr.kofipod.pkm.connections.PkmConnectionRepository
import com.kofikodr.kofipod.pkm.sinks.ReadwiseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    private var validateJob: Job? = null

    init {
        // UI-state collection belongs to viewModelScope — when the Connections
        // screen leaves the back stack the VM is cleared and the collector
        // should die with it. The prior `appScope.launch` here leaked the
        // collector for the lifetime of the process, kept _uiState live, and
        // racked up duplicate emits on every revisit.
        viewModelScope.launch {
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
        validateJob?.cancel()
        validateJob = null
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
        validateJob?.cancel()
        // Readwise token verification + state mutation belongs to viewModelScope
        // — it's a UI-owned spinner ("Validating…") that must die with the
        // dialog. The prior appScope-launched validation outlived the dialog
        // and could update _uiState after the user closed it.
        validateJob =
            viewModelScope.launch {
                val valid =
                    runCatching { readwiseClient.verify(token) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrElse { false }
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
        // DB write, intentionally on appScope so a quick screen-pop after
        // the SAF folder-picker returns can't cancel the row insert mid-
        // flight. Mirrors the SmartPlaylist delete pattern.
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
        // DB write on appScope for the same reason as connectObsidian — a
        // user who taps Disconnect and immediately navigates away must not
        // race the row removal.
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
                        ConnectionStatus.Connected(detail)
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
