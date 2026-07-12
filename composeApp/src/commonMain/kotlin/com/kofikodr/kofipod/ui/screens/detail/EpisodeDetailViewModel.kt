// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.ai.AiConfigRepository
import com.kofikodr.kofipod.bookmarks.BookmarkRepository
import com.kofikodr.kofipod.data.repo.ChaptersRepository
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.PlaybackRepository
import com.kofikodr.kofipod.data.repo.RemoteEpisodeCache
import com.kofikodr.kofipod.db.Download
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.EpisodeChapter
import com.kofikodr.kofipod.db.PlaybackState
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.downloadFileName
import com.kofikodr.kofipod.pkm.PkmExportCoordinator
import com.kofikodr.kofipod.pkm.PkmExportRequest
import com.kofikodr.kofipod.playback.PlayableEpisode
import com.kofikodr.kofipod.playback.Player
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.share.Sharer
import com.kofikodr.kofipod.snippets.FileSizer
import com.kofikodr.kofipod.snippets.SnippetRepository
import com.kofikodr.kofipod.ui.UiEvent
import com.kofikodr.kofipod.ui.UiEventBus
import com.kofikodr.kofipod.ui.primitives.DownloadButtonState
import com.kofikodr.kofipod.ui.primitives.toDownloadButtonState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class EpisodeDetailUiState(
    val episode: Episode? = null,
    val podcast: Podcast? = null,
    val chapters: List<EpisodeChapter> = emptyList(),
    val isPlayingThis: Boolean = false,
    val isCurrentEpisode: Boolean = false,
    val downloaded: Boolean = false,
    /**
     * True only when the episode is persisted in the library (its `Episode` row
     * exists) AND has a non-blank enclosure URL. A `Download` row FKs to
     * `Episode.id`, so a remote-only (Search → unsubscribed) episode has no row to
     * reference: the insert is either FK-rejected or strands an orphan the
     * Downloads list's `INNER JOIN Episode` hides. The UI gates the Download
     * affordance on this rather than on enclosure presence alone (issue #28).
     */
    val canDownload: Boolean = false,
    val downloadButtonState: DownloadButtonState = DownloadButtonState.Idle,
    val played: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    /**
     * True when the user has connected a Gemini API key. Drives whether the AI
     * tabs (Summary / Mentioned / Discuss) appear at all on the detail screen.
     */
    val summaryEnabled: Boolean = false,
)

class EpisodeDetailViewModel(
    private val episodeId: String,
    episodes: EpisodeSource,
    private val library: LibraryRepository,
    private val playback: PlaybackRepository,
    private val downloads: DownloadRepository,
    private val player: Player,
    private val sharer: Sharer,
    private val chapters: ChaptersRepository,
    aiConfig: AiConfigRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val snippetRepo: SnippetRepository,
    private val fileSizer: FileSizer,
    private val pkmExport: PkmExportCoordinator,
    private val paywallRouter: PaywallRouter,
    private val pro: ProEntitlementRepository,
    private val uiEvents: UiEventBus,
    remoteCache: RemoteEpisodeCache,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    // Optimistic "played" latch. markPlayed() writes completedAt to the DB and the
    // combine below also reads playback.stateFlow, but flipping this the instant the
    // user taps guarantees the checkmark turns green immediately rather than waiting
    // on the SQLDelight re-query round-trip. Mark-played is one-way (no un-mark
    // affordance), so this latch never needs resetting within the VM's lifetime.
    private val markedPlayedOptimistic = MutableStateFlow(false)

    // Resolve the episode against the DB first, fall back to the in-memory
    // RemoteEpisodeCache when the row isn't persisted yet (Search → unsubscribed
    // podcast → episode). The cache emits when the parent PodcastDetailVM
    // populates it; here it's observed alongside the DB flow.
    private val dbEpisodeFlow = episodes.episodeFlow(episodeId)
    private val cacheFlow = remoteCache.observe(episodeId)
    private val episodeFlow = mergeEpisodeWithCache(dbEpisodeFlow, cacheFlow)
    private val chaptersFlow = chapters.chaptersFlow(episodeId)
    private val summaryEnabledFlow = aiConfig.isKeyConfigured()

    // Derive the podcast as a Flow off the episode so the combine lambda doesn't need
    // a synchronous DB read on every emission. player.state ticks ~2/sec during
    // playback, so a synchronous lookup here would burn a Default-pool thread per tick.
    // Library DB wins; cache supplies a Podcast for remote-only episodes.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val podcastFlow =
        episodeFlow
            .map { it?.podcastId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id != null) library.podcastFlow(id) else flowOf(null) }
            .combine(cacheFlow) { db, cached -> db ?: cached?.podcast }

    init {
        // Gate chapter-fetch on the DB-backed episode flow specifically. `EpisodeChapter`
        // FKs to `Episode.id ON DELETE CASCADE`, so writing rows for a cache-only
        // (unsubscribed-podcast) episode would either FK-fail or strand orphan rows
        // depending on SQLite's enforcement. Subscribed shows already cover the
        // chaptersUrl path; unsubscribed shows wait until the user saves to library.
        viewModelScope.launch {
            dbEpisodeFlow.collect { ep ->
                val url = ep?.chaptersUrl?.takeIf { it.isNotBlank() } ?: return@collect
                chapters.ensureCached(episodeId, url)
            }
        }
    }

    val state: StateFlow<EpisodeDetailUiState> =
        combine(
            episodeFlow,
            podcastFlow,
            playback.stateFlow(episodeId),
            downloads.forEpisodeFlow(episodeId),
            player.state,
            chaptersFlow,
            error,
            summaryEnabledFlow,
            // DB-persisted signal: an `Episode` row only exists for a subscribed
            // (in-library) podcast. Drives `canDownload` so the Download affordance
            // is hidden for remote-only episodes whose Download row would dangle (#28).
            dbEpisodeFlow.map { it != null }.distinctUntilChanged(),
            markedPlayedOptimistic,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val ep = values[0] as Episode?
            val pod = values[1] as Podcast?
            val ps = values[2] as PlaybackState?
            val dl = values[3] as Download?
            val playerState = values[4] as com.kofikodr.kofipod.playback.PlayerState
            val chapterRows = values[5] as List<EpisodeChapter>
            val err = values[6] as String?
            val summaryEnabled = values[7] as Boolean
            val isPersisted = values[8] as Boolean
            val markedPlayed = values[9] as Boolean
            EpisodeDetailUiState(
                episode = ep,
                podcast = pod,
                chapters = chapterRows,
                isPlayingThis = playerState.episodeId == episodeId && playerState.isPlaying,
                isCurrentEpisode = playerState.episodeId == episodeId,
                downloaded = dl.isDownloaded(),
                canDownload = isPersisted && ep?.enclosureUrl?.isNotBlank() == true,
                downloadButtonState = dl.toDownloadButtonState(),
                played = ps.isPlayed() || markedPlayed,
                loading = ep == null && err == null,
                error = err,
                summaryEnabled = summaryEnabled,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeDetailUiState())

    /**
     * Per-episode "Saved" list — bookmarks (Slice 1) and snippets (Slice 3) merged into
     * one newest-first stream. UI switches on [SavedItem] to render each variant.
     */
    val saved: StateFlow<List<SavedItem>> =
        combine(
            bookmarkRepo.observeForEpisode(episodeId),
            snippetRepo.observeForEpisode(episodeId),
        ) { bms, sns ->
            // fileSizer.sizeOf calls File.length() — a single inode lookup against
            // the snippet's path under cacheDir/snippets/ (a few microseconds on
            // internal SSD storage). The combine transform already runs on the
            // upstream's emission dispatcher (typically IO from the SQLDelight
            // flows), so no extra dispatcher switching is needed.
            val snippetItems =
                sns.map { snippet ->
                    val sizeBytes =
                        snippet.lastExportPath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { fileSizer.sizeOf(it) }
                            ?: 0L
                    SavedItem.SnippetItem(snippet, sizeBytes)
                }
            (bms.map(SavedItem::BookmarkItem) + snippetItems)
                .sortedByDescending { it.createdAtMs }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePlay() {
        val s = state.value
        val ep = s.episode ?: return
        val pod = s.podcast ?: return
        if (s.isCurrentEpisode) {
            if (s.isPlayingThis) player.pause() else player.resume()
            return
        }
        // Start playback synchronously (not in viewModelScope.launch). The screen's
        // onPlay handler navigates to the player immediately after this call, which
        // tears down this ViewModel's scope — a launched coroutine would be cancelled
        // before player.play() ran, so the episode never actually played (the player
        // showed a stale/blank item). resolvedSourceUrl() and positionFor() are
        // synchronous DB reads and player.play() must run on the main thread anyway,
        // so there is nothing to offload here.
        val sourceUrl = downloads.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return
        val startMs = playback.positionFor(episodeId)
        player.play(
            PlayableEpisode(
                episodeId = episodeId,
                podcastId = pod.id,
                podcastTitle = pod.title,
                title = ep.title,
                artworkUrl = ep.imageUrl.ifBlank { pod.artworkUrl },
                sourceUrl = sourceUrl,
                startPositionMs = startMs,
                episodeNumber = ep.episodeNumber?.toInt(),
            ),
        )
    }

    fun markPlayed() {
        val s = state.value
        val ep = s.episode ?: return
        val pod = s.podcast
        // Flip the checkmark to its "played" (green) colour immediately, and confirm
        // the action to the user, before the DB write + reactive re-query settle.
        markedPlayedOptimistic.value = true
        uiEvents.emit(UiEvent.Snackbar("Marking episode as played"))
        val now = Clock.System.now().toEpochMilliseconds()
        val durationMs = ep.durationSec * 1000L
        // Seed metadata first so episodes the user has never played carry enough context
        // (podcastId, title, artwork, sourceUrl) for "Continue listening" and Stats
        // queries that JOIN on those columns. Without this, markCompleted on a missing
        // row would write empty strings for everything but the position.
        playback.save(
            episodeId = episodeId,
            positionMs = durationMs,
            durationMs = durationMs,
            speed = 1f,
            updatedAt = now,
            episodeTitle = ep.title,
            podcastId = pod?.id ?: ep.podcastId,
            podcastTitle = pod?.title.orEmpty(),
            artworkUrl = ep.imageUrl.ifBlank { pod?.artworkUrl.orEmpty() },
            sourceUrl = ep.enclosureUrl,
            episodeNumber = ep.episodeNumber?.toInt(),
        )
        playback.markCompleted(
            episodeId = episodeId,
            nowMillis = now,
            currentDurationMs = durationMs,
        )
    }

    fun deleteDownload() {
        if (!state.value.downloaded) return
        downloads.delete(episodeId)
    }

    fun download() {
        val ep = state.value.episode ?: return
        // Defence-in-depth behind the UI gate: never enqueue a Download for a
        // remote-only episode — its row would FK-dangle off a missing Episode (#28).
        if (!state.value.canDownload) return
        if (state.value.downloaded) return
        downloads.enqueue(
            episodeId = ep.id,
            url = ep.enclosureUrl,
            fileName = downloadFileName(ep.id, ep.enclosureMimeType),
            source = DownloadJob.Source.Manual,
        )
    }

    /**
     * Cancels an in-flight download for this episode. Wired to the in-progress
     * Close-icon tap on [com.kofikodr.kofipod.ui.primitives.DownloadActionButton];
     * no-op when no download is actually running so a stray tap can't write a
     * spurious `"Paused"` row to the DB.
     */
    fun cancelDownload() {
        val s = state.value.downloadButtonState
        if (s !is DownloadButtonState.Pending && s !is DownloadButtonState.InProgress) return
        downloads.cancel(episodeId)
    }

    fun seekToChapter(startMs: Long) {
        val s = state.value
        if (s.isCurrentEpisode) {
            player.seekTo(startMs)
            if (!s.isPlayingThis) player.resume()
            return
        }
        val ep = s.episode ?: return
        val pod = s.podcast ?: return
        // Synchronous for the same reason as togglePlay(): onChapterTap navigates to the
        // player right after this call, which would cancel a launched coroutine before
        // player.play() ran.
        val sourceUrl = downloads.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return
        player.play(
            PlayableEpisode(
                episodeId = episodeId,
                podcastId = pod.id,
                podcastTitle = pod.title,
                title = ep.title,
                artworkUrl = ep.imageUrl.ifBlank { pod.artworkUrl },
                sourceUrl = sourceUrl,
                startPositionMs = startMs,
                episodeNumber = ep.episodeNumber?.toInt(),
            ),
        )
    }

    /**
     * Seek-or-play behaviour matches [seekToChapter]; bookmarks and chapters share semantics.
     */
    fun seekToBookmark(timestampMs: Long) = seekToChapter(timestampMs)

    fun deleteBookmark(id: String) = bookmarkRepo.deleteById(id)

    fun deleteSnippet(id: String) = snippetRepo.deleteById(id)

    /**
     * Pro-gated. On Pro: open the markdown export sheet for [snippetId].
     * On Free / Unknown: open the paywall sheet via [PaywallRouter].
     */
    fun onSnippetExportRequested(snippetId: String) {
        when (pro.state.value) {
            is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.Snippet(snippetId))
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_pkm_export_snippet")
        }
    }

    /**
     * Pro-gated. On Pro: open the markdown export sheet for [bookmarkId].
     * On Free / Unknown: open the paywall sheet.
     */
    fun onBookmarkExportRequested(bookmarkId: String) {
        when (pro.state.value) {
            is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.Bookmark(bookmarkId))
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_pkm_export_bookmark")
        }
    }

    /**
     * Pro-gated. On Pro: open the markdown export sheet for the AI summary
     * cached for this episode. Caller is responsible for only invoking this
     * when the summary state is Ready (the SummaryCard shows the affordance
     * conditionally on Ready).
     *
     * On Free / Unknown: open the paywall sheet.
     */
    fun onAiSummaryExportRequested() {
        when (pro.state.value) {
            is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.AiSummary(episodeId))
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_pkm_export_summary")
        }
    }

    fun share() {
        val ep = state.value.episode ?: return
        val pod = state.value.podcast
        val link = ep.enclosureUrl.ifBlank { pod?.feedUrl.orEmpty() }
        sharer.shareText(
            title = ep.title,
            text = "${ep.title}${pod?.title?.let { " — $it" } ?: ""}\n$link",
        )
    }
}

private fun Download?.isDownloaded(): Boolean = this != null && state == "Completed" && !localPath.isNullOrBlank()

private fun PlaybackState?.isPlayed(): Boolean = this != null && completedAt != null

/**
 * Combines the DB-backed episode flow with the in-memory RemoteEpisodeCache so that
 * navigating to a remote-only episode (Search → unsubscribed podcast → episode) shows
 * the cached projection until/unless the user subscribes and the row is persisted.
 * DB wins on conflict; cache fills the null slot.
 */
internal fun mergeEpisodeWithCache(
    dbFlow: kotlinx.coroutines.flow.Flow<Episode?>,
    cacheFlow: kotlinx.coroutines.flow.Flow<com.kofikodr.kofipod.data.repo.RemoteEpisodeCache.Entry?>,
): kotlinx.coroutines.flow.Flow<Episode?> = kotlinx.coroutines.flow.combine(dbFlow, cacheFlow) { db, cached -> db ?: cached?.episode }
