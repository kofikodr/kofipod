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
import com.kofikodr.kofipod.db.Download
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.EpisodeChapter
import com.kofikodr.kofipod.db.PlaybackState
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.downloadFileName
import com.kofikodr.kofipod.pkm.PkmExportCoordinator
import com.kofikodr.kofipod.pkm.PkmExportRequest
import com.kofikodr.kofipod.playback.KofipodPlayer
import com.kofikodr.kofipod.playback.PlayableEpisode
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.share.Sharer
import com.kofikodr.kofipod.snippets.FileSizer
import com.kofikodr.kofipod.snippets.SnippetRepository
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
    private val player: KofipodPlayer,
    private val sharer: Sharer,
    private val chapters: ChaptersRepository,
    aiConfig: AiConfigRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val snippetRepo: SnippetRepository,
    private val fileSizer: FileSizer,
    private val pkmExport: PkmExportCoordinator,
    private val paywallRouter: PaywallRouter,
    private val pro: ProEntitlementRepository,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    private val episodeFlow = episodes.episodeFlow(episodeId)
    private val chaptersFlow = chapters.chaptersFlow(episodeId)
    private val summaryEnabledFlow = aiConfig.isKeyConfigured()

    // Derive the podcast as a Flow off the episode so the combine lambda doesn't need
    // a synchronous DB read on every emission. player.state ticks ~2/sec during
    // playback, so a synchronous lookup here would burn a Default-pool thread per tick.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val podcastFlow =
        episodeFlow
            .map { it?.podcastId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id != null) library.podcastFlow(id) else flowOf(null) }

    init {
        viewModelScope.launch {
            episodeFlow.collect { ep ->
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
            EpisodeDetailUiState(
                episode = ep,
                podcast = pod,
                chapters = chapterRows,
                isPlayingThis = playerState.episodeId == episodeId && playerState.isPlaying,
                isCurrentEpisode = playerState.episodeId == episodeId,
                downloaded = dl.isDownloaded(),
                played = ps.isPlayed(),
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
        viewModelScope.launch {
            val sourceUrl = downloads.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return@launch
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
    }

    fun markPlayed() {
        val s = state.value
        val ep = s.episode ?: return
        val pod = s.podcast
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
        if (ep.enclosureUrl.isBlank()) return
        if (state.value.downloaded) return
        downloads.enqueue(
            episodeId = ep.id,
            url = ep.enclosureUrl,
            fileName = downloadFileName(ep.id, ep.enclosureMimeType),
            source = DownloadJob.Source.Manual,
        )
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
        viewModelScope.launch {
            val sourceUrl = downloads.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return@launch
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
