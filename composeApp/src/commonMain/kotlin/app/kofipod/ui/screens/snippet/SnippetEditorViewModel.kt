// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.snippets.Snippet
import app.kofipod.snippets.SnippetFormat
import app.kofipod.snippets.SnippetRenderLauncher
import app.kofipod.snippets.SnippetRepository
import app.kofipod.snippets.SnippetWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Editor state for a single snippet draft. [episodeDurationMs] is intentionally
 * derived from `endMs` (clamped to at least `startMs + 1s`) rather than looked
 * up from `EpisodeSource` — the snippet row doesn't store episode duration, and
 * the render service re-clamps against the actual decoded duration when the
 * MP3 is produced. The editor's nudge controls therefore never run "off the
 * end" of what the user already chose; growing the window past the persisted
 * `endMs` is allowed only down to `endMs+`, and the export pipeline is the
 * authoritative ceiling.
 */
data class SnippetEditorUiState(
    val loading: Boolean = true,
    val snippet: Snippet? = null,
    val title: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val episodeDurationMs: Long = 0L,
    val format: SnippetFormat = SnippetFormat.MP3,
    val rendering: Boolean = false,
)

class SnippetEditorViewModel(
    private val snippetId: String,
    private val snippets: SnippetRepository,
    private val launcher: SnippetRenderLauncher,
) : ViewModel() {
    private val _state = MutableStateFlow(SnippetEditorUiState())
    val state: StateFlow<SnippetEditorUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val s = snippets.selectById(snippetId) ?: return@launch
            _state.value =
                SnippetEditorUiState(
                    loading = false,
                    snippet = s,
                    title = s.title.orEmpty(),
                    startMs = s.startMs,
                    endMs = s.endMs,
                    // We don't have episode duration in the snippet row; the editor
                    // permits trims that exceed the persisted endMs. The render
                    // service will re-clamp against the actual decoded duration.
                    episodeDurationMs = s.endMs.coerceAtLeast(s.startMs + ONE_SECOND_MS),
                    format = s.lastExportFormat ?: SnippetFormat.MP3,
                )
        }
    }

    fun setTitle(value: String) {
        _state.value = _state.value.copy(title = value)
    }

    fun nudgeStart(deltaMs: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(cur.startMs + deltaMs, cur.endMs, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }

    fun nudgeEnd(deltaMs: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(cur.startMs, cur.endMs + deltaMs, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }

    /**
     * Persist the in-memory edits, enqueue the render via [SnippetRenderLauncher],
     * then invoke [onLaunchRender] (the back-navigation hook). The launcher is
     * called BEFORE the navigation callback so a slow nav transition cannot
     * race the foreground service's start.
     */
    fun saveAndRender(onLaunchRender: () -> Unit) {
        val cur = _state.value
        val s = cur.snippet ?: return
        viewModelScope.launch {
            snippets.updateTitle(s.id, cur.title.takeIf { it.isNotBlank() })
            snippets.updateTrim(s.id, cur.startMs, cur.endMs)
            _state.value = cur.copy(rendering = true)
            launcher.enqueue(s.id)
            onLaunchRender()
        }
    }

    private companion object {
        private const val ONE_SECOND_MS = 1_000L
    }
}
