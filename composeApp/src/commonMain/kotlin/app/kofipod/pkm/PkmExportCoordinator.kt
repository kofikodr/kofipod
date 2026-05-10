// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.background.PkmExportScheduler
import app.kofipod.pkm.connections.ConnectionKind
import app.kofipod.pkm.connections.ExportLogEntry
import app.kofipod.pkm.connections.ExportLogRepository
import app.kofipod.pkm.sinks.ExportSink
import app.kofipod.pkm.sinks.ExportSinkResult
import app.kofipod.pkm.sinks.SinkRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Process-wide singleton that orchestrates "export to PKM" for every entry
 * point (snippet detail, bookmark row, AI summary card). UI hosts subscribe to
 * [pendingRequest] to know when to render the markdown export bottom sheet;
 * the sheet calls [execute] when the user picks a destination. Result rows
 * (Copied / Shared / Failed) are emitted on [results] for any snackbar host
 * that wants to surface them.
 *
 * The coordinator does **not** itself check Pro entitlement — that is the
 * calling ViewModel's responsibility, mirroring the pattern used for snip /
 * bookmark gating in `PlayerViewModel.onSnipTapped`.
 *
 * Dispatch logic:
 * - [PkmDestination.Clipboard] → [clipboardSink] (zero-auth, no ExportLog write).
 * - [PkmDestination.ShareFile] → [shareFileSink] (zero-auth, no ExportLog write).
 * - [PkmDestination.Obsidian] / [PkmDestination.Readwise] → resolved from [sinks]
 *   by their [PkmDestination.connectionKind]. Prior [externalId] is looked up in
 *   [exportLog] for idempotency (e.g. Readwise POST-then-PATCH).
 *   Transient failures schedule a retry via [scheduler]; permanent failures mark
 *   the row `failed` and do not schedule a retry.
 *
 * The [results] flow is one-shot (`replay = 0`) so a snackbar host that
 * re-subscribes after a config change does not re-toast a stale result.
 */
class PkmExportCoordinator(
    private val deps: PkmExportDeps,
    private val formatter: MarkdownFormatter,
    private val sinks: SinkRegistry,
    private val exportLog: ExportLogRepository,
    private val scheduler: PkmExportScheduler,
    private val appScope: CoroutineScope,
    /** Zero-auth bypass — clipboard copy never needs a connection row. */
    private val clipboardSink: ExportSink,
    /** Zero-auth bypass — file share never needs a connection row. */
    private val shareFileSink: ExportSink,
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

    /** Called by the sheet when the user dismisses without picking a destination. */
    fun dismiss() {
        _pendingRequest.value = null
    }

    /**
     * Resolve domain types, format, dispatch to the correct sink, write the
     * export log, and optionally schedule a retry. Always clears the sheet
     * state, even on failure, so the user is never stuck looking at an
     * un-dismissable bottom sheet. A missing artifact / episode / podcast
     * surfaces as `Failed("Item not found")` rather than a thrown exception
     * because deletion races are an expected outcome, not a bug.
     */
    fun execute(
        request: PkmExportRequest,
        destination: PkmDestination,
    ) {
        appScope.launch {
            executeInternal(request, destination)
            _pendingRequest.value = null
        }
    }

    /**
     * Resume a previously queued or failed export. Used by the export worker
     * (Task 12) to re-attempt without any UI interaction.
     */
    suspend fun retry(entry: ExportLogEntry) {
        val request = requestFromEntry(entry) ?: return
        val destination =
            destinationFromKind(entry.destinationKind) ?: return
        executeInternal(request, destination)
    }

    // ─── internals ────────────────────────────────────────────────────────────

    private suspend fun executeInternal(
        request: PkmExportRequest,
        destination: PkmDestination,
    ) {
        try {
            val document = buildDocument(request)
            if (document == null) {
                _results.emit(PkmExportResult.Failed("Item not found"))
                return
            }

            val resolved = resolveSink(destination)
            if (resolved == null) {
                _results.emit(PkmExportResult.Failed("Destination not configured"))
                return
            }
            val (kindForLog, sink) = resolved

            val priorExternalId =
                if (kindForLog != null) {
                    exportLog.find(itemKindOf(request), itemIdOf(request), kindForLog)?.externalId
                } else {
                    null
                }

            val sinkResult = sink.export(document, request, priorExternalId)
            recordResult(request, destination, kindForLog, sinkResult)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            // Catching Throwable is deliberate: the sink delegates to platform code
            // (clipboard managers, share sheet, SAF, HTTP) that can raise
            // platform-specific runtime errors. We collapse them into a Failed result
            // so the UI never shows a crash dialog — mirrors the Slice 5 invariant.
            _results.emit(PkmExportResult.Failed(t.message ?: "Export failed"))
        }
    }

    /**
     * Returns a pair of `(kindForLog, sink)` where `kindForLog` is null for
     * zero-auth destinations (Clipboard / ShareFile) and non-null for
     * connection-bound destinations (Obsidian / Readwise).
     *
     * Returns `null` when a connection-bound destination has no registered sink
     * (e.g. Obsidian before Task 12 wires ObsidianSink into the registry).
     */
    private fun resolveSink(destination: PkmDestination): Pair<ConnectionKind?, ExportSink>? =
        when (destination) {
            PkmDestination.Clipboard -> null to clipboardSink
            PkmDestination.ShareFile -> null to shareFileSink
            else -> {
                val kind = destination.connectionKind ?: return null
                val sink = sinks.forKind(kind) ?: return null
                kind to sink
            }
        }

    private suspend fun recordResult(
        request: PkmExportRequest,
        destination: PkmDestination,
        kindForLog: ConnectionKind?,
        sinkResult: ExportSinkResult,
    ) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        when (sinkResult) {
            is ExportSinkResult.Success -> {
                if (kindForLog != null) {
                    exportLog.recordSuccess(
                        itemKind = itemKindOf(request),
                        itemId = itemIdOf(request),
                        destinationKind = kindForLog,
                        externalId = sinkResult.externalId,
                        nowMs = nowMs,
                    )
                }
                // Emit the appropriate zero-auth result or a generic success signal.
                when (destination) {
                    PkmDestination.Clipboard -> _results.emit(PkmExportResult.Copied)
                    PkmDestination.ShareFile -> _results.emit(PkmExportResult.Shared)
                    else -> _results.emit(PkmExportResult.Exported)
                }
            }
            is ExportSinkResult.TransientFailure -> {
                if (kindForLog != null) {
                    exportLog.markQueued(
                        itemKind = itemKindOf(request),
                        itemId = itemIdOf(request),
                        destinationKind = kindForLog,
                        nowMs = nowMs,
                    )
                    scheduler.enqueue()
                }
                _results.emit(PkmExportResult.Failed(sinkResult.message))
            }
            is ExportSinkResult.PermanentFailure -> {
                if (kindForLog != null) {
                    exportLog.markFailed(
                        itemKind = itemKindOf(request),
                        itemId = itemIdOf(request),
                        destinationKind = kindForLog,
                        message = sinkResult.message,
                        nowMs = nowMs,
                    )
                }
                _results.emit(PkmExportResult.Failed(sinkResult.message))
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

    // ─── item-kind / item-id helpers ──────────────────────────────────────────

    private fun itemKindOf(request: PkmExportRequest): String =
        when (request) {
            is PkmExportRequest.Snippet -> ITEM_KIND_SNIPPET
            is PkmExportRequest.Bookmark -> ITEM_KIND_BOOKMARK
            is PkmExportRequest.AiSummary -> ITEM_KIND_AI_SUMMARY
        }

    private fun itemIdOf(request: PkmExportRequest): String =
        when (request) {
            is PkmExportRequest.Snippet -> request.snippetId
            is PkmExportRequest.Bookmark -> request.bookmarkId
            is PkmExportRequest.AiSummary -> request.episodeId
        }

    private fun requestFromEntry(entry: ExportLogEntry): PkmExportRequest? =
        when (entry.itemKind) {
            ITEM_KIND_SNIPPET -> PkmExportRequest.Snippet(entry.itemId)
            ITEM_KIND_BOOKMARK -> PkmExportRequest.Bookmark(entry.itemId)
            ITEM_KIND_AI_SUMMARY -> PkmExportRequest.AiSummary(entry.itemId)
            else -> null
        }

    private fun destinationFromKind(kind: ConnectionKind): PkmDestination? =
        PkmDestination.entries.firstOrNull { it.connectionKind == kind }

    private companion object {
        const val ITEM_KIND_SNIPPET = "snippet"
        const val ITEM_KIND_BOOKMARK = "bookmark"
        const val ITEM_KIND_AI_SUMMARY = "summary"
    }
}
