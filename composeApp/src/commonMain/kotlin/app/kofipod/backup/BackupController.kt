// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import app.kofipod.ui.UiEvent
import app.kofipod.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Single-flight orchestrator for SAF backup + restore. Mirrors
 * [app.kofipod.opml.OpmlController] — same `MutableStateFlow<…Action>` shape, same
 * single-flight guard, same `appScope` lifetime so the operation isn't cancelled if the
 * user navigates between Settings tabs while a write is in flight.
 *
 * Three entry points map to the three Settings rows:
 *  - [chooseFolder] — fires SAF `OpenDocumentTree`, persists the URI, emits a snackbar.
 *  - [runBackup] — manual "Back up now" tap (and, in Slice 3, [BackupWorker]).
 *  - [runRestore] — full validate + confirm + stage + exit-process flow. The
 *    confirmation dialog itself is rendered by `SettingsScreen` from the
 *    [pendingRestoreConfirm] StateFlow this class exposes.
 */
class BackupController(
    private val repo: BackupRepository,
    private val port: BackupFilePort,
    private val store: BackupFolderStore,
    private val bus: UiEventBus,
    private val appScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val exitProcess: () -> Unit = ::defaultExitProcess,
) {
    private val _action = MutableStateFlow<BackupAction>(BackupAction.Idle)
    val action: StateFlow<BackupAction> = _action.asStateFlow()

    private val _pendingRestoreConfirm = MutableStateFlow<RestoreValidation.Valid?>(null)

    /**
     * When non-null, `SettingsScreen` renders the destructive-confirm dialog. UI calls
     * [confirmRestore] on confirm or [cancelRestoreConfirm] on cancel.
     */
    val pendingRestoreConfirm: StateFlow<RestoreValidation.Valid?> = _pendingRestoreConfirm.asStateFlow()

    /**
     * Shared between the manual-button path and the WorkManager worker so a tap during a
     * scheduled run (or vice versa) doesn't double-write. The state guard separately
     * prevents duplicate UI taps from queuing up.
     */
    private val backupMutex = Mutex()

    fun chooseFolder() {
        appScope.launch {
            runCatching {
                val uri = port.pickFolder() ?: return@launch
                store.setTreeUri(uri)
                bus.emit(UiEvent.Snackbar("Backup folder set"))
            }.onFailure { t ->
                _action.value = BackupAction.Error(t.userMessageOrFallback("Couldn't set backup folder"))
            }
        }
    }

    fun runBackup() {
        appScope.launch { runBackupInternal() }
    }

    /**
     * Suspending variant for the WorkManager worker. Joins (via the shared mutex) any
     * manual run that's in flight rather than starting a parallel write.
     */
    suspend fun runBackupAwaiting() {
        runBackupInternal()
    }

    private suspend fun runBackupInternal() {
        if (!_action.compareAndSet(BackupAction.Idle, BackupAction.BackingUp)) return
        val treeUri = store.treeUriNow()
        if (treeUri.isNullOrEmpty()) {
            _action.value = BackupAction.Idle
            return
        }
        backupMutex.withLock {
            runCatching {
                val payload = repo.buildBackup()
                port.writeBackup(treeUri, payload)
            }.onSuccess {
                store.setLastBackupAt(clock.now().toEpochMilliseconds())
                _action.value = BackupAction.Idle
                bus.emit(UiEvent.Snackbar("Backup saved"))
            }.onFailure { t ->
                _action.value = BackupAction.Error(mapBackupError(t))
            }
        }
    }

    fun runRestore() {
        if (!_action.compareAndSet(BackupAction.Idle, BackupAction.Restoring)) return
        appScope.launch {
            runCatching {
                val bytes = port.pickAndReadBackup()
                if (bytes == null) {
                    _action.value = BackupAction.Idle
                    return@launch
                }
                when (val result = repo.validateBackup(bytes)) {
                    is RestoreValidation.Invalid -> {
                        _action.value = BackupAction.Error(result.error.toUserMessage())
                    }
                    is RestoreValidation.Valid -> {
                        // State stays in `Restoring` so the rows remain disabled while
                        // the confirm dialog is up — a second tap on Restore would
                        // otherwise race the dialog.
                        _pendingRestoreConfirm.value = result
                    }
                }
            }.onFailure { t ->
                _action.value = BackupAction.Error(t.userMessageOrFallback("Couldn't read backup file"))
            }
        }
    }

    fun cancelRestoreConfirm() {
        _pendingRestoreConfirm.value = null
        if (_action.value is BackupAction.Restoring) _action.value = BackupAction.Idle
    }

    fun confirmRestore() {
        val pending = _pendingRestoreConfirm.value ?: return
        _pendingRestoreConfirm.value = null
        appScope.launch {
            runCatching {
                repo.stageRestore(pending.dbBytes)
                store.setPendingRestoreFilename(STAGED_FILENAME)
                bus.emit(UiEvent.Snackbar("Restoring — the app will close. Open it again from your launcher."))
                // Let the snackbar render before tearing the process down. Half a second
                // is the minimum that reliably shows the message; longer means a visible
                // pause where the user might tap something else.
                delay(SNACKBAR_VISIBILITY_DELAY_MS)
                exitProcess()
            }.onFailure { t ->
                _action.value = BackupAction.Error(t.userMessageOrFallback("Restore failed"))
            }
        }
    }

    fun dismissError() {
        if (_action.value is BackupAction.Error) _action.value = BackupAction.Idle
    }

    /**
     * Read the one-shot "restore just finished" flag and emit a snackbar if set.
     * Called by [app.kofipod.ui.shell.AppShell] on first composition. Idempotent — the
     * flag clears on read so subsequent calls within the same process are no-ops.
     */
    fun notifyRestoreCompletedIfPending() {
        if (store.consumeRestoreCompletedFlag()) {
            bus.emit(UiEvent.Snackbar("Library restored"))
        }
    }

    private fun Throwable.userMessageOrFallback(fallback: String): String = message?.takeIf { it.isNotBlank() } ?: fallback

    private fun mapBackupError(t: Throwable): String =
        if (isUriPermissionRevoked(t)) {
            "Backup folder is no longer accessible. Choose it again."
        } else {
            t.userMessageOrFallback("Backup failed")
        }

    companion object {
        // Filename inside `filesDir` that the controller stages restore payloads to and
        // [PendingRestore] consumes on next cold start.
        const val STAGED_FILENAME = "restore.tmp"
        private const val SNACKBAR_VISIBILITY_DELAY_MS = 600L
    }
}

/**
 * Default `exitProcess` impl. Lives at file scope so the controller's commonMain
 * declaration doesn't have to take a hard dep on `kotlin.system`. Tests inject a fake.
 */
private fun defaultExitProcess() {
    kotlin.system.exitProcess(0)
}
