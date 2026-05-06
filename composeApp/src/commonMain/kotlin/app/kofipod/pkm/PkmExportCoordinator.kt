// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide singleton that orchestrates "export to PKM" for every entry
 * point (snippet detail, bookmark row, AI summary card). UI hosts subscribe to
 * [pendingRequest] to know when to render the markdown export bottom sheet;
 * the sheet calls [execute] when the user picks a sink. Result rows
 * (Copied / Shared / Failed) are emitted on [results] for any snackbar host
 * that wants to surface them.
 *
 * The coordinator does **not** itself check Pro entitlement — that is the
 * calling ViewModel's responsibility, mirroring the pattern used for snip /
 * bookmark gating in `PlayerViewModel.onSnipTapped` and
 * `PlayerViewModel.onBookmarkTapped`.
 *
 * Dependency seams ([PkmExportDeps], [MarkdownSink]) are intentionally narrow
 * so this class can be unit-tested without a mocking framework. Production
 * wiring is a one-line adapter in `CommonModule.kt` that delegates to the
 * five concrete repositories — see [PkmExportDeps] kdoc for the mapping.
 *
 * The [results] flow is one-shot (`replay = 0`) so a snackbar host that
 * re-subscribes after a config change does not re-toast a stale result. The
 * trade-off is that a result fired while no subscriber is collecting is lost;
 * in practice, the AppShell snackbar host subscribes for the process lifetime,
 * so this never happens.
 */
class PkmExportCoordinator(
    private val deps: PkmExportDeps,
    private val formatter: MarkdownFormatter,
    private val sink: MarkdownSink,
    private val appScope: CoroutineScope,
) {
    private val _pendingRequest = MutableStateFlow<PkmExportRequest?>(null)
    val pendingRequest: StateFlow<PkmExportRequest?> = _pendingRequest

    private val _results =
        MutableSharedFlow<PkmExportResult>(
            replay = 0,
            extraBufferCapacity = 4,
        )
    val results: SharedFlow<PkmExportResult> = _results

    /** Called by entry-point ViewModels after the Pro gate; shows the sheet. */
    fun show(request: PkmExportRequest) {
        _pendingRequest.value = request
    }

    /** Called by the sheet when the user dismisses without picking a sink. */
    fun dismiss() {
        _pendingRequest.value = null
    }

    /**
     * Resolve domain types, format, dispatch to sink. Always clears the sheet
     * state, even on failure, so the user is never stuck looking at an
     * un-dismissable bottom sheet. A missing artifact / episode / podcast
     * surfaces as `Failed("Item not found")` rather than a thrown exception
     * because deletion races (the user nukes a bookmark while the sheet is
     * up) are an expected outcome, not a bug.
     */
    fun execute(
        request: PkmExportRequest,
        sinkChoice: PkmExportSink,
    ) {
        appScope.launch {
            try {
                val document = buildDocument(request)
                if (document == null) {
                    _results.emit(PkmExportResult.Failed("Item not found"))
                    return@launch
                }
                when (sinkChoice) {
                    PkmExportSink.Clipboard -> {
                        sink.exportToClipboard(document)
                        _results.emit(PkmExportResult.Copied)
                    }
                    PkmExportSink.File -> {
                        sink.exportAsFile(document, shareTitle = "Share Markdown")
                        _results.emit(PkmExportResult.Shared)
                    }
                }
            } catch (t: Throwable) {
                // Catching Throwable is deliberate here: the sink delegates to
                // platform code (clipboard managers, share sheet) that can
                // raise platform-specific runtime errors. We collapse them all
                // into a Failed result so the UI never shows a crash dialog.
                _results.emit(PkmExportResult.Failed(t.message ?: "Export failed"))
            } finally {
                _pendingRequest.value = null
            }
        }
    }

    private suspend fun buildDocument(request: PkmExportRequest): MarkdownDocument? =
        when (request) {
            is PkmExportRequest.Snippet -> {
                val s = deps.snippetById(request.snippetId) ?: return null
                val ep = deps.episode(s.episodeId) ?: return null
                val pod = deps.podcast(s.podcastId) ?: return null
                formatter.formatSnippet(s, ep, pod)
            }
            is PkmExportRequest.Bookmark -> {
                val b = deps.bookmarkById(request.bookmarkId) ?: return null
                val ep = deps.episode(b.episodeId) ?: return null
                val pod = deps.podcast(b.podcastId) ?: return null
                formatter.formatBookmark(b, ep, pod)
            }
            is PkmExportRequest.AiSummary -> {
                val sm = deps.summaryFor(request.episodeId) ?: return null
                val ep = deps.episode(request.episodeId) ?: return null
                val pod = deps.podcast(ep.podcastId) ?: return null
                formatter.formatAiSummary(sm, ep, pod)
            }
        }
}
