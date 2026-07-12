// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.kofikodr.kofipod.ui.primitives.DownloadActionButton
import com.kofikodr.kofipod.ui.primitives.DownloadButtonState
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.primitives.LoadMoreRow
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class DetailTab { Episodes, About }

private const val SEEN_DWELL_MS = 1_500L

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
    // Defaults so the SearchScreen embed (which renders this screen with
    // LocalTabletSize forced to `null`, i.e. phone single-column) doesn't need
    // to plumb master-detail-only callbacks it can't trigger. Real navigation
    // is supplied by the standalone NavHost call site.
    onOpenAiSetup: () -> Unit = {},
    onOpenAskGemini: (String) -> Unit = {},
    onOpenSnippetEditor: (String) -> Unit = {},
    // `key = podcastId` is load-bearing for embedded use (Search tablet-landscape
    // master-detail): the ViewModelStoreOwner stays the same as the user clicks
    // through results, so without a per-id key Koin would hand out the FIRST VM
    // for every subsequent podcast — the detail pane would stick on result #1.
    viewModel: PodcastDetailViewModel =
        koinViewModel(key = podcastId) { parametersOf(podcastId) },
) {
    val state by viewModel.state.collectAsState()
    val playingEpisodeId by viewModel.playingEpisodeId.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val selectedEpisodeId by viewModel.selectedEpisodeId.collectAsState()

    LaunchedEffect(Unit) {
        // Dwell guard: this effect's coroutine is cancelled when the screen leaves
        // composition, so backing out before SEEN_DWELL_MS elapses skips the write —
        // an accidental tap-and-back won't dismiss the "new" dot. markSeen() is a
        // no-op off-library and idempotent, so re-entry is safe.
        delay(SEEN_DWELL_MS)
        viewModel.markSeen()
    }

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

    // Default the preview-pane selection to the newest row when nothing has been picked
    // yet, so landscape rotations never start with an empty detail pane. Falls back to
    // remoteEpisodes for unsubscribed podcasts (Search → result → tablet landscape).
    val effectiveSelectedId: String? =
        remember(selectedEpisodeId, state.storedEpisodes, state.remoteEpisodes) {
            selectedEpisodeId
                ?: state.storedEpisodes.firstOrNull()?.id
                ?: state.remoteEpisodes.firstOrNull()?.id
        }
    // Resolve the selected episode against stored rows first (full Episode), then
    // fall back to a transient projection of the matching EpisodePreview so the
    // preview pane renders for unsubscribed podcasts too. Without this, tapping an
    // episode in Search → tablet landscape silently navigated to an empty full-screen
    // EpisodeDetail (the DB row doesn't exist for unsubscribed shows).
    val selectedEpisode: Episode? =
        remember(effectiveSelectedId, state.storedEpisodes, state.remoteEpisodes, podcastId) {
            val id = effectiveSelectedId ?: return@remember null
            state.storedEpisodes.firstOrNull { it.id == id }
                ?: state.remoteEpisodes.firstOrNull { it.id == id }?.toTransientEpisode(podcastId)
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
        downloadStatesFlow = viewModel.downloadStates,
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
        onCancelNewestDownload = {
            val newest = state.storedEpisodes.firstOrNull()?.id
            if (newest != null) viewModel.cancelDownload(newest)
        },
        onDeleteNewestDownload = {
            val newest = state.storedEpisodes.firstOrNull()?.id
            if (newest != null) viewModel.deleteDownload(newest)
        },
        onToggleAutoDownload = { viewModel.toggleAutoDownload(it) },
        onEpisodeTap = onEpisodeTap,
        onPlayEpisode = { viewModel.play(it) },
        onDownloadEpisode = { viewModel.download(it) },
        onCancelDownload = { viewModel.cancelDownload(it) },
        onDeleteEpisodeDownload = { viewModel.deleteDownload(it) },
        onShareEpisode = { viewModel.shareEpisode(it) },
        onLoadMore = { viewModel.loadMoreEpisodes() },
        onOpenPlayer = onOpenPlayer,
        onOpenAiSetup = onOpenAiSetup,
        onOpenAskGemini = onOpenAskGemini,
        onOpenSnippetEditor = onOpenSnippetEditor,
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
 *    same single-column body; detail pane embeds the full [EpisodeDetailScreen] for
 *    the selected episode via `HostMode.MasterDetailPane`.
 *
 * `selectedEpisode` is only consumed by the landscape branch but lives on the signature
 * so the screen-level hoist stays uniform. `onEpisodeTap` is the single size-aware
 * row-tap callback (see [routeEpisodeTap] upstream).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastDetailContent(
    state: DetailUiState,
    playingEpisodeId: String?,
    activePlaybackFlow: StateFlow<ActivePlayback>,
    downloadStatesFlow: StateFlow<Map<String, DownloadButtonState>>,
    refreshing: Boolean,
    selectedEpisode: Episode?,
    size: TabletSize?,
    onBack: () -> Unit,
    onSharePodcast: () -> Unit,
    onRefresh: () -> Unit,
    onSaveTap: () -> Unit,
    onToggleBell: () -> Unit,
    onDownloadNewest: () -> Unit,
    onCancelNewestDownload: () -> Unit,
    onDeleteNewestDownload: () -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onEpisodeTap: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteEpisodeDownload: (String) -> Unit,
    onShareEpisode: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    onOpenAiSetup: () -> Unit = {},
    onOpenAskGemini: (String) -> Unit = {},
    onOpenSnippetEditor: (String) -> Unit = {},
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
                downloadStatesFlow = downloadStatesFlow,
                refreshing = refreshing,
                selectedEpisodeId = if (isLandscape) selectedEpisode?.id else null,
                onBack = onBack,
                onSharePodcast = onSharePodcast,
                onRefresh = onRefresh,
                onSaveTap = onSaveTap,
                onToggleBell = onToggleBell,
                onDownloadNewest = onDownloadNewest,
                onCancelNewestDownload = onCancelNewestDownload,
                onDeleteNewestDownload = onDeleteNewestDownload,
                onToggleAutoDownload = onToggleAutoDownload,
                onEpisodeTap = onEpisodeTap,
                onPlayEpisode = onPlayEpisode,
                onDownloadEpisode = onDownloadEpisode,
                onCancelDownload = onCancelDownload,
                onDeleteEpisodeDownload = onDeleteEpisodeDownload,
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
                // `key(ep.id)` forces a fresh subtree (and therefore a fresh
                // `koinViewModel` keyed by episodeId via the wrapper) when the
                // user picks a different episode in the master list — otherwise
                // Compose would reuse the same EpisodeDetailViewModel instance
                // for every selection in this ViewModelStoreOwner scope.
                key(ep.id) {
                    EpisodeDetailScreen(
                        episodeId = ep.id,
                        onBack = {},
                        onOpenPlayer = onOpenPlayer,
                        onOpenAiSetup = onOpenAiSetup,
                        onOpenAskGemini = onOpenAskGemini,
                        onOpenSnippetEditor = onOpenSnippetEditor,
                        hostMode = HostMode.MasterDetailPane,
                    )
                }
            }
        },
        hasSelection = selectedEpisode != null,
        masterWeight = 0.5f,
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
    downloadStatesFlow: StateFlow<Map<String, DownloadButtonState>>,
    refreshing: Boolean,
    selectedEpisodeId: String? = null,
    onBack: () -> Unit,
    onSharePodcast: () -> Unit,
    onRefresh: () -> Unit,
    onSaveTap: () -> Unit,
    onToggleBell: () -> Unit,
    onDownloadNewest: () -> Unit,
    onCancelNewestDownload: () -> Unit,
    onDeleteNewestDownload: () -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onEpisodeTap: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteEpisodeDownload: (String) -> Unit,
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
    val displayLimit = state.episodeDisplayLimit
    // Prefer stored rows for subscribed shows (carries real publishedAt). Fall
    // back to remote when stored is empty so subscriptions whose Episode rows
    // were never persisted still render — the API result is already in memory
    // from the VM's init `loadRemote`. Single source of truth for both row
    // mapping and `hasMore` paging below.
    //
    // EpisodeRowData intentionally carries NO download state. Each row's
    // download visual lives inside StateIndicator, which collects
    // downloadStatesFlow itself — that keeps the ~5 Hz progress ticks from
    // forcing the whole row list (and the 500 ms playback ticker) to recompose.
    val useStored = inLibrary && storedEpisodes.isNotEmpty()
    val rows: List<EpisodeRowData> =
        remember(
            useStored,
            storedEpisodes,
            remoteEpisodes,
            newestFirst,
        ) {
            val mapped =
                if (useStored) {
                    storedEpisodes.map {
                        EpisodeRowData(
                            id = it.id,
                            title = it.title,
                            publishedAt = it.publishedAt,
                            durationSec = it.durationSec.toInt(),
                            fileSizeBytes = it.fileSizeBytes,
                            playable = true,
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
                val newestId = state.storedEpisodes.firstOrNull()?.id
                ActionRow(
                    saveLabel = saveLabel,
                    saved = state.inLibrary,
                    bellOn = state.inLibrary && state.notifyNewEpisodes,
                    bellEnabled = state.inLibrary,
                    onSave = onSaveTap,
                    onToggleBell = onToggleBell,
                    onDownload = onDownloadNewest,
                    onCancelDownload = onCancelNewestDownload,
                    onDeleteDownload = onDeleteNewestDownload,
                    downloadEnabled = state.inLibrary,
                    newestEpisodeId = newestId,
                    downloadStatesFlow = downloadStatesFlow,
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

                // When rows came from the fallback (subscribed but stored empty), the
                // pagination signal lives on the remote side too — `useStored` is the
                // single source of truth for row source / paging source.
                val hasMore = if (useStored) rows.size > visibleRows.size else state.remoteHasMore
                items(visibleRows, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep = ep,
                        isActive = ep.id == playingEpisodeId,
                        isSelected = ep.id == selectedEpisodeId,
                        canDownload = inLibrary,
                        activePlaybackFlow = activePlaybackFlow,
                        downloadStatesFlow = downloadStatesFlow,
                        onTap = { onEpisodeTap(ep.id) },
                        onLongPress = { onShareEpisode(ep.id) },
                        onPlay = { onPlayEpisode(ep.id) },
                        onDownload = { onDownloadEpisode(ep.id) },
                        onCancelDownload = { onCancelDownload(ep.id) },
                        onDeleteDownload = { onDeleteEpisodeDownload(ep.id) },
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
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    downloadEnabled: Boolean,
    newestEpisodeId: String?,
    downloadStatesFlow: StateFlow<Map<String, DownloadButtonState>>,
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
        // Scoped collect so the bell + save pill never recompose on a download
        // tick — only this button reads the progress map.
        val states by downloadStatesFlow.collectAsState()
        val newestState =
            newestEpisodeId?.let { states[it] } ?: DownloadButtonState.Idle
        val iconTint = if (downloadEnabled) c.purple else c.textMute
        DownloadActionButton(
            state = newestState,
            size = 44.dp,
            iconColor = iconTint,
            background = c.purpleTint,
            onIdleClick = onDownload,
            onCancel = onCancelDownload,
            onRetry = onDownload,
            onDelete = onDeleteDownload,
            iconSize = 18.dp,
        )
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
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    ep: EpisodeRowData,
    isActive: Boolean,
    canDownload: Boolean,
    activePlaybackFlow: StateFlow<ActivePlayback>,
    downloadStatesFlow: StateFlow<Map<String, DownloadButtonState>>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    isSelected: Boolean = false,
) {
    val c = LocalKofipodColors.current
    val playable = ep.playable
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) c.purpleTint else Color.Transparent)
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
            episodeId = ep.id,
            isPlayable = playable,
            canDownload = canDownload,
            downloadStatesFlow = downloadStatesFlow,
            onDownload = { if (playable && canDownload) onDownload() },
            onCancelDownload = onCancelDownload,
            onDelete = onDeleteDownload,
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
    episodeId: String,
    isPlayable: Boolean,
    canDownload: Boolean,
    downloadStatesFlow: StateFlow<Map<String, DownloadButtonState>>,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalKofipodColors.current
    // Per-row collect: this is the only point in the row tree that re-reads the
    // 5 Hz progress map. EpisodeRow and its siblings never see download state
    // and therefore never recompose on a progress tick.
    val states by downloadStatesFlow.collectAsState()
    val state = states[episodeId] ?: DownloadButtonState.Idle
    val active = isPlayable && canDownload
    DownloadActionButton(
        state = state,
        size = 32.dp,
        iconColor = if (active) c.textSoft else c.textMute,
        background = Color.Transparent,
        border = BorderStroke(1.dp, c.border),
        iconSize = 14.dp,
        onIdleClick = { if (active) onDownload() },
        onCancel = onCancelDownload,
        onRetry = { if (active) onDownload() },
        onDelete = onDelete,
    )
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

// Projects an unsubscribed-feed `EpisodePreview` into a transient `Episode` so the
// landscape master row can identify the selected episode and pass its id into the
// embedded `EpisodeDetailScreen`. Never persisted — `EpisodeDetailViewModel`
// resolves unsubscribed ids through `RemoteEpisodeCache`. Missing fields collapse
// to the SQLDelight schema's NOT-NULL defaults.
private fun EpisodePreview.toTransientEpisode(podcastId: String): Episode =
    Episode(
        id = id,
        podcastId = podcastId,
        guid = id,
        title = title,
        description = "",
        publishedAt = 0L,
        durationSec = (durationMinutes * 60).toLong(),
        enclosureUrl = enclosureUrl,
        enclosureMimeType = "",
        fileSizeBytes = 0L,
        seasonNumber = null,
        episodeNumber = episodeNumber?.toLong(),
        imageUrl = "",
        chaptersUrl = null,
        transcriptUrl = null,
    )
