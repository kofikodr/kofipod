// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.ui.layout.EmptyDetailHint
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.MasterDetailPane
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.permission.rememberNotificationPermissionRequester
import com.kofikodr.kofipod.ui.primitives.KPButton
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.primitives.LoadMoreRow
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class DetailTab { Episodes, About }

/**
 * Thin Koin-aware wrapper. Owns VM resolution, permission requester, dialog state, and
 * the size-aware episode-tap routing. Delegates the actual layout to
 * [PodcastDetailContent], which is stateless and branches by [TabletSize].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    podcastId: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenEpisode: (String) -> Unit,
    viewModel: PodcastDetailViewModel = koinViewModel { parametersOf(podcastId) },
) {
    val state by viewModel.state.collectAsState()
    val playingEpisodeId by viewModel.playingEpisodeId.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val selectedEpisodeId by viewModel.selectedEpisodeId.collectAsState()
    val tabletSize = LocalTabletSize.current
    val c = LocalKofipodColors.current

    val summary = state.summary
    if (summary == null) {
        Box(Modifier.fillMaxSize().background(c.bg), Alignment.Center) {
            if (state.error != null) {
                Text(state.error!!, color = c.danger)
            } else {
                Text("Loading…", color = c.textMute)
            }
        }
        return
    }

    var listPickerOpen by remember { mutableStateOf(false) }

    val requestNotificationPermission =
        rememberNotificationPermissionRequester { granted ->
            viewModel.toggleNotifyNewEpisodes(granted)
        }

    // Default the preview-pane selection to the newest stored episode when nothing has
    // been picked yet, so landscape rotations never start with an empty detail pane.
    val effectiveSelectedId: String? =
        remember(selectedEpisodeId, state.storedEpisodes) {
            selectedEpisodeId ?: state.storedEpisodes.firstOrNull()?.id
        }
    val selectedEpisode: Episode? =
        remember(effectiveSelectedId, state.storedEpisodes) {
            effectiveSelectedId?.let { id -> state.storedEpisodes.firstOrNull { it.id == id } }
        }

    // Size-aware routing for episode-row taps — see routeEpisodeTap. Phone +
    // tablet portraits navigate; tablet landscapes preview-first via selection.
    val onEpisodeTap: (String) -> Unit = { episodeId ->
        when (val action = routeEpisodeTap(tabletSize, episodeId)) {
            is EpisodeTapAction.Navigate -> onOpenEpisode(action.episodeId)
            is EpisodeTapAction.Select -> viewModel.selectEpisode(action.episodeId)
        }
    }

    PodcastDetailContent(
        state = state,
        playingEpisodeId = playingEpisodeId,
        activePlaybackFlow = viewModel.activePlayback,
        refreshing = refreshing,
        selectedEpisode = selectedEpisode,
        size = tabletSize,
        onBack = onBack,
        onSharePodcast = { viewModel.sharePodcast() },
        onRefresh = { viewModel.refresh() },
        onSaveTap = { listPickerOpen = true },
        onToggleBell = {
            if (!state.inLibrary) return@PodcastDetailContent
            if (state.notifyNewEpisodes) {
                viewModel.toggleNotifyNewEpisodes(false)
            } else {
                requestNotificationPermission()
            }
        },
        onDownloadNewest = {
            if (!state.inLibrary) return@PodcastDetailContent
            val newest = state.storedEpisodes.firstOrNull()?.id
            if (newest != null) viewModel.download(newest)
        },
        onToggleAutoDownload = { viewModel.toggleAutoDownload(it) },
        onEpisodeTap = onEpisodeTap,
        onEpisodeOpen = onOpenEpisode,
        onPlayEpisode = { viewModel.play(it) },
        onDownloadEpisode = { viewModel.download(it) },
        onShareEpisode = { viewModel.shareEpisode(it) },
        onLoadMore = { viewModel.loadMoreEpisodes() },
    )

    if (listPickerOpen) {
        ListPickerDialog(
            lists = state.lists,
            currentListId = state.listId,
            onDismiss = { listPickerOpen = false },
            onPick = {
                viewModel.saveToList(it)
                listPickerOpen = false
            },
        )
    }
}

/**
 * Stateless podcast-detail body. Branches by [size]:
 *  - Phone (`null`) and tablet portraits (`Tablet8Port` / `Tablet10Port`): today's
 *    single-column LazyColumn body — unchanged from pre-Phase-8.
 *  - Tablet landscape (`Tablet8Land` / `Tablet10Land`): master-detail. Master is the
 *    same single-column body; detail pane previews the selected episode and offers an
 *    "Open" CTA that navigates to the full Episode detail route.
 *
 * `selectedEpisode` is only consumed by the landscape branch but lives on the signature
 * so the screen-level hoist stays uniform. `onEpisodeTap` is the single size-aware
 * row-tap callback (see [routeEpisodeTap] upstream); `onEpisodeOpen` is the separate
 * "Open" gesture in the landscape detail pane and the phone/portrait navigate path.
 *
 * The full tab-embedded detail pane (Overview / Chapters / Mentioned / Discuss) from
 * the spec is deliberately deferred — see the plan doc's "Tab embedding deferred" note.
 * This Phase 8 ships the preview-pane variant, mirroring Library and Search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastDetailContent(
    state: DetailUiState,
    playingEpisodeId: String?,
    activePlaybackFlow: StateFlow<ActivePlayback>,
    refreshing: Boolean,
    selectedEpisode: Episode?,
    size: TabletSize?,
    onBack: () -> Unit,
    onSharePodcast: () -> Unit,
    onRefresh: () -> Unit,
    onSaveTap: () -> Unit,
    onToggleBell: () -> Unit,
    onDownloadNewest: () -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onEpisodeTap: (String) -> Unit,
    onEpisodeOpen: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (String) -> Unit,
    onShareEpisode: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val summary = state.summary ?: return
    val isLandscape = size == TabletSize.Tablet8Land || size == TabletSize.Tablet10Land

    val master =
        @Composable {
            PodcastDetailSingleColumn(
                state = state,
                summary = summary,
                playingEpisodeId = playingEpisodeId,
                activePlaybackFlow = activePlaybackFlow,
                refreshing = refreshing,
                onBack = onBack,
                onSharePodcast = onSharePodcast,
                onRefresh = onRefresh,
                onSaveTap = onSaveTap,
                onToggleBell = onToggleBell,
                onDownloadNewest = onDownloadNewest,
                onToggleAutoDownload = onToggleAutoDownload,
                onEpisodeTap = onEpisodeTap,
                onPlayEpisode = onPlayEpisode,
                onDownloadEpisode = onDownloadEpisode,
                onShareEpisode = onShareEpisode,
                onLoadMore = onLoadMore,
            )
        }

    if (!isLandscape) {
        master()
        return
    }

    MasterDetailPane(
        master = master,
        detail = {
            val ep = selectedEpisode
            if (ep != null) {
                EpisodePreviewPane(
                    episode = ep,
                    podcastTitle = summary.title,
                    onPlay = { onPlayEpisode(ep.id) },
                    onOpen = { onEpisodeOpen(ep.id) },
                )
            }
        },
        hasSelection = selectedEpisode != null,
        masterWeight = 0.46f,
        emptyDetail = { EmptyDetailHint(text = "Tap an episode to preview") },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastDetailSingleColumn(
    state: DetailUiState,
    summary: com.kofikodr.kofipod.domain.PodcastSummary,
    playingEpisodeId: String?,
    activePlaybackFlow: StateFlow<ActivePlayback>,
    refreshing: Boolean,
    onBack: () -> Unit,
    onSharePodcast: () -> Unit,
    onRefresh: () -> Unit,
    onSaveTap: () -> Unit,
    onToggleBell: () -> Unit,
    onDownloadNewest: () -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onEpisodeTap: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (String) -> Unit,
    onShareEpisode: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val c = LocalKofipodColors.current
    var tab by remember { mutableStateOf(DetailTab.Episodes) }
    var newestFirst by remember { mutableStateOf(true) }

    val listName = state.listId?.let { id -> state.lists.firstOrNull { it.id == id }?.name }
    val saveLabel =
        when {
            state.inLibrary && listName != null -> "Saved to $listName"
            state.inLibrary -> "Saved"
            else -> "Save to list"
        }

    val inLibrary = state.inLibrary
    val storedEpisodes = state.storedEpisodes
    val remoteEpisodes = state.remoteEpisodes
    val downloadStates = state.downloadStates
    val displayLimit = state.episodeDisplayLimit
    val rows: List<EpisodeRowData> =
        remember(
            inLibrary,
            storedEpisodes,
            remoteEpisodes,
            downloadStates,
            newestFirst,
        ) {
            val mapped =
                if (inLibrary) {
                    storedEpisodes.map {
                        EpisodeRowData(
                            id = it.id,
                            title = it.title,
                            publishedAt = it.publishedAt,
                            durationSec = it.durationSec.toInt(),
                            fileSizeBytes = it.fileSizeBytes,
                            playable = true,
                            downloadState = downloadStates[it.id],
                        )
                    }
                } else {
                    remoteEpisodes.map {
                        EpisodeRowData(
                            id = it.id,
                            title = it.title,
                            publishedAt = 0L,
                            durationSec = it.durationMinutes * 60,
                            fileSizeBytes = 0,
                            playable = it.enclosureUrl.isNotBlank(),
                            downloadState = null,
                        )
                    }
                }
            if (newestFirst) mapped else mapped.asReversed()
        }
    val visibleRows = remember(rows, displayLimit) { rows.take(displayLimit) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().background(c.bg),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                TopIconBar(
                    onBack = onBack,
                    onShare = onSharePodcast,
                    onCheckForEpisodes = onRefresh,
                )
            }
            item { HeroRow(summary) }
            if (summary.description.isNotBlank()) {
                item {
                    Text(
                        summary.description,
                        color = c.textSoft,
                        fontSize = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            } else {
                item { Spacer(Modifier.height(12.dp)) }
            }
            item {
                ActionRow(
                    saveLabel = saveLabel,
                    saved = state.inLibrary,
                    bellOn = state.inLibrary && state.notifyNewEpisodes,
                    bellEnabled = state.inLibrary,
                    onSave = onSaveTap,
                    onToggleBell = onToggleBell,
                    onDownload = onDownloadNewest,
                    downloadEnabled = state.inLibrary,
                )
            }
            if (state.inLibrary) {
                item {
                    AutoDownloadRow(
                        enabled = state.autoDownload,
                        onToggle = onToggleAutoDownload,
                    )
                }
            }
            item { TabsRow(tab = tab, onSelect = { tab = it }, newestFirst = newestFirst, onToggleSort = { newestFirst = !newestFirst }) }

            if (tab == DetailTab.Episodes) {
                if (state.loading && rows.isEmpty()) {
                    item { Text("Loading episodes…", color = c.textMute, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
                }
                state.error?.let { err ->
                    item { Text(err, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
                }

                val hasMore = if (inLibrary) rows.size > visibleRows.size else state.remoteHasMore
                items(visibleRows, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep = ep,
                        isActive = ep.id == playingEpisodeId,
                        canDownload = inLibrary,
                        activePlaybackFlow = activePlaybackFlow,
                        onTap = { onEpisodeTap(ep.id) },
                        onLongPress = { onShareEpisode(ep.id) },
                        onPlay = { onPlayEpisode(ep.id) },
                        onDownload = { onDownloadEpisode(ep.id) },
                    )
                }
                if (hasMore) {
                    item(key = "load-more") {
                        LoadMoreRow(loading = state.loadingMore, onClick = onLoadMore)
                    }
                }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            summary.description.ifBlank { "No description." },
                            color = c.textSoft,
                            fontSize = 14.sp,
                        )
                        if (summary.feedUrl.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                summary.feedUrl,
                                color = c.textMute,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Right-pane preview for the selected episode (tablet landscapes). Eyebrow date +
 * duration, title, podcast name + duration meta, Resume/Play CTA, "Open" affordance
 * that navigates to the full Episode detail route, and the truncated description.
 *
 * No tabs (Chapters / Mentioned / Discuss) — those are deferred to a follow-up; the
 * "Open" button is the bridge to the full experience in the meantime. Mirrors
 * [com.kofikodr.kofipod.ui.screens.library.SubscriptionPreviewPane]'s shape.
 */
@Composable
internal fun EpisodePreviewPane(
    episode: Episode,
    podcastTitle: String,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(24.dp),
    ) {
        val durationSec = episode.durationSec.toInt()
        val eyebrowParts = mutableListOf<String>()
        episode.episodeNumber?.let { eyebrowParts += "EP $it" }
        if (episode.publishedAt > 0) eyebrowParts += formatDate(episode.publishedAt)
        if (eyebrowParts.isNotEmpty()) {
            Text(
                eyebrowParts.joinToString("  ·  "),
                color = c.pink,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.08.em,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            episode.title,
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        val metaParts = mutableListOf(podcastTitle)
        if (durationSec > 0) metaParts += formatDuration(durationSec)
        if (episode.fileSizeBytes > 0) metaParts += formatMb(episode.fileSizeBytes)
        Text(
            metaParts.joinToString("  ·  "),
            color = c.textMute,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KPButton(
                label = "Play",
                onClick = onPlay,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, c.border, RoundedCornerShape(999.dp))
                    .clickable { onOpen() }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Open",
                    color = c.textSoft,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
        if (episode.description.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(
                episode.description,
                color = c.textSoft,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TopIconBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
    onCheckForEpisodes: () -> Unit,
) {
    val c = LocalKofipodColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShare) {
            KPIcon(name = KPIconName.Share, color = c.text, size = 20.dp, strokeWidth = 1.6f)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                KPIcon(name = KPIconName.More, color = c.text, size = 20.dp, strokeWidth = 1.6f)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Check for episodes", color = c.text) },
                    onClick = {
                        menuOpen = false
                        onCheckForEpisodes()
                    },
                )
            }
        }
    }
}

@Composable
private fun IconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun HeroRow(summary: com.kofikodr.kofipod.domain.PodcastSummary) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        KofipodArtwork(
            size = 108.dp,
            seed = summary.feedId.toInt(),
            label = summary.title,
            radius = r.md,
            model = summary.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            if (summary.category.isNotBlank()) {
                Text(
                    summary.category.uppercase(),
                    color = c.pink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.08.em,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                summary.title,
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.author.isNotBlank()) {
                Text(
                    summary.author,
                    color = c.textSoft,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
            if (summary.episodeCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${summary.episodeCount} EPS",
                    color = c.textMute,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    saveLabel: String,
    saved: Boolean,
    bellOn: Boolean,
    bellEnabled: Boolean,
    onSave: () -> Unit,
    onToggleBell: () -> Unit,
    onDownload: () -> Unit,
    downloadEnabled: Boolean,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pink pill — prefixed with check when saved
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(999.dp))
                .background(c.pink)
                .clickable { onSave() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (saved) {
                    KPIcon(name = KPIconName.Check, color = Color.White, size = 16.dp, strokeWidth = 2.4f)
                    Spacer(Modifier.width(8.dp))
                }
                Text(saveLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        val bellTint =
            when {
                !bellEnabled -> c.textMute
                bellOn -> c.pink
                else -> c.purple
            }
        CircleButton(onClick = onToggleBell, tint = bellTint) {
            KPIcon(name = KPIconName.Bell, color = bellTint, size = 18.dp)
        }
        Spacer(Modifier.width(8.dp))
        CircleButton(onClick = onDownload, tint = if (downloadEnabled) c.purple else c.textMute) {
            KPIcon(
                name = KPIconName.Download,
                color = if (downloadEnabled) c.purple else c.textMute,
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun AutoDownloadRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Auto-download new episodes",
                color = c.text,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Text(
                "On Wi-Fi while charging",
                color = c.textMute,
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = c.pink,
                    checkedTrackColor = c.pinkSoft,
                ),
        )
    }
}

@Composable
private fun CircleButton(
    onClick: () -> Unit,
    tint: Color,
    content: @Composable () -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.purpleTint)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun TabsRow(
    tab: DetailTab,
    onSelect: (DetailTab) -> Unit,
    newestFirst: Boolean,
    onToggleSort: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPill("Episodes", selected = tab == DetailTab.Episodes) { onSelect(DetailTab.Episodes) }
        Spacer(Modifier.width(16.dp))
        TabPill("About", selected = tab == DetailTab.About) { onSelect(DetailTab.About) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clickable { onToggleSort() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (newestFirst) "Newest first" else "Oldest first",
                color = c.textSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(4.dp))
            KPIcon(name = KPIconName.ChevronDown, color = c.textSoft, size = 14.dp)
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) c.text else c.textMute,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) c.pink else Color.Transparent),
        )
    }
}

private data class EpisodeRowData(
    val id: String,
    val title: String,
    val publishedAt: Long,
    val durationSec: Int,
    val fileSizeBytes: Long,
    val playable: Boolean,
    val downloadState: String?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    ep: EpisodeRowData,
    isActive: Boolean,
    canDownload: Boolean,
    activePlaybackFlow: StateFlow<ActivePlayback>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val playable = ep.playable
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EpisodePlayButton(
            isActive = isActive,
            activePlaybackFlow = activePlaybackFlow,
            enabled = playable,
            onClick = { if (playable) onPlay() },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ep.title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                episodeMetaLine(ep.publishedAt, ep.durationSec, ep.fileSizeBytes),
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(8.dp))
        StateIndicator(
            ep = ep,
            canDownload = canDownload,
            onDownload = { if (playable && canDownload) onDownload() },
        )
    }
}

@Composable
private fun EpisodePlayButton(
    isActive: Boolean,
    activePlaybackFlow: StateFlow<ActivePlayback>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (isActive) {
        val playback by activePlaybackFlow.collectAsState()
        ActivePlayButton(
            isPlaying = playback.isPlaying,
            progress = playback.progress,
            enabled = enabled,
            onClick = onClick,
        )
    } else {
        IdlePlayButton(enabled = enabled, onClick = onClick)
    }
}

@Composable
private fun ActivePlayButton(
    isPlaying: Boolean,
    progress: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "epProgress")
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.purple)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val stroke = 2.5.dp.toPx()
            drawArc(
                color = c.pink,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        KPIcon(
            name = if (isPlaying) KPIconName.Pause else KPIconName.Play,
            color = Color.White,
            size = 16.dp,
        )
    }
}

@Composable
private fun IdlePlayButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.purpleTint)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = KPIconName.Play, color = c.purple, size = 16.dp)
    }
}

@Composable
private fun StateIndicator(
    ep: EpisodeRowData,
    canDownload: Boolean,
    onDownload: () -> Unit,
) {
    val c = LocalKofipodColors.current
    when (ep.downloadState) {
        "Completed" ->
            Box(Modifier.size(28.dp), Alignment.Center) {
                KPIcon(name = KPIconName.Check, color = c.success, size = 18.dp, strokeWidth = 2.2f)
            }
        "Downloading", "Queued" ->
            Box(Modifier.size(28.dp), Alignment.Center) {
                KPIcon(name = KPIconName.Clock, color = c.pink, size = 18.dp)
            }
        else -> {
            val active = ep.playable && canDownload
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, c.border, RoundedCornerShape(999.dp))
                    .clickable(enabled = active) { onDownload() },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = KPIconName.Download,
                    color = if (active) c.textSoft else c.textMute,
                    size = 14.dp,
                    strokeWidth = 1.7f,
                )
            }
        }
    }
}

@Composable
private fun ListPickerDialog(
    lists: List<PodcastList>,
    currentListId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.surface)
                .padding(20.dp),
        ) {
            Text("Save to…", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            lists.forEach { list ->
                PickerRow(
                    label = list.name,
                    selected = list.id == currentListId,
                    onClick = { onPick(list.id) },
                )
            }
            PickerRow(
                label = "Unfiled",
                selected = currentListId == null,
                onClick = { onPick(null) },
            )
            if (lists.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No lists yet — tap Unfiled to save without a list (you can create lists in Library).",
                    color = c.textMute,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "●" else "○", color = if (selected) c.pink else c.textMute, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = c.text, fontWeight = FontWeight.Medium)
    }
}
