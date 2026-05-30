// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.snippet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.playback.PlayableEpisode
import com.kofikodr.kofipod.playback.Player
import com.kofikodr.kofipod.snippets.RenderProgress
import com.kofikodr.kofipod.snippets.Snippet
import com.kofikodr.kofipod.snippets.SnippetFormat
import com.kofikodr.kofipod.snippets.SnippetPreviewTick
import com.kofikodr.kofipod.snippets.SnippetRenderLauncher
import com.kofikodr.kofipod.snippets.SnippetRepository
import com.kofikodr.kofipod.snippets.SnippetWindow
import com.kofikodr.kofipod.snippets.WaveformGenerator
import com.kofikodr.kofipod.snippets.WaveformSamples
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

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
 * [previewing] is true while preview playback is active. [previewPositionMs]
 * holds the live playhead in episode-time so [SnippetWaveform] can draw the
 * vertical scrubber line. Both reset to their idle values when preview ends
 * (auto-stop at `endMs`, user toggle, or screen exit).
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
    /** Episode metadata for the header card. Empty until [load] resolves. */
    val episodeTitle: String = "",
    val podcastTitle: String = "",
    val episodeNumber: Int? = null,
    val artworkUrl: String = "",
    /** Stable seed for the [KofipodArtwork] gradient placeholder. */
    val artworkSeed: Int = 0,
)

class SnippetEditorViewModel(
    private val snippetId: String,
    private val snippets: SnippetRepository,
    private val launcher: SnippetRenderLauncher,
    private val player: Player,
    private val waveformGen: WaveformGenerator,
    private val episodes: EpisodeSource,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _state = MutableStateFlow(SnippetEditorUiState())
    val state: StateFlow<SnippetEditorUiState> = _state.asStateFlow()

    // Preview lifecycle. All written from the Main dispatcher (viewModelScope's
    // default), so no locking is needed.
    private var previewJob: Job? = null

    /**
     * The user's pre-preview position in the snippet's episode. Restored on
     * stop so a quick preview doesn't lose their place. Null when the player
     * was on a different episode (we couldn't snapshot a comparable position).
     */
    private var savedRestorePositionMs: Long? = null

    /**
     * Wall-clock baseline used to interpolate the preview playhead between the
     * player's 500ms position ticks. Reset on resync when player drifts.
     */
    private var previewBaseMs: Long = 0L
    private var previewBaseClockMs: Long = 0L
    private var previewSpeed: Float = 1f

    /**
     * Grace window after `resume()` before we honour `!isPlaying` as
     * "audio focus lost — stop preview". MediaController.play() is async, so
     * `isPlaying` may stay false for a few hundred ms after we ask to resume.
     */
    private var previewStartedAtClockMs: Long = 0L

    /**
     * Whether the current preview replaced the player's loaded episode. On
     * stop, true means we should clear the queue (`stop()`) rather than just
     * pause — leaving a foreign episode cued in the mini-player after preview
     * is more confusing than letting it disappear.
     */
    private var previewLoadedNewEpisode: Boolean = false

    init {
        load()
        observeProgress()
    }

    private fun load() {
        viewModelScope.launch {
            val s = snippets.selectById(snippetId) ?: return@launch
            // Episode + podcast lookups are best-effort — header card falls back
            // to the snippet's own ids if either is missing (e.g. unsubscribed
            // mid-edit). Don't block editor load on them.
            val ep = episodes.episodeFlow(s.episodeId).firstOrNull()
            val pod = library.podcastNow(s.podcastId)
            _state.value =
                _state.value.copy(
                    loading = false,
                    snippet = s,
                    title = s.title.orEmpty(),
                    caption = s.captionOverride.orEmpty(),
                    startMs = s.startMs,
                    endMs = s.endMs,
                    // Use the actual episode duration so the waveform spans
                    // the full episode and the trim window appears in proper
                    // proportion (e.g. handles at 30%–50% rather than crowded
                    // against the right edge for a snip-last-60s near the end).
                    // Falls back to the snippet end + 1s floor if the episode
                    // row is missing or has no duration. The render service
                    // re-clamps against the real decoded duration so an
                    // optimistic ceiling here can't over-trim the file.
                    episodeDurationMs = (
                        ep?.durationSec?.takeIf { it > 0L }?.let { it * 1_000L }
                            ?: s.endMs.coerceAtLeast(s.startMs + ONE_SECOND_MS)
                    ),
                    format = s.lastExportFormat ?: SnippetFormat.MP4,
                    waveform = waveformGen.generate(seed = s.id),
                    episodeTitle = ep?.title.orEmpty(),
                    podcastTitle = pod?.title.orEmpty(),
                    episodeNumber = ep?.episodeNumber?.toInt(),
                    artworkUrl = ep?.imageUrl?.takeIf { it.isNotBlank() } ?: pod?.artworkUrl.orEmpty(),
                    artworkSeed = s.podcastId.hashCode(),
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
     * Toggle preview playback over the trim window.
     *
     * Behaviour:
     * - Same-episode case (player already loaded with this snippet's episode):
     *   snapshot the user's current `positionMs`, `seekTo(startMs)`, `resume()`.
     *   On stop, `seekTo(savedPositionMs)` so a quick preview doesn't displace
     *   their listening position.
     * - Different- or no-episode case: load the snippet's episode via
     *   [EpisodeSource] + [DownloadRepository] and call [KofipodPlayer.play] at
     *   `startMs`. We can't restore the user's previous episode (PlayerState
     *   doesn't expose `sourceUrl`) — accepted limitation.
     *
     * In both cases a position-poll coroutine drives [SnippetEditorUiState.previewPositionMs]
     * for the waveform's vertical scrubber line, and auto-stops when:
     *   - the projected position reaches `endMs`, or
     *   - the player reports `!isPlaying` past the startup grace window
     *     (audio focus loss, user pulled the system notification, etc.).
     *
     * If the snippet row is not loaded yet ([SnippetEditorUiState.snippet] is
     * null), this is a no-op.
     */
    fun previewToggle() {
        val cur = _state.value
        if (cur.previewing) {
            stopPreview()
            return
        }
        val s = cur.snippet ?: return
        // Synchronously claim `previewing` so a double-tap before the launch
        // resumes is routed into stopPreview() instead of starting a second
        // overlapping preview.
        _state.value = cur.copy(previewing = true, previewPositionMs = cur.startMs)
        viewModelScope.launch { startPreview(s, cur.startMs, cur.endMs) }
    }

    private suspend fun startPreview(
        snippet: Snippet,
        startMs: Long,
        endMs: Long,
    ) {
        val playerNow = player.state.value
        val sameEpisode =
            !playerNow.episodeId.isNullOrBlank() && playerNow.episodeId == snippet.episodeId
        var loadedNewEpisode = false

        if (sameEpisode) {
            // Snapshot before we hijack the player so we can restore on stop.
            savedRestorePositionMs = playerNow.positionMs
            player.seekTo(startMs)
            player.resume()
        } else {
            // Episode not currently loaded — load it. Restore is not possible
            // (PlayerState carries no sourceUrl to rebuild a PlayableEpisode for
            // whatever was playing before), so we don't snapshot.
            savedRestorePositionMs = null
            val playable = buildPlayableEpisode(snippet, startMs)
            if (playable == null) {
                // Episode missing or no source available — back out of the
                // optimistic `previewing = true` we set in previewToggle.
                _state.value = _state.value.copy(previewing = false, previewPositionMs = null)
                return
            }
            player.play(playable)
            loadedNewEpisode = true
        }

        previewBaseMs = startMs
        previewBaseClockMs = clock.now().toEpochMilliseconds()
        previewStartedAtClockMs = previewBaseClockMs
        // Speed seed for wall-clock interpolation. In the different-episode
        // branch the controller may not yet have applied a speed (`play()`
        // is async via MediaController IPC), but the poll loop's drift resync
        // catches any mismatch within one player tick (≤500ms).
        previewSpeed = playerNow.speed.takeIf { it > 0f } ?: 1f
        previewLoadedNewEpisode = loadedNewEpisode

        startPositionPoll()
    }

    private suspend fun buildPlayableEpisode(
        snippet: Snippet,
        startPositionMs: Long,
    ): PlayableEpisode? {
        val ep = episodes.episodeFlow(snippet.episodeId).firstOrNull() ?: return null
        val sourceUrl = downloads.resolvedSourceUrl(snippet.episodeId, ep.enclosureUrl) ?: return null
        val pod = library.podcastNow(snippet.podcastId)
        return PlayableEpisode(
            episodeId = ep.id,
            podcastId = snippet.podcastId,
            podcastTitle = pod?.title.orEmpty(),
            title = ep.title,
            artworkUrl = ep.imageUrl.ifBlank { pod?.artworkUrl.orEmpty() },
            sourceUrl = sourceUrl,
            startPositionMs = startPositionMs,
            episodeNumber = ep.episodeNumber?.toInt(),
        )
    }

    private fun startPositionPoll() {
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch {
                while (true) {
                    delay(PREVIEW_TICK_MS)
                    val now = clock.now().toEpochMilliseconds()
                    val elapsed = now - previewBaseClockMs

                    // Watch for audio focus loss / external pause once the
                    // grace window has passed. Without the grace, MediaController's
                    // async resume() would trigger an immediate false-positive stop.
                    val sincePreviewStarted = now - previewStartedAtClockMs
                    if (sincePreviewStarted > AUDIO_FOCUS_GRACE_MS && !player.state.value.isPlaying) {
                        stopPreview()
                        return@launch
                    }

                    // Read endMs from current state every tick so that trim
                    // handle drags during preview re-bound the auto-stop point.
                    val currentEndMs = _state.value.endMs
                    when (val tick = SnippetPreviewTick.project(previewBaseMs, elapsed, previewSpeed, currentEndMs)) {
                        is SnippetPreviewTick.Result.End -> {
                            _state.value = _state.value.copy(previewPositionMs = tick.positionMs)
                            stopPreview()
                            return@launch
                        }

                        is SnippetPreviewTick.Result.Continue -> {
                            // Resync against the player's authoritative position
                            // when wall-clock drift accumulates (speed change,
                            // buffer stall, user yanked the system seek bar).
                            val playerPos = player.state.value.positionMs
                            val resyncTo = SnippetPreviewTick.resyncIfDrifted(tick.positionMs, playerPos)
                            if (resyncTo != null) {
                                previewBaseMs = resyncTo
                                previewBaseClockMs = now
                                _state.value = _state.value.copy(previewPositionMs = resyncTo)
                            } else {
                                _state.value = _state.value.copy(previewPositionMs = tick.positionMs)
                            }
                        }
                    }
                }
            }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        if (previewLoadedNewEpisode) {
            // We replaced the user's loaded episode to preview. `stop()` clears
            // the queue so the mini-player drops the foreign episode entirely
            // — leaving it paused mid-snippet would surprise the user worse.
            player.stop()
        } else {
            player.pause()
            savedRestorePositionMs?.let { player.seekTo(it) }
        }
        savedRestorePositionMs = null
        previewLoadedNewEpisode = false
        _state.value = _state.value.copy(previewing = false, previewPositionMs = null)
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
        if (cur.previewing) stopPreview()
        viewModelScope.launch {
            snippets.updateTitle(s.id, cur.title.takeIf { it.isNotBlank() })
            snippets.updateCaptionOverride(s.id, cur.caption.takeIf { it.isNotBlank() })
            snippets.updateTrim(s.id, cur.startMs, cur.endMs)
            snippets.markFormatPending(s.id, cur.format)
            launcher.enqueue(s.id)
        }
    }

    /**
     * Cancel the in-flight render. Tells the launcher to fire an ACTION_CANCEL
     * intent at the foreground service (which cancels the encode job and
     * publishes Idle on the bus) and flips local UI state to Idle so the
     * editor returns to the "Render & Share" CTA immediately. Without the
     * service-side cancel, a previously-running encode would finish in the
     * background and pop the share dialog after the user already aborted.
     */
    fun cancelRender() {
        val id = _state.value.snippet?.id ?: snippetId
        launcher.cancel(id)
        _state.value = _state.value.copy(progress = RenderProgress.Idle)
    }

    override fun onCleared() {
        if (_state.value.previewing) stopPreview()
        super.onCleared()
    }

    private companion object {
        const val ONE_SECOND_MS = 1_000L

        /**
         * 50ms = ~20fps line motion. The player itself only updates its
         * positionMs every 500ms, so we interpolate against wall-clock between
         * its ticks to keep the scrubber smooth.
         */
        const val PREVIEW_TICK_MS = 50L

        /**
         * MediaController.resume() is async — `isPlaying` can stay false for
         * a few hundred ms after we ask to play. Don't trip the
         * "external pause → stop preview" path until this much has elapsed.
         */
        const val AUDIO_FOCUS_GRACE_MS = 600L
    }
}
