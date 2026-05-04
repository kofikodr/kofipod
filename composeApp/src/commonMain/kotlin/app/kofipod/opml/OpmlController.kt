// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

import app.kofipod.ui.UiEvent
import app.kofipod.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase of an OPML import/export — surfaced to the UI as inline progress on the Settings
 * rows. Distinct from any per-screen state. Single-flight: at most one operation runs at
 * a time (a second tap while [Importing] / [Exporting] is a no-op).
 */
sealed interface OpmlAction {
    data object Idle : OpmlAction

    data object Importing : OpmlAction

    data object Exporting : OpmlAction

    data class Error(val message: String) : OpmlAction
}

/**
 * Shared coordinator for OPML import/export. Both the Settings entry point and the
 * Library empty-state CTA delegate here so the operation runs once, owns its progress
 * state, and emits its completion snackbar from one place.
 *
 * Runs on the named `appScope` (matching [app.kofipod.ai.AiSummaryRepository]) so an
 * import isn't cancelled if the user navigates between Library and Settings while the
 * file picker is open or the resolution fan-out is mid-flight.
 */
class OpmlController(
    private val repo: OpmlRepository,
    private val port: OpmlFilePort,
    private val bus: UiEventBus,
    private val appScope: CoroutineScope,
) {
    private val _action = MutableStateFlow<OpmlAction>(OpmlAction.Idle)
    val action: StateFlow<OpmlAction> = _action.asStateFlow()

    fun importOpml() {
        // compareAndSet: atomic single-flight. Two callers (e.g. Library + Settings tab
        // racing) can't both pass the guard.
        if (!_action.compareAndSet(OpmlAction.Idle, OpmlAction.Importing)) return
        appScope.launch {
            runCatching {
                val bytes = port.pickImport() ?: return@runCatching null
                repo.import(bytes)
            }.onSuccess { result ->
                _action.value = OpmlAction.Idle
                if (result != null) bus.emit(UiEvent.Snackbar(formatImportMessage(result)))
            }.onFailure { t ->
                // Inline error state surfaces in the row subtitle. Don't double-report
                // via snackbar — that would show the same message in two places.
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "OPML import failed"
                _action.value = OpmlAction.Error(msg)
            }
        }
    }

    fun exportOpml() {
        if (!_action.compareAndSet(OpmlAction.Idle, OpmlAction.Exporting)) return
        appScope.launch {
            runCatching {
                val content = repo.export()
                port.saveExport(repo.suggestedExportFilename(), content)
            }.onSuccess { saved ->
                _action.value = OpmlAction.Idle
                if (saved) bus.emit(UiEvent.Snackbar("OPML exported"))
            }.onFailure { t ->
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "OPML export failed"
                _action.value = OpmlAction.Error(msg)
            }
        }
    }

    fun dismissError() {
        if (_action.value is OpmlAction.Error) _action.value = OpmlAction.Idle
    }

    private fun formatImportMessage(result: ImportResult): String {
        if (result.totalSeen == 0) return "No subscriptions found in OPML"
        val parts = mutableListOf<String>()
        parts += "Imported ${result.imported}"
        if (result.skipped > 0) parts += "${result.skipped} already in library"
        if (result.failed > 0) parts += "${result.failed} couldn't be resolved"
        return parts.joinToString(" · ")
    }
}
