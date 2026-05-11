// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.api.PodcastIndexApi
import com.kofikodr.kofipod.data.net.NetworkErrorHandler
import com.kofikodr.kofipod.data.recommend.RecommendationsRepository
import com.kofikodr.kofipod.data.recommend.RecommendationsSource
import com.kofikodr.kofipod.data.recommend.ReshuffleResult
import com.kofikodr.kofipod.data.repo.CategoriesSource
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.SearchSource
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.domain.PodcastSummary
import com.mr3y.podcastindex.model.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.random.Random

enum class SearchTab { All, Title, Person }

sealed interface SearchEvent {
    data object OutOfReshuffles : SearchEvent
}

data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.All,
    val results: List<PodcastSummary> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val popularCategories: List<Category> = emptyList(),
    val recommendations: List<PodcastSummary> = emptyList(),
    val recsLoading: Boolean = false,
    /** Stable while [recsLoading] is true so the UI doesn't flicker between quips. */
    val recsLoadingQuip: String = "",
    val recsReshufflesRemaining: Int = RecommendationsRepository.MAX_DAILY_RESHUFFLES,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repo: SearchSource,
    categories: CategoriesSource,
    private val recommendations: RecommendationsSource,
    private val episodes: EpisodeSource,
    private val library: LibraryRepository,
    private val appScope: CoroutineScope,
    private val errors: NetworkErrorHandler,
    private val telemetry: com.kofikodr.kofipod.diagnostics.Telemetry,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(popularCategories = categories.popular()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SearchEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SearchEvent> = _events.asSharedFlow()

    private var searchJob: Job? = null
    private var currentLimit: Int = PodcastIndexApi.PAGE_SIZE

    /**
     * Currently selected result for the tablet-landscape master-detail preview pane.
     * VM-local UI state — not persisted across process death, not routed (the URL only
     * changes when the user explicitly opens the podcast via the detail pane's
     * "Latest" CTA). `null` means "show the empty-detail hint."
     */
    private val _selectedSearchResultId = MutableStateFlow<String?>(null)
    val selectedSearchResultId: StateFlow<String?> = _selectedSearchResultId.asStateFlow()

    /**
     * Last [PREVIEW_EPISODE_LIMIT] episodes for the selected result, sourced from the
     * existing [EpisodeSource.episodesFlow]. Search results are usually unsubscribed,
     * so the table is typically empty — the preview pane handles that case by showing
     * an "No episodes cached yet" hint. `flatMapLatest` cancels the previous
     * episodesFlow subscription when the selection changes so we never accumulate
     * orphaned collectors as the user clicks through results.
     */
    val selectedRecentEpisodes: StateFlow<List<Episode>> =
        _selectedSearchResultId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyList())
                } else {
                    episodes.episodesFlow(id).map { it.take(PREVIEW_EPISODE_LIMIT) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSearchResult(podcastId: String?) {
        _selectedSearchResultId.value = podcastId
    }

    /**
     * Task 3.4 — Subscribe wiring for the tablet-landscape preview pane's "Subscribe"
     * CTA. Resolves the [PodcastSummary] from the current results list (the only place
     * the preview pane can be opened from) and persists it via [LibraryRepository.savePodcast].
     * No-op if the id no longer matches a row (e.g. results changed mid-tap). Visual
     * loading / "Subscribed" toggle is out of scope for this task — the button stays a
     * flat KPButton until a follow-up slice surfaces subscription state.
     */
    fun subscribe(podcastId: String) {
        val summary = _state.value.results.firstOrNull { it.id == podcastId } ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        library.savePodcast(summary, listId = null, now = now)
    }

    init {
        viewModelScope.launch {
            recommendations.observe().collect { recState ->
                _state.value =
                    _state.value.copy(
                        recommendations = recState.items.orEmpty(),
                        recsReshufflesRemaining = recState.reshufflesRemaining,
                    )
            }
        }
        // Daily refresh — show loading only when we have nothing to show, so an existing cache
        // doesn't get covered up while we re-check.
        val initialHasNoCache = _state.value.recommendations.isEmpty()
        if (initialHasNoCache) startLoadingQuip()
        appScope.launch {
            try {
                recommendations.refreshIfStale()
            } finally {
                _state.value = _state.value.copy(recsLoading = false)
            }
        }
    }

    fun reshuffle() {
        if (_state.value.recsLoading) return
        startLoadingQuip()
        appScope.launch {
            try {
                // Cap check inside the launch (not as an early-return) so PullToRefreshBox
                // observes a real false→true→false cycle on recsLoading. Skipping the cycle
                // leaves its indicator anchored at the release position with nothing to drive
                // the retraction animation.
                if (_state.value.recsReshufflesRemaining <= 0) {
                    delay(LOADING_MIN_VISIBLE_MS)
                    _events.tryEmit(SearchEvent.OutOfReshuffles)
                    return@launch
                }
                when (recommendations.reshuffle()) {
                    // Race fallback: another caller hit the cap between our check and this call.
                    ReshuffleResult.OutOfQuota -> _events.tryEmit(SearchEvent.OutOfReshuffles)
                    ReshuffleResult.Done, ReshuffleResult.NoData -> Unit
                }
            } finally {
                _state.value = _state.value.copy(recsLoading = false)
            }
        }
    }

    private fun startLoadingQuip() {
        val quip = LOADING_QUIPS.random(Random(Random.nextLong()))
        _state.value = _state.value.copy(recsLoading = true, recsLoadingQuip = quip)
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        currentLimit = PodcastIndexApi.PAGE_SIZE
        scheduleSearch(loadMore = false)
    }

    fun setTab(tab: SearchTab) {
        _state.value = _state.value.copy(tab = tab)
        currentLimit = PodcastIndexApi.PAGE_SIZE
        scheduleSearch(loadMore = false)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore || s.query.isBlank()) return
        currentLimit += PodcastIndexApi.PAGE_SIZE
        scheduleSearch(loadMore = true)
    }

    private fun scheduleSearch(loadMore: Boolean) {
        searchJob?.cancel()
        val s = _state.value
        if (s.query.isBlank()) {
            _state.value = s.copy(results = emptyList(), loading = false, loadingMore = false, hasMore = false, error = null)
            return
        }
        searchJob =
            viewModelScope.launch {
                if (!loadMore) delay(DEBOUNCE_MS)
                _state.value =
                    _state.value.copy(
                        loading = !loadMore,
                        loadingMore = loadMore,
                        error = null,
                    )
                val limit = currentLimit
                runCatching {
                    when (s.tab) {
                        SearchTab.All -> repo.searchAll(s.query, limit)
                        SearchTab.Title -> repo.searchByTitle(s.query, limit)
                        SearchTab.Person -> repo.searchByPerson(s.query, limit)
                    }
                }.onSuccess { results ->
                    _state.value =
                        _state.value.copy(
                            results = results,
                            loading = false,
                            loadingMore = false,
                            hasMore = results.size >= limit,
                        )
                    if (!loadMore) {
                        telemetry.track(
                            com.kofikodr.kofipod.diagnostics.TelemetryEvent.SearchPerformed(
                                com.kofikodr.kofipod.diagnostics.SearchSource.TYPED,
                            ),
                        )
                    }
                }.onFailure { e ->
                    // Search has no cached results to fall back on, so always surface the
                    // friendly message inline. Snackbar is reserved for screens with a cache.
                    // NetworkErrorHandler.handle() rethrows CancellationException internally,
                    // so we don't need a local guard here.
                    _state.value =
                        _state.value.copy(
                            loading = false,
                            loadingMore = false,
                            error = errors.handle(e, hasCachedData = false, fallback = "Search failed"),
                        )
                }
            }
    }

    companion object {
        const val DEBOUNCE_MS: Long = 600

        /**
         * How many recent episodes the tablet-landscape preview pane surfaces for the
         * selected search result. Matches plan §3.3's "last 4 episodes" guideline.
         */
        const val PREVIEW_EPISODE_LIMIT: Int = 4

        // Long enough for PullToRefreshBox to observe recsLoading=true and play its retract
        // animation cleanly when we short-circuit (e.g. daily cap hit, no API call needed).
        private const val LOADING_MIN_VISIBLE_MS: Long = 450

        // Coffee-themed loading quips. Picked at random so each refresh feels a little different.
        internal val LOADING_QUIPS: List<String> =
            listOf(
                "Brewing a fresh batch…",
                "Grinding the algorithm beans…",
                "Tamping the perfect pull…",
                "Pulling a fresh shot of recs…",
                "Frothing up new shows…",
                "Decaffeinating the noise…",
                "Steeping podcast magic…",
                "Asking the barista for picks…",
                "Sniffing out tasty new feeds…",
                "Skimming the crema for gems…",
            )
    }
}
