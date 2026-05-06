// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.playback.KofipodPlayer
import app.kofipod.snippets.RenderProgress
import app.kofipod.snippets.Snippet
import app.kofipod.snippets.SnippetFormat
import app.kofipod.snippets.SnippetRenderLauncher
import app.kofipod.snippets.SnippetRepository
import app.kofipod.snippets.SnippetWindow
import app.kofipod.snippets.WaveformGenerator
import app.kofipod.snippets.WaveformSamples
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Editor state for a single snippet draft. Replaces the Slice 3 shape with
 * the full Slice 4 design: caption / format / waveform / preview / RenderProgress.
 *
 * [episodeDurationMs] is derived from `endMs` (clamped to at least
 * `startMs + 1s`). The render service re-clamps against the actual decoded
 * duration, so the editor never over-trims.
 *
 * [waveform] is a deterministic placeholder seeded by `snippet.id`; real
 * audio-amplitude extraction is deferred to Slice 4.5 ([WaveformGenerator] is
 * the seam).
 *
 * [previewing] reflects whether the main player is currently in preview mode
 * for this snippet. Preview playback is best-effort: `seekTo(startMs)` +
 * `resume()` does NOT clip to the window — the user taps again to stop.
 */
data class SnippetEditorUiState(
    val loading: Boolean = true,
    val snippet: Snippet? = null,
    val title: String = "",
    val caption: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val episodeDurationMs: Long = 0L,
    val format: SnippetFormat = SnippetFormat.MP4,
    val waveform: WaveformSamples = WaveformSamples(FloatArray(0)),
    val previewing: Boolean = false,
    val previewPositionMs: Long? = null,
    val progress: RenderProgress = RenderProgress.Idle,
)

class SnippetEditorViewModel(
    private val snippetId: String,
    private val snippets: SnippetRepository,
    private val launcher: SnippetRenderLauncher,
    private val player: KofipodPlayer,
    private val waveformGen: WaveformGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(SnippetEditorUiState())
    val state: StateFlow<SnippetEditorUiState> = _state.asStateFlow()

    init {
        load()
        observeProgress()
    }

    private fun load() {
        viewModelScope.launch {
            val s = snippets.selectById(snippetId) ?: return@launch
            _state.value =
                SnippetEditorUiState(
                    loading = false,
                    snippet = s,
                    title = s.title.orEmpty(),
                    caption = s.captionOverride.orEmpty(),
                    startMs = s.startMs,
                    endMs = s.endMs,
                    // Derived ceiling — render service re-clamps against real
                    // decoded duration so this can never over-trim the file.
                    episodeDurationMs = s.endMs.coerceAtLeast(s.startMs + ONE_SECOND_MS),
                    format = s.lastExportFormat ?: SnippetFormat.MP4,
                    waveform = waveformGen.generate(seed = s.id),
                )
        }
    }

    /**
     * Subscribe to [SnippetRenderLauncher.progress] and mirror events that
     * belong to this snippet (or the global Idle reset) into [state.progress].
     */
    private fun observeProgress() {
        viewModelScope.launch {
            launcher.progress
                .filter { p ->
                    p is RenderProgress.Idle ||
                        (p is RenderProgress.InFlight && p.snippetId == snippetId) ||
                        (p is RenderProgress.Complete && p.snippetId == snippetId) ||
                        (p is RenderProgress.Failed && p.snippetId == snippetId)
                }
                .collect { p -> _state.value = _state.value.copy(progress = p) }
        }
    }

    fun setTitle(value: String) {
        _state.value = _state.value.copy(title = value)
    }

    fun setCaption(value: String) {
        _state.value = _state.value.copy(caption = value)
    }

    fun setFormat(value: SnippetFormat) {
        _state.value = _state.value.copy(format = value)
    }

    /** Move the start handle to an absolute position; clamped via [SnippetWindow]. */
    fun setStart(ms: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(ms, cur.endMs, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }

    /** Move the end handle to an absolute position; clamped via [SnippetWindow]. */
    fun setEnd(ms: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(cur.startMs, ms, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }

    /**
     * Toggle preview playback on the main player. When starting, seeks to
     * [SnippetEditorUiState.startMs] and resumes. Clipping to `endMs` is
     * best-effort (spec-intentional MVP); the user taps again to stop.
     *
     * If the snippet row is not loaded yet ([SnippetEditorUiState.snippet] is
     * null), this is a no-op.
     */
    fun previewToggle() {
        val cur = _state.value
        if (cur.previewing) {
            player.pause()
            _state.value = cur.copy(previewing = false)
            return
        }
        // Guard: require the snippet to be loaded before touching the player.
        cur.snippet ?: return
        viewModelScope.launch {
            player.seekTo(cur.startMs)
            player.resume()
            _state.value = cur.copy(previewing = true)
        }
    }

    /**
     * Persist edits and enqueue the render. The editor stays on screen and
     * watches [state.progress] instead of returning to Player on launch.
     *
     * Sequence:
     * 1. Persist title / caption / trim.
     * 2. [SnippetRepository.markFormatPending] — writes `lastExportFormat` so
     *    the render service knows which exporter to call. `lastExportPath` stays
     *    NULL; [SnippetRepository.setRendered] (called by the service on
     *    success) overwrites both.
     * 3. [SnippetRenderLauncher.enqueue] — kicks off the foreground service.
     */
    fun saveAndRender() {
        val cur = _state.value
        val s = cur.snippet ?: return
        viewModelScope.launch {
            snippets.updateTitle(s.id, cur.title.takeIf { it.isNotBlank() })
            snippets.updateCaptionOverride(s.id, cur.caption.takeIf { it.isNotBlank() })
            snippets.updateTrim(s.id, cur.startMs, cur.endMs)
            snippets.markFormatPending(s.id, cur.format)
            launcher.enqueue(s.id)
        }
    }

    /**
     * Best-effort cancel: flips [state.progress] to [RenderProgress.Idle]
     * locally. Slice 4 has no separate "cancel" intent to the foreground
     * service — this is the user-visible part only.
     */
    fun cancelRender() {
        _state.value = _state.value.copy(progress = RenderProgress.Idle)
    }

    private companion object {
        private const val ONE_SECOND_MS = 1_000L
    }
}
