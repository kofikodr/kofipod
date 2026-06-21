// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.api.PodcastIndexApi
import com.kofikodr.kofipod.data.api.isItunesOnlyId
import com.kofikodr.kofipod.data.net.NetworkErrorHandler
import com.kofikodr.kofipod.data.recommend.RecommendationsRepository
import com.kofikodr.kofipod.data.recommend.RecommendationsSource
import com.kofikodr.kofipod.data.recommend.ReshuffleResult
import com.kofikodr.kofipod.data.repo.CategoriesSource
import com.kofikodr.kofipod.data.repo.SearchSource
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.opml.PodcastFeedLookup
import com.mr3y.podcastindex.model.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class SearchTab { All, Title, Person }

sealed interface SearchEvent {
    data object OutOfReshuffles : SearchEvent

    /**
     * The viewmodel resolved a tapped result to a numeric Podcast Index id. The
     * screen reacts by routing through `SearchResultTapAction` (Navigate on phone /
     * tablet portrait, Select on tablet landscape). This indirection only matters
     * for iTunes-only results that need PI hydration first — direct numeric ids
     * fire this event synchronously.
     */
    data class NavigateToPodcast(val podcastId: String) : SearchEvent

    /**
     * Tapping an iTunes-only result failed to resolve to a Podcast Index feed (PI
     * 404, network error, or unexpected response). The screen surfaces [message]
     * as a snackbar. Slice B will replace this with a direct-RSS fallback path.
     */
    data class HydrationFailed(val message: String) : SearchEvent
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
    /**
     * The result id currently being hydrated (PI lookup in flight for an iTunes-only
     * tap). Used by [SearchViewModel.requestNavigation] to suppress repeat taps on
     * the same row while a hydration is already in flight. `null` when no hydration
     * is pending. A row-level loading affordance is not wired in Slice A — adding one
     * would require a result-row recomposition path; the suppression alone keeps the
     * VM idempotent under rapid double-taps.
     */
    val hydratingId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val repo: SearchSource,
    categories: CategoriesSource,
    private val recommendations: RecommendationsSource,
    private val appScope: CoroutineScope,
    private val errors: NetworkErrorHandler,
    private val telemetry: com.kofikodr.kofipod.diagnostics.Telemetry,
    private val feedLookup: PodcastFeedLookup,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(popularCategories = categories.popular()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // One-shot navigation/notification events.
    //
    // NOTE on rotation/resize: the original concern was that the screen-side
    // collector re-keyed on `tabletSize`, causing it to detach + re-attach during
    // rotation and drop events emitted in the gap. The fix lives in `SearchScreen` —
    // the LaunchedEffect now keys ONLY on the ViewModel and reads `tabletSize` via
    // `rememberUpdatedState` so the collector never tears down on rotation. With
    // that in place a SharedFlow is the right choice: a buffered Channel would have
    // queued events past the user's screen exit and replayed stale navigation when
    // they came back to Search (Search is a bottom-nav destination whose VM
    // survives tab switches).
    private val _events = MutableSharedFlow<SearchEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SearchEvent> = _events.asSharedFlow()

    private var searchJob: Job? = null
    private var currentLimit: Int = PodcastIndexApi.PAGE_SIZE
    private var searchGeneration: Long = 0L
    private var hydrateJob: Job? = null

    /**
     * Debounced query channel. [setQuery]/[setTab] push the user's intent here without
     * launching a coroutine per keystroke; a single [kotlinx.coroutines.flow.collectLatest]
     * downstream of [kotlinx.coroutines.flow.debounce] runs the actual search and
     * auto-cancels any in-flight request when a newer query arrives. Replaces the prior
     * `searchJob?.cancel() + viewModelScope.launch + delay(DEBOUNCE_MS)` pattern on every
     * keystroke, which piled up Main-thread frames under rapid input (see ANR bug fix).
     */
    private data class QueryKey(
        val query: String,
        val tab: SearchTab,
        val generation: Long,
    )

    private val queryChannel = MutableStateFlow(QueryKey("", SearchTab.All, searchGeneration))

    /**
     * Selected search result on tablet landscape. Drives the master-detail right pane
     * (which embeds [com.kofikodr.kofipod.ui.screens.detail.PodcastDetailScreen] for the
     * picked id). `null` means "show the empty-detail hint." VM-local UI state — not
     * routed; the URL only changes when the user explicitly opens the detail elsewhere.
     */
    private val _selectedSearchResultId = MutableStateFlow<String?>(null)
    val selectedSearchResultId: StateFlow<String?> = _selectedSearchResultId.asStateFlow()

    fun selectSearchResult(podcastId: String?) {
        _selectedSearchResultId.value = podcastId
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
        viewModelScope.launch {
            queryChannel
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key.query.isBlank()) {
                        _state.value =
                            _state.value.copy(
                                results = emptyList(),
                                loading = false,
                                loadingMore = false,
                                hasMore = false,
                                error = null,
                            )
                    } else {
                        runSearch(loadMore = false, key = key)
                    }
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

    /**
     * Resolves a tapped result id to a numeric Podcast Index id, then emits
     * [SearchEvent.NavigateToPodcast]. For results that came directly from Podcast
     * Index (or merged with PI's identity) the id is already numeric and the event
     * fires synchronously. For iTunes-only results (id prefixed with `itunes:`) we
     * look the feed up against PI by URL; on success, navigate with the resolved
     * feedId; on failure, emit [SearchEvent.HydrationFailed] so the screen can show
     * a snackbar.
     *
     * A tap on a different iTunes-only row cancels any in-flight hydration. A tap on
     * the **same** in-flight row is a no-op so a rapid double-tap doesn't queue two
     * lookups.
     */
    fun requestNavigation(rawId: String) {
        if (!rawId.isItunesOnlyId()) {
            // A tap on a Podcast Index row supersedes any in-flight iTunes hydration
            // — otherwise the hydration could complete later and navigate the user
            // away from the row they just opened.
            cancelHydration()
            _events.tryEmit(SearchEvent.NavigateToPodcast(rawId))
            return
        }
        // Suppress repeat taps on the row already being hydrated. A different row
        // still cancels the old job below.
        if (_state.value.hydratingId == rawId) return
        val tapped = _state.value.results.firstOrNull { it.id == rawId }
        val feedUrl = tapped?.feedUrl
        if (feedUrl.isNullOrBlank()) {
            _events.tryEmit(SearchEvent.HydrationFailed(HYDRATION_FALLBACK_MESSAGE))
            return
        }
        hydrateJob?.cancel()
        _state.value = _state.value.copy(hydratingId = rawId)
        hydrateJob =
            viewModelScope.launch {
                try {
                    val resolved = feedLookup.resolve(feedUrl)
                    // Stale check — a query change / tab change / non-iTunes tap that
                    // happened mid-flight cleared hydratingId. Emitting NavigateToPodcast
                    // now would steal focus from whatever the user just did.
                    if (_state.value.hydratingId != rawId) return@launch
                    // Rewrite the row's id from the itunes: sentinel to the resolved PI
                    // id so (a) the tablet-landscape selection highlight matches the
                    // tapped row after Select, and (b) re-taps go straight through the
                    // numeric branch instead of re-hydrating.
                    val updatedResults =
                        _state.value.results.map { p ->
                            if (p.id == rawId) p.copy(id = resolved.id, feedId = resolved.feedId) else p
                        }
                    _state.value = _state.value.copy(results = updatedResults, hydratingId = null)
                    _events.tryEmit(SearchEvent.NavigateToPodcast(resolved.id))
                } catch (e: CancellationException) {
                    // Structured cancellation must propagate. Do NOT clear hydratingId
                    // here — the cancelling caller already updated it.
                    throw e
                } catch (e: Throwable) {
                    if (_state.value.hydratingId != rawId) return@launch
                    _state.value = _state.value.copy(hydratingId = null)
                    val resolved =
                        errors.handle(e, hasCachedData = false, fallback = HYDRATION_FALLBACK_MESSAGE)
                            ?: HYDRATION_FALLBACK_MESSAGE
                    _events.tryEmit(SearchEvent.HydrationFailed(resolved))
                }
            }
    }

    private fun cancelHydration() {
        hydrateJob?.cancel()
        hydrateJob = null
        val current = _state.value
        if (current.hydratingId != null) {
            _state.value = current.copy(hydratingId = null)
        }
    }

    fun setQuery(q: String) {
        // Cancel any in-flight loadMore before resetting limit so a stale paged result
        // can't write back over the fresh query's first page.
        searchJob?.cancel()
        // Also drop any in-flight iTunes hydration — its target row may no longer be
        // in the result list once the new query lands.
        cancelHydration()
        _state.value = _state.value.copy(query = q)
        currentLimit = PodcastIndexApi.PAGE_SIZE
        searchGeneration += 1
        queryChannel.value = QueryKey(q, _state.value.tab, searchGeneration)
        // Stale selection would leave the tablet-landscape right pane pinned to the
        // previous query's result while the master shows new results.
        _selectedSearchResultId.value = null
    }

    fun setTab(tab: SearchTab) {
        searchJob?.cancel()
        cancelHydration()
        _state.value = _state.value.copy(tab = tab)
        currentLimit = PodcastIndexApi.PAGE_SIZE
        searchGeneration += 1
        queryChannel.value = QueryKey(_state.value.query, tab, searchGeneration)
        _selectedSearchResultId.value = null
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore || s.query.isBlank()) return
        currentLimit += PodcastIndexApi.PAGE_SIZE
        val loadMoreLimit = currentLimit
        val loadMoreKey = QueryKey(s.query, s.tab, searchGeneration)
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                runSearch(loadMore = true, key = loadMoreKey, limit = loadMoreLimit)
            }
    }

    private suspend fun runSearch(
        loadMore: Boolean,
        key: QueryKey,
        limit: Int = currentLimit,
    ) {
        _state.value =
            _state.value.copy(
                loading = !loadMore,
                loadingMore = loadMore,
                error = null,
            )
        try {
            val results =
                when (key.tab) {
                    SearchTab.All -> repo.searchAll(key.query, limit)
                    SearchTab.Title -> repo.searchByTitle(key.query, limit)
                    SearchTab.Person -> repo.searchByPerson(key.query, limit)
                }
            if (!isCurrentSearch(key)) return
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
        } catch (e: CancellationException) {
            // The `collectLatest` driver above cancels us when a newer query
            // arrives. `runCatching` would swallow this and let the cancelled run
            // clear loading state or surface a phantom error for the newer search
            // — break the cancellation chain. AggregateSearchSource already
            // converts timeouts to a thrown error before this point, so any
            // CancellationException reaching here is structural.
            throw e
        } catch (e: Throwable) {
            if (!isCurrentSearch(key)) return
            _state.value =
                _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = errors.handle(e, hasCachedData = false, fallback = "Search failed"),
                )
        }
    }

    private fun isCurrentSearch(key: QueryKey): Boolean {
        val current = _state.value
        return current.query == key.query && current.tab == key.tab && searchGeneration == key.generation
    }

    companion object {
        const val DEBOUNCE_MS: Long = 600

        /**
         * User-facing message when an iTunes-only result can't be resolved to a
         * Podcast Index feed (PI doesn't have it, network unavailable, etc.).
         * Slice B (direct-RSS fallback) will narrow this down or eliminate it.
         */
        internal const val HYDRATION_FALLBACK_MESSAGE: String =
            "This feed isn't in our index yet — try again later"

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
