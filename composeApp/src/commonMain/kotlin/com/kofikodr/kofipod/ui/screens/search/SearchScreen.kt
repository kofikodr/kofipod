// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.ui.layout.EmptyDetailHint
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.MasterDetailPane
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.primitives.KPButton
import com.kofikodr.kofipod.ui.primitives.KPButtonStyle
import com.kofikodr.kofipod.ui.primitives.KPChip
import com.kofikodr.kofipod.ui.primitives.KPChipTone
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.primitives.LoadMoreRow
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import com.mr3y.podcastindex.model.Category
import org.koin.compose.viewmodel.koinViewModel

private enum class EmptyQueryContent { Loading, ForYou, ColdStart }

@Composable
fun SearchScreen(
    onOpenPodcast: (String) -> Unit,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val selectedSearchResultId by viewModel.selectedSearchResultId.collectAsState()
    val selectedRecentEpisodes by viewModel.selectedRecentEpisodes.collectAsState()
    val selectedInLibrary by viewModel.selectedInLibrary.collectAsState()

    // Surface "out of reshuffles" as a transient toast string, consumed by the rendering below.
    var toastText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            toastText =
                when (event) {
                    SearchEvent.OutOfReshuffles -> "All shuffled out for today — come back tomorrow."
                }
        }
    }

    val tabletSize = LocalTabletSize.current
    // Task 3.4 — single source of truth for "what does tapping a result mean here?"
    // Phone + portraits navigate; landscapes select the master-detail preview. The
    // size-aware decision lives in [routeSearchResultTap]; SearchScreen just consumes it.
    val onResultTap: (String) -> Unit = { podcastId ->
        when (val action = routeSearchResultTap(tabletSize, podcastId)) {
            is SearchResultTapAction.Navigate -> onOpenPodcast(action.podcastId)
            is SearchResultTapAction.Select -> viewModel.selectSearchResult(action.podcastId)
        }
    }

    SearchContent(
        state = state,
        toastText = toastText,
        onToastDone = { toastText = null },
        onQueryChange = viewModel::setQuery,
        onTabSelect = viewModel::setTab,
        onReshuffle = viewModel::reshuffle,
        onLoadMore = viewModel::loadMore,
        onPickTopic = viewModel::setQuery,
        onOpenPodcast = onOpenPodcast,
        onResultTap = onResultTap,
        selectedSearchResultId = selectedSearchResultId,
        selectedRecentEpisodes = selectedRecentEpisodes,
        selectedInLibrary = selectedInLibrary,
        onSubscribe = viewModel::subscribe,
        size = tabletSize,
    )
}

/**
 * Stateless Search body. Phone (`size == null`) renders today's single-column layout
 * unchanged. Tablet portraits (Tablet8Port / Tablet10Port) route to
 * [SearchContentTabletSingle], which keeps the same chrome + result list but caps the
 * content column width for comfortable reading on wider screens. Tablet landscapes
 * route to [SearchContentTabletMasterDetail] — master = scope tabs + results column,
 * detail = [SearchPreviewPane] for the selected result.
 *
 * Result-tap routing is decided one level up in [SearchScreen] via [routeSearchResultTap]
 * and passed in as a single [onResultTap] lambda — leaf composables don't need to know
 * which size they're rendering for. `selectedSearchResultId` / `selectedRecentEpisodes`
 * / `onSubscribe` are only consumed by the landscape branch but are accepted on every
 * call so the public `SearchScreen` doesn't have to fork its argument list per form
 * factor. `internal` so Paparazzi tests can construct it directly.
 */
@Composable
internal fun SearchContent(
    state: SearchUiState,
    toastText: String?,
    onToastDone: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTabSelect: (SearchTab) -> Unit,
    onReshuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onPickTopic: (String) -> Unit,
    onOpenPodcast: (String) -> Unit,
    size: TabletSize?,
    onResultTap: (String) -> Unit = onOpenPodcast,
    selectedSearchResultId: String? = null,
    selectedRecentEpisodes: List<Episode> = emptyList(),
    selectedInLibrary: Boolean = false,
    onSubscribe: (String) -> Unit = {},
) {
    when (size) {
        TabletSize.Tablet8Port, TabletSize.Tablet10Port -> {
            SearchContentTabletSingle(
                state = state,
                toastText = toastText,
                onToastDone = onToastDone,
                onQueryChange = onQueryChange,
                onTabSelect = onTabSelect,
                onReshuffle = onReshuffle,
                onLoadMore = onLoadMore,
                onPickTopic = onPickTopic,
                onOpenPodcast = onOpenPodcast,
                onResultTap = onResultTap,
                size = size,
            )
        }
        TabletSize.Tablet8Land, TabletSize.Tablet10Land -> {
            val selectedSummary =
                remember(selectedSearchResultId, state.results) {
                    if (selectedSearchResultId == null) {
                        null
                    } else {
                        state.results.firstOrNull { it.id == selectedSearchResultId }
                    }
                }
            SearchContentTabletMasterDetail(
                state = state,
                toastText = toastText,
                onToastDone = onToastDone,
                onQueryChange = onQueryChange,
                onTabSelect = onTabSelect,
                onReshuffle = onReshuffle,
                onLoadMore = onLoadMore,
                onPickTopic = onPickTopic,
                onOpenPodcast = onOpenPodcast,
                onResultTap = onResultTap,
                selectedSummary = selectedSummary,
                selectedRecentEpisodes = selectedRecentEpisodes,
                selectedInLibrary = selectedInLibrary,
                onSubscribe = onSubscribe,
            )
        }
        else ->
            SearchContentPhone(
                state = state,
                toastText = toastText,
                onToastDone = onToastDone,
                onQueryChange = onQueryChange,
                onTabSelect = onTabSelect,
                onReshuffle = onReshuffle,
                onLoadMore = onLoadMore,
                onPickTopic = onPickTopic,
                onOpenPodcast = onOpenPodcast,
                onResultTap = onResultTap,
            )
    }
}

@Composable
private fun SearchContentPhone(
    state: SearchUiState,
    toastText: String?,
    onToastDone: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTabSelect: (SearchTab) -> Unit,
    onReshuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onPickTopic: (String) -> Unit,
    onOpenPodcast: (String) -> Unit,
    onResultTap: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            SearchBodyContent(
                state = state,
                onQueryChange = onQueryChange,
                onTabSelect = onTabSelect,
                onReshuffle = onReshuffle,
                onLoadMore = onLoadMore,
                onPickTopic = onPickTopic,
                onOpenPodcast = onOpenPodcast,
                onResultTap = onResultTap,
            )
        }
        SearchToast(text = toastText, onDone = onToastDone)
    }
}

/**
 * Tablet portrait layout (8" / 10" portrait). Same single-column body as phone, but
 * the content column is capped at 720 dp and centered so result rows don't sprawl on
 * wider screens. Horizontal padding is bumped to 28 dp for breathing room.
 *
 * The first-run empty state today renders the hero card + popular-categories chips
 * (see [SearchEmptyState]). The plan calls out a future "starter packs horizontal
 * scroll" treatment with a reusable `StarterPackCard`; that affordance doesn't yet
 * exist on the Search side (starter packs live in Library), so we render today's
 * cold-start content as-is. Follow-up: lift Library's starter packs into a shared
 * composable and surface them here when the design lands.
 */
@Composable
private fun SearchContentTabletSingle(
    state: SearchUiState,
    toastText: String?,
    onToastDone: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTabSelect: (SearchTab) -> Unit,
    onReshuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onPickTopic: (String) -> Unit,
    onOpenPodcast: (String) -> Unit,
    onResultTap: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") size: TabletSize,
) {
    val c = LocalKofipodColors.current
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp)
                        .padding(horizontal = 28.dp),
            ) {
                SearchBodyContent(
                    state = state,
                    onQueryChange = onQueryChange,
                    onTabSelect = onTabSelect,
                    onReshuffle = onReshuffle,
                    onLoadMore = onLoadMore,
                    onPickTopic = onPickTopic,
                    onOpenPodcast = onOpenPodcast,
                    onResultTap = onResultTap,
                )
            }
        }
        SearchToast(text = toastText, onDone = onToastDone)
    }
}

/**
 * Tablet landscape master-detail layout (8" / 10" landscape). Master pane (~46%) is
 * the existing [SearchBodyContent] (header + scope tabs + results list) with the
 * result-tap callback routed to selection instead of navigation. Detail pane (~54%)
 * is [SearchPreviewPane] for the selected result, or an [EmptyDetailHint] when
 * nothing is selected — the hint copy varies based on whether the query is empty.
 *
 * Per plan §3.3 the master/detail split is ~46/54 (wider detail than Library's
 * 62/38), since the preview card is denser than Library's episode list.
 */
@Composable
private fun SearchContentTabletMasterDetail(
    state: SearchUiState,
    toastText: String?,
    onToastDone: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTabSelect: (SearchTab) -> Unit,
    onReshuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onPickTopic: (String) -> Unit,
    onOpenPodcast: (String) -> Unit,
    onResultTap: (String) -> Unit,
    selectedSummary: PodcastSummary?,
    selectedRecentEpisodes: List<Episode>,
    selectedInLibrary: Boolean,
    onSubscribe: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    val emptyHint =
        if (state.query.isBlank()) "Search to find shows" else "Pick a result to preview"
    Box(Modifier.fillMaxSize().background(c.bg)) {
        MasterDetailPane(
            master = {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                ) {
                    SearchBodyContent(
                        state = state,
                        onQueryChange = onQueryChange,
                        onTabSelect = onTabSelect,
                        onReshuffle = onReshuffle,
                        onLoadMore = onLoadMore,
                        onPickTopic = onPickTopic,
                        onOpenPodcast = onOpenPodcast,
                        onResultTap = onResultTap,
                        selectedResultId = selectedSummary?.id,
                    )
                }
            },
            detail = {
                val s = selectedSummary
                if (s != null) {
                    SearchPreviewPane(
                        summary = s,
                        recentEpisodes = selectedRecentEpisodes,
                        inLibrary = selectedInLibrary,
                        onOpenPodcast = { onOpenPodcast(s.id) },
                        onSubscribe = { onSubscribe(s.id) },
                    )
                }
            },
            hasSelection = selectedSummary != null,
            masterWeight = 0.46f,
            emptyDetail = { EmptyDetailHint(text = emptyHint) },
        )
        SearchToast(text = toastText, onDone = onToastDone)
    }
}

/**
 * Right-pane preview for a selected search result. Renders a podcast header (artwork
 * + title + author + category), the Subscribe + Latest CTA pair, the blurb, and the
 * last [SearchViewModel.PREVIEW_EPISODE_LIMIT] episodes (sourced from the DB; empty
 * for unsubscribed shows, which is the common case in Search).
 */
@Composable
private fun SearchPreviewPane(
    summary: PodcastSummary,
    recentEpisodes: List<Episode>,
    inLibrary: Boolean,
    onOpenPodcast: () -> Unit,
    onSubscribe: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KofipodArtwork(
                size = 72.dp,
                seed = summary.feedId.toInt(),
                label = summary.title,
                radius = 14.dp,
                model = summary.artworkUrl.ifBlank { null },
                contentDescription = summary.title,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary.title,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary.author,
                        color = c.textMute,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (summary.category.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    CategoryTag(summary.category)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KPButton(
                label = if (inLibrary) "Subscribed" else "Subscribe",
                onClick = onSubscribe,
                style = if (inLibrary) KPButtonStyle.Outline else KPButtonStyle.PrimaryPink,
                enabled = !inLibrary,
            )
            KPButton(
                label = "Latest",
                onClick = onOpenPodcast,
                style = KPButtonStyle.Outline,
            )
        }
        if (summary.description.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Text(
                summary.description,
                color = c.textSoft,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SectionLabel(title = "Latest episodes", topSpacing = 22.dp)
        if (recentEpisodes.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (inLibrary) {
                    "No episodes cached yet."
                } else {
                    "No episodes cached yet — subscribe to load the feed."
                },
                color = c.textMute,
                fontSize = 13.sp,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth()) {
                recentEpisodes.forEachIndexed { idx, ep ->
                    SearchPreviewEpisodeRow(
                        episode = ep,
                        showDivider = idx < recentEpisodes.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPreviewEpisodeRow(
    episode: Episode,
    showDivider: Boolean,
) {
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(
                episode.title,
                color = c.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.durationSec > 0) {
                Spacer(Modifier.height(2.dp))
                val minutes = (episode.durationSec / 60).coerceAtLeast(0)
                Text(
                    "$minutes MIN",
                    color = c.textMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp,
                )
            }
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        }
    }
}

/**
 * Shared body content used by phone, tablet-portrait, and tablet-landscape (master
 * pane) wrappers. Header + search bar + scope tabs + the empty/loading/results
 * switch — identical across form factors. Layout differences (padding, max-width
 * cap, chrome) live in the wrappers.
 *
 * `onResultTap` lets phone / portraits route to navigation while landscapes route to
 * selection — the decision is made one level up in [SearchScreen] via
 * [routeSearchResultTap]. `onOpenPodcast` is still accepted because the "For you"
 * recommendations row inside the empty-state always navigates — selection only applies
 * to the typed-query results list.
 */
@Composable
private fun SearchBodyContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onTabSelect: (SearchTab) -> Unit,
    onReshuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onPickTopic: (String) -> Unit,
    onOpenPodcast: (String) -> Unit,
    onResultTap: (String) -> Unit = onOpenPodcast,
    selectedResultId: String? = null,
) {
    val c = LocalKofipodColors.current
    Spacer(Modifier.height(24.dp))
    Text(
        "Search",
        color = c.text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        "Powered by the Podcast Index",
        color = c.textMute,
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(16.dp))
    SearchBar(
        value = state.query,
        onValueChange = onQueryChange,
        onClear = { onQueryChange("") },
    )
    Spacer(Modifier.height(14.dp))
    TabRow(current = state.tab, onSelect = onTabSelect)
    Spacer(Modifier.height(16.dp))

    when {
        state.loading ->
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = c.pink)
            }
        state.error != null ->
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(state.error!!, color = c.danger)
            }
        state.results.isEmpty() && state.query.isNotBlank() ->
            Box(
                Modifier.fillMaxSize(),
                Alignment.Center,
            ) {
                Text("No results", color = c.textMute)
            }
        state.results.isEmpty() -> {
            val target =
                when {
                    state.recommendations.isEmpty() && state.recsLoading -> EmptyQueryContent.Loading
                    state.recommendations.isNotEmpty() -> EmptyQueryContent.ForYou
                    else -> EmptyQueryContent.ColdStart
                }
            Crossfade(targetState = target, label = "empty-query-content") { which ->
                when (which) {
                    EmptyQueryContent.Loading ->
                        RecommendationsLoading(quip = state.recsLoadingQuip)
                    EmptyQueryContent.ForYou ->
                        ForYouSection(
                            items = state.recommendations,
                            inlineLoading = state.recsLoading,
                            inlineQuip = state.recsLoadingQuip,
                            onReshuffle = onReshuffle,
                            onOpenPodcast = onOpenPodcast,
                        )
                    EmptyQueryContent.ColdStart ->
                        SearchEmptyState(
                            categories = state.popularCategories,
                            onPickTopic = onPickTopic,
                        )
                }
            }
        }
        else -> {
            ResultsCaption(count = state.results.size)
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(state.results, key = { _, p -> p.id }) { index, p ->
                    ResultCard(
                        p = p,
                        isTopMatch = index == 0,
                        selected = p.id == selectedResultId,
                        onClick = { onResultTap(p.id) },
                    )
                }
                if (state.hasMore) {
                    item(key = "load-more") {
                        LoadMoreRow(loading = state.loadingMore, onClick = onLoadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsCaption(count: Int) {
    val c = LocalKofipodColors.current
    val label = if (count == 1) "1 PODCAST" else "$count PODCASTS"
    Text(
        label,
        color = c.textMute,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.surfaceAlt)
            .border(1.dp, c.border, RoundedCornerShape(r.pill))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Search, color = c.textMute, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Try \"design critique\" or \"Ada Palmer\"",
                    color = c.textMute,
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle =
                    TextStyle(
                        color = c.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.pink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(r.pill))
                    .background(c.purpleTint)
                    .clickable { onClear() },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Close, color = c.textSoft, size = 14.dp)
            }
        }
    }
}

@Composable
private fun TabRow(
    current: SearchTab,
    onSelect: (SearchTab) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchTab.entries.forEach { tab ->
            val selected = tab == current
            KPChip(
                label =
                    when (tab) {
                        SearchTab.All -> "All"
                        SearchTab.Title -> "By title"
                        SearchTab.Person -> "By person"
                    },
                selected = selected,
                tone = if (selected) KPChipTone.Neutral else KPChipTone.Outline,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun ResultCard(
    p: PodcastSummary,
    isTopMatch: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val borderColor = if (selected) c.pink else c.border
    val borderWidth = if (selected) 2.dp else 1.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(borderWidth, borderColor, RoundedCornerShape(r.md))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        KofipodArtwork(
            size = 68.dp,
            seed = p.feedId.toInt(),
            label = p.title,
            radius = 12.dp,
            model = p.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.title,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isTopMatch) {
                    Spacer(Modifier.width(8.dp))
                    TopMatchBadge()
                }
            }
            if (p.author.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    p.author,
                    color = c.textSoft,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    p.description,
                    color = c.textMute,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.category.isNotBlank() || p.episodeCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.category.isNotBlank()) {
                        CategoryTag(p.category)
                    }
                    Spacer(Modifier.weight(1f))
                    if (p.episodeCount > 0) {
                        Text(
                            "${p.episodeCount} eps",
                            color = c.textMute,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopMatchBadge() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .clip(RoundedCornerShape(r.pill))
            .background(c.pink)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "TOP MATCH",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
private fun CategoryTag(label: String) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .clip(RoundedCornerShape(r.pill))
            .border(1.dp, c.border, RoundedCornerShape(r.pill))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = c.textSoft,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ForYouSection(
    items: List<PodcastSummary>,
    inlineLoading: Boolean,
    inlineQuip: String,
    onReshuffle: () -> Unit,
    onOpenPodcast: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    PullToRefreshBox(
        isRefreshing = inlineLoading,
        onRefresh = onReshuffle,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "for-you-header") {
                Column {
                    SectionLabel(title = "For you", topSpacing = 0.dp)
                    Text(
                        if (inlineLoading) inlineQuip else "Based on what you've been listening to · pull to reshuffle",
                        color = c.textMute,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            itemsIndexed(items, key = { _, p -> p.id }) { _, p ->
                ResultCard(
                    p = p,
                    isTopMatch = false,
                    onClick = { onOpenPodcast(p.id) },
                )
            }
        }
    }
}

@Composable
private fun RecommendationsLoading(quip: String) {
    val c = LocalKofipodColors.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = c.pink)
        Spacer(Modifier.height(16.dp))
        Text(
            text = quip.ifBlank { "Brewing a fresh batch…" },
            color = c.textMute,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SearchToast(
    text: String?,
    onDone: () -> Unit,
) {
    val c = LocalKofipodColors.current
    // Drive the auto-dismiss from the outer composable so the exit transition gets to play
    // before the bubble's children leave composition.
    LaunchedEffect(text) {
        if (text != null) {
            kotlinx.coroutines.delay(2200)
            onDone()
        }
    }
    // Latch the last non-null text so the exit transition keeps showing it while fading out.
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = text != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            Box(
                Modifier
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.purple)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text = lastText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

// TODO(tablet-design): The design mock "Search · first run" shows starter
// packs (Slow news / Maker talk / Field notes) appearing in Search, with
// the landscape variant placing them in the detail pane. Starter packs
// currently live in Library only (ui/screens/library/StarterPackScreen.kt).
// Lifting them into Search would be a new-feature addition, so it's out
// of scope for the tablet layout adaptation. If the design intent is
// confirmed, follow up with a separate plan.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchEmptyState(
    categories: List<Category>,
    onPickTopic: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
    ) {
        SearchHeroCard()
        SectionLabel(title = "Popular categories", topSpacing = 22.dp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { category ->
                CategoryAvatarPill(
                    label = category.label,
                    onClick = { onPickTopic(category.label) },
                )
            }
        }
    }
}

@Composable
private fun SearchHeroCard() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    val heroGradient =
        Brush.linearGradient(
            colors = listOf(c.purpleSoft, c.purple, c.purpleDeep),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(r.lg))
                .background(heroGradient),
    ) {
        HeroDecorTiles(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 160.dp, bottom = 22.dp),
        ) {
            val headline =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Over ") }
                    withStyle(SpanStyle(color = c.pink)) { append("4 million") }
                    withStyle(SpanStyle(color = Color.White)) {
                        append(" shows\nare waiting on the other side.")
                    }
                }
            Text(
                headline,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Search by a show title — or the name of someone you'd love to hear.",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/**
 * Three decorative tilted tiles on the hero card. Pure canvas — no data.
 * Left: purple, medium, rotated -16°. Center: pink, largest, slight rotation.
 * Right: orange, medium, rotated +14°. Each carries a tiny 2-letter glyph
 * in white mono as a hint of "podcast cover" without representing a real show.
 */
@Composable
private fun HeroDecorTiles(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(width = 240.dp, height = 140.dp)) {
        DecorTile(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 16.dp, y = 6.dp)
                    .rotate(-16f),
            size = 92.dp,
            brush =
                Brush.linearGradient(
                    colors = listOf(Color(0xFFB084F5), Color(0xFF7E4DE0)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            glyph = "SN",
        )
        DecorTile(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .rotate(-2f),
            size = 112.dp,
            brush =
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFF5FA3), Color(0xFFE11D75)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            glyph = "SS",
        )
        DecorTile(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-16).dp, y = 10.dp)
                    .rotate(14f),
            size = 92.dp,
            brush =
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFFA24B), Color(0xFFE07315)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            glyph = "TR",
        )
    }
}

@Composable
private fun DecorTile(
    modifier: Modifier,
    size: androidx.compose.ui.unit.Dp,
    brush: Brush,
    glyph: String,
) {
    val r = LocalKofipodRadii.current
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(r.lg))
                .background(brush),
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Diagonal stripe overlay for a subtle cover-art texture.
            val stroke = 1.2f.dp.toPx()
            val step = 8.dp.toPx()
            val count = ((this.size.width + this.size.height) / step).toInt() + 2
            val strokeColor = Color.White.copy(alpha = 0.16f)
            for (i in 0..count) {
                val x = i * step - this.size.height
                drawLine(
                    color = strokeColor,
                    start = Offset(x, 0f),
                    end = Offset(x + this.size.height, this.size.height),
                    strokeWidth = stroke,
                )
            }
        }
        Text(
            glyph,
            color = Color.White.copy(alpha = 0.92f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 8.dp),
        )
    }
}

@Composable
private fun CategoryAvatarPill(
    label: String,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(r.pill))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(r.pill))
                .clickable { onClick() }
                .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(c.purple),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                categoryInitials(label),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = c.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

private fun categoryInitials(label: String): String {
    // "True Crime" → "TC"; "Arts" → "AR"; single-word fallback uses first two letters.
    val parts = label.split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
    return if (parts.size >= 2) {
        "${parts[0].first().uppercase()}${parts[1].first().uppercase()}"
    } else {
        label.take(2).uppercase()
    }
}
