// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.palette.rememberTileVisuals
import com.kofikodr.kofipod.ui.primitives.KPButton
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel
import com.kofikodr.kofipod.playlists.SmartPlaylist as SmartPlaylistDomain

@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenList: (String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStarterPack: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenLibrarySearch: () -> Unit,
    onOpenSmartPlaylistEditor: (playlistId: String?, initialName: String?) -> Unit,
    onOpenSmartPlaylistDetail: (playlistId: String) -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    var newListOpen by remember { mutableStateOf(false) }
    var pendingDeletePodcast by remember { mutableStateOf<Podcast?>(null) }
    var pendingDeleteList by remember { mutableStateOf<PodcastList?>(null) }
    var pendingDeleteSmartPlaylist by remember { mutableStateOf<SmartPlaylistDomain?>(null) }

    LibraryContent(
        state = state,
        onOpenPodcast = onOpenPodcast,
        onOpenList = onOpenList,
        onOpenSearch = onOpenSearch,
        onOpenStarterPack = onOpenStarterPack,
        onOpenBookmarks = {
            if (viewModel.onBookmarksTapped()) onOpenBookmarks()
        },
        onOpenStats = onOpenStats,
        onOpenLibrarySearch = {
            if (viewModel.onLibrarySearchTapped()) onOpenLibrarySearch()
        },
        onOpenSmartPlaylistDetail = { id ->
            if (viewModel.onSmartPlaylistTapped()) onOpenSmartPlaylistDetail(id)
        },
        onNewList = { newListOpen = true },
        onLongPressPodcast = { pendingDeletePodcast = it },
        onLongPressList = { pendingDeleteList = it },
        onLongPressSmartPlaylist = { pendingDeleteSmartPlaylist = it },
        onImportOpml = { viewModel.importOpml() },
        size = LocalTabletSize.current,
    )

    if (newListOpen) {
        NewListDialog(
            onDismiss = { newListOpen = false },
            onCreate = { name, smart ->
                if (smart) {
                    if (viewModel.onCreateSmartPlaylistTapped()) {
                        newListOpen = false
                        onOpenSmartPlaylistEditor(null, name)
                    }
                    // Paywall-blocked: leave the dialog open so the user keeps
                    // their typed name and can retry after upgrading or unchecking.
                } else {
                    viewModel.createList(name)
                    newListOpen = false
                }
            },
        )
    }

    pendingDeletePodcast?.let { p ->
        ConfirmDialog(
            title = "Remove from library?",
            message = "\"${p.title}\" and its episodes will be removed from your library.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.deletePodcast(p.id)
                pendingDeletePodcast = null
            },
            onDismiss = { pendingDeletePodcast = null },
        )
    }

    pendingDeleteList?.let { list ->
        ConfirmDialog(
            title = "Delete list?",
            message = "\"${list.name}\" will be removed. Its podcasts will move to Unfiled.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteList(list.id)
                pendingDeleteList = null
            },
            onDismiss = { pendingDeleteList = null },
        )
    }

    pendingDeleteSmartPlaylist?.let { pl ->
        ConfirmDialog(
            title = "Delete playlist?",
            message = "\"${pl.name}\" will be removed. Episodes themselves are not affected.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteSmartPlaylist(pl.id)
                pendingDeleteSmartPlaylist = null
            },
            onDismiss = { pendingDeleteSmartPlaylist = null },
        )
    }
}

/**
 * Stateless Library body. Phone branch (`size == null`) renders the today's layout
 * unchanged; the `size` parameter is a placeholder for Tasks 2.2 / 2.3 which will
 * branch on tablet portrait / master-detail variants.
 */
@Composable
internal fun LibraryContent(
    state: LibraryUiState,
    onOpenPodcast: (String) -> Unit,
    onOpenList: (String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStarterPack: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenLibrarySearch: () -> Unit,
    onOpenSmartPlaylistDetail: (String) -> Unit,
    onNewList: () -> Unit,
    onLongPressPodcast: (Podcast) -> Unit,
    onLongPressList: (PodcastList) -> Unit,
    onLongPressSmartPlaylist: (SmartPlaylistDomain) -> Unit,
    onImportOpml: () -> Unit,
    size: TabletSize?,
) {
    // Tablet portraits get the new single-column layout (header + folder row +
    // adaptive grid). Phone (size == null) and tablet landscapes (8L / 10L) fall
    // through to today's body — Task 2.3 will swap landscapes for master-detail.
    if (size == TabletSize.Tablet8Port || size == TabletSize.Tablet10Port) {
        LibraryContentTabletSingle(
            state = state,
            onOpenPodcast = onOpenPodcast,
            onOpenList = onOpenList,
            onOpenSearch = onOpenSearch,
            onOpenStarterPack = onOpenStarterPack,
            onOpenBookmarks = onOpenBookmarks,
            onOpenStats = onOpenStats,
            onOpenLibrarySearch = onOpenLibrarySearch,
            onOpenSmartPlaylistDetail = onOpenSmartPlaylistDetail,
            onNewList = onNewList,
            onLongPressPodcast = onLongPressPodcast,
            onLongPressList = onLongPressList,
            onLongPressSmartPlaylist = onLongPressSmartPlaylist,
            onImportOpml = onImportOpml,
            size = size,
        )
        return
    }

    val c = LocalKofipodColors.current

    val lists: List<PodcastList> = state.groups.mapNotNull { it.list }
    val podcasts: List<Podcast> = state.groups.flatMap { it.podcasts }

    val activeListId: String? =
        lists
            .firstOrNull { l -> podcasts.any { it.listId == l.id } }
            ?.id
            ?: lists.firstOrNull()?.id

    val recent: List<Podcast> =
        podcasts
            .sortedByDescending { it.addedAt }
            .take(6)

    // Tile slot descriptor: either a real list, an unfiled bucket, or the "New list" CTA.
    // Lets the grid iterate uniformly without special-casing indices inline.
    // NewList tile appears only before any folder exists — once folders are created the "+"
    // moves to the header to free up grid space.
    val unfiledPodcasts = podcasts.filter { it.listId == null }
    val tiles: List<Tile> =
        buildList {
            lists.forEach { add(Tile.OfList(it)) }
            if (unfiledPodcasts.isNotEmpty()) add(Tile.Unfiled(unfiledPodcasts))
            if (lists.isEmpty()) add(Tile.NewList)
            // Smart Playlists rendered after the list/unfiled tiles so the user's own
            // folder organization stays the primary visual anchor; the "+ New playlist"
            // CTA only shows once any folder or playlist exists, mirroring NewListTile.
            state.smartPlaylists.forEach { add(Tile.SmartPlaylist(it.playlist, it.matchedCount)) }
        }

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
    ) {
        item {
            LibraryHeader(
                showAddButton = lists.isNotEmpty(),
                onNewList = onNewList,
                onOpenStats = onOpenStats,
                statsHasBadge = state.statsHasUnseenTierChange,
                onOpenBookmarks = onOpenBookmarks,
            )
        }

        item {
            LibrarySearchEntry(
                onTap = onOpenLibrarySearch,
            )
        }

        if (lists.isEmpty() && podcasts.isEmpty()) {
            item {
                LibraryEmptyState(
                    onFindPodcast = onOpenSearch,
                    onCreateList = onNewList,
                    onOpenStarterPack = onOpenStarterPack,
                    onImportOpml = onImportOpml,
                )
            }
        } else {
            item { SectionLabel(title = "Your lists", topSpacing = 18.dp) }

            val rows = (tiles.size + 1) / 2
            items(rows) { rowIndex ->
                val left = tiles.getOrNull(rowIndex * 2)
                val right = tiles.getOrNull(rowIndex * 2 + 1)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TileSlot(
                        modifier = Modifier.weight(1f),
                        tile = left,
                        podcasts = podcasts,
                        activeListId = activeListId,
                        groupsWithNew = state.groupsWithNew,
                        onOpenList = onOpenList,
                        onLongPressList = onLongPressList,
                        onCreateList = onNewList,
                        onOpenSmartPlaylistDetail = onOpenSmartPlaylistDetail,
                        onLongPressSmartPlaylist = onLongPressSmartPlaylist,
                    )
                    TileSlot(
                        modifier = Modifier.weight(1f),
                        tile = right,
                        podcasts = podcasts,
                        activeListId = activeListId,
                        groupsWithNew = state.groupsWithNew,
                        onOpenList = onOpenList,
                        onLongPressList = onLongPressList,
                        onCreateList = onNewList,
                        onOpenSmartPlaylistDetail = onOpenSmartPlaylistDetail,
                        onLongPressSmartPlaylist = onLongPressSmartPlaylist,
                    )
                }
            }

            item { SectionLabel(title = "Recently opened", topSpacing = 20.dp) }

            if (recent.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet.",
                        color = c.textMute,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(recent.size) { idx ->
                    val p = recent[idx]
                    RecentRow(
                        podcast = p,
                        episodeCount = placeholderEpisodeCount(p),
                        showDivider = idx < recent.lastIndex,
                        onClick = { onOpenPodcast(p.id) },
                        onLongClick = { onLongPressPodcast(p) },
                    )
                }
            }
        }
    }
}

/**
 * Tablet portrait (8"P / 10"P) layout for the Library: a single LazyVerticalGrid whose
 * top rows span the full width (header, in-library search, optional Folders horizontal
 * scroll) and whose trailing rows render subscriptions as adaptive grid tiles.
 *
 * One outer grid (with `GridItemSpan(maxLineSpan)` spanning items for non-grid sections)
 * avoids nested LazyColumn/LazyVerticalGrid scroll containers.
 */
@Composable
private fun LibraryContentTabletSingle(
    state: LibraryUiState,
    onOpenPodcast: (String) -> Unit,
    onOpenList: (String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStarterPack: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenLibrarySearch: () -> Unit,
    onOpenSmartPlaylistDetail: (String) -> Unit,
    onNewList: () -> Unit,
    onLongPressPodcast: (Podcast) -> Unit,
    onLongPressList: (PodcastList) -> Unit,
    onLongPressSmartPlaylist: (SmartPlaylistDomain) -> Unit,
    onImportOpml: () -> Unit,
    size: TabletSize,
) {
    val c = LocalKofipodColors.current

    val lists: List<PodcastList> = state.groups.mapNotNull { it.list }
    val podcasts: List<Podcast> = state.groups.flatMap { it.podcasts }
    val unfiledPodcasts = podcasts.filter { it.listId == null }
    val isEmpty = lists.isEmpty() && podcasts.isEmpty()

    val cardWidth = if (size == TabletSize.Tablet10Port) 320.dp else 260.dp
    val gridMinTile = cardWidth

    val sortedPodcasts =
        podcasts.sortedByDescending { it.addedAt }

    // Identify which podcasts roll up into a folder/group that has new content. The
    // existing groupsWithNew set is keyed by listId; we surface the same NEW badge on
    // the per-podcast tile by checking whether the podcast's listId is in the set.
    val podcastsWithNew: Set<String> =
        buildSet {
            sortedPodcasts.forEach { p ->
                if (p.listId in state.groupsWithNew || (p.listId == null && null in state.groupsWithNew)) {
                    add(p.id)
                }
            }
        }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = gridMinTile),
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryHeader(
                showAddButton = lists.isNotEmpty(),
                onNewList = onNewList,
                onOpenStats = onOpenStats,
                statsHasBadge = state.statsHasUnseenTierChange,
                onOpenBookmarks = onOpenBookmarks,
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibrarySearchEntry(onTap = onOpenLibrarySearch)
        }

        if (isEmpty) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryEmptyState(
                    onFindPodcast = onOpenSearch,
                    onCreateList = onNewList,
                    onOpenStarterPack = onOpenStarterPack,
                    onImportOpml = onImportOpml,
                )
            }
            return@LazyVerticalGrid
        }

        if (lists.isNotEmpty() || unfiledPodcasts.isNotEmpty() || state.smartPlaylists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(title = "Folders", topSpacing = 18.dp)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(lists.size) { idx ->
                        val list = lists[idx]
                        val members = podcasts.filter { it.listId == list.id }
                        TabletFolderCard(
                            width = cardWidth,
                            title = list.name,
                            subtitle = "${members.size} SHOWS",
                            hasNew = list.id in state.groupsWithNew,
                            onClick = { onOpenList(list.id) },
                            onLongClick = { onLongPressList(list) },
                        )
                    }
                    if (unfiledPodcasts.isNotEmpty()) {
                        item {
                            TabletFolderCard(
                                width = cardWidth,
                                title = "Unfiled",
                                subtitle = "${unfiledPodcasts.size} SHOWS",
                                hasNew = null in state.groupsWithNew,
                                onClick = { onOpenList(null) },
                                onLongClick = null,
                            )
                        }
                    }
                    items(state.smartPlaylists.size) { idx ->
                        val tile = state.smartPlaylists[idx]
                        SmartPlaylistTile(
                            modifier = Modifier.width(cardWidth).height(120.dp),
                            playlist = tile.playlist,
                            matchedCount = tile.matchedCount,
                            onClick = { onOpenSmartPlaylistDetail(tile.playlist.id) },
                            onLongClick = { onLongPressSmartPlaylist(tile.playlist) },
                        )
                    }
                }
            }
        }

        if (sortedPodcasts.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(title = "Subscriptions", topSpacing = 22.dp)
            }
            items(sortedPodcasts.size) { idx ->
                val p = sortedPodcasts[idx]
                SubscriptionGridTile(
                    podcast = p,
                    hasNew = p.id in podcastsWithNew,
                    onClick = { onOpenPodcast(p.id) },
                    onLongClick = { onLongPressPodcast(p) },
                )
            }
        }
    }
}

/**
 * Folder card used in the tablet portrait Folders horizontal-scroll row. Fixed
 * `width` (260 dp on 8"P / 320 dp on 10"P), 120 dp tall, rounded surface with the
 * folder glyph, name, "N SHOWS" subtitle, and an optional NEW dot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletFolderCard(
    width: Dp,
    title: String,
    subtitle: String,
    hasNew: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val clickModifier =
        if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
    Box(
        Modifier
            .width(width)
            .height(120.dp)
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .then(clickModifier)
            .padding(14.dp),
    ) {
        KPIcon(
            name = KPIconName.Folder,
            color = c.purple,
            size = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (hasNew) {
            NewDot(
                ringColor = c.surface,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = c.textMute,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

/**
 * Subscription grid tile rendered inside the tablet `LazyVerticalGrid`. Square-ish
 * surface with artwork, title (up to 2 lines), author (1 line muted), and an
 * optional NEW badge in the top-right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionGridTile(
    podcast: Podcast,
    hasNew: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            KofipodArtwork(
                size = 96.dp,
                seed = podcast.id.hashCode(),
                label = podcast.title,
                radius = 12.dp,
                model = podcast.artworkUrl.ifBlank { null },
                contentDescription = podcast.title,
            )
            if (hasNew) {
                NewDot(
                    ringColor = c.surface,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            podcast.title,
            color = c.text,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (podcast.author.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                podcast.author,
                color = c.textMute,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed interface Tile {
    data class OfList(val list: PodcastList) : Tile

    data class Unfiled(val podcasts: List<Podcast>) : Tile

    data object NewList : Tile

    data class SmartPlaylist(val playlist: SmartPlaylistDomain, val matchedCount: Int) : Tile
}

@Composable
private fun LibrarySearchEntry(onTap: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Search, color = c.textMute, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "Search bookmarks, summaries, transcripts",
            color = c.textMute,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun LibraryHeader(
    showAddButton: Boolean,
    onNewList: () -> Unit,
    onOpenStats: () -> Unit,
    statsHasBadge: Boolean,
    onOpenBookmarks: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Library",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(c.bgSubtle)
                .clickable(onClick = onOpenBookmarks),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(
                name = KPIconName.Bookmark,
                color = c.purple,
                size = 20.dp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(c.bgSubtle)
                    .clickable(onClick = onOpenStats),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = KPIconName.Chart,
                    color = c.purple,
                    size = 20.dp,
                )
            }
            if (statsHasBadge) {
                NewDot(
                    ringColor = c.bg,
                    modifier = Modifier.offset(x = 2.dp, y = (-2).dp),
                )
            }
        }
        if (showAddButton) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(c.pink)
                    .clickable(onClick = onNewList),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = KPIconName.Plus,
                    color = Color.White,
                    size = 20.dp,
                    strokeWidth = 2.4f,
                )
            }
        }
    }
}

@Composable
private fun TileSlot(
    modifier: Modifier,
    tile: Tile?,
    podcasts: List<Podcast>,
    activeListId: String?,
    groupsWithNew: Set<String?>,
    onOpenList: (String?) -> Unit,
    onLongPressList: (PodcastList) -> Unit,
    onCreateList: () -> Unit,
    onOpenSmartPlaylistDetail: (String) -> Unit,
    onLongPressSmartPlaylist: (SmartPlaylistDomain) -> Unit,
) {
    when (tile) {
        is Tile.OfList -> {
            val members = podcasts.filter { it.listId == tile.list.id }
            ListTile(
                modifier = modifier,
                list = tile.list,
                members = members,
                active = tile.list.id == activeListId,
                hasNew = tile.list.id in groupsWithNew,
                seed = tile.list.id.hashCode(),
                onClick = { onOpenList(tile.list.id) },
                onLongClick = { onLongPressList(tile.list) },
            )
        }
        is Tile.Unfiled ->
            UnfiledTile(
                modifier = modifier,
                members = tile.podcasts,
                hasNew = null in groupsWithNew,
                onClick = { onOpenList(null) },
            )
        Tile.NewList -> NewListTile(modifier = modifier, onClick = onCreateList)
        is Tile.SmartPlaylist ->
            SmartPlaylistTile(
                modifier = modifier,
                playlist = tile.playlist,
                matchedCount = tile.matchedCount,
                onClick = { onOpenSmartPlaylistDetail(tile.playlist.id) },
                onLongClick = { onLongPressSmartPlaylist(tile.playlist) },
            )
        null -> Box(modifier = modifier.aspectRatio(1f)) // balances odd-count rows
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListTile(
    modifier: Modifier,
    list: PodcastList,
    members: List<Podcast>,
    active: Boolean,
    hasNew: Boolean,
    seed: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val visuals = rememberTileVisuals(members = members, fallbackSeed = seed)

    val onSampledBg = visuals.sampled && !active
    val textColor =
        when {
            active -> Color.White
            onSampledBg -> Color.White
            else -> c.text
        }
    val subTextColor =
        when {
            active -> Color.White.copy(alpha = 0.72f)
            onSampledBg -> Color.White.copy(alpha = 0.85f)
            else -> c.textMute
        }
    val folderColor =
        when {
            active -> c.pink
            onSampledBg -> Color.White.copy(alpha = 0.9f)
            else -> c.purple
        }

    TileSurface(
        modifier = modifier,
        radius = r.md,
        active = active,
        visuals = visuals,
        clickable = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        KPIcon(
            name = KPIconName.Folder,
            color = folderColor,
            size = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Box(Modifier.align(Alignment.TopEnd)) {
            ListMosaic(
                members = members,
                size = 96.dp,
                seed = seed,
            )
            if (hasNew) {
                NewDot(
                    ringColor = if (active) c.purple else c.surface,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                list.name,
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${members.size} PODCASTS",
                color = subTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

/**
 * Shared chrome for [ListTile] / [UnfiledTile]: square clipped surface that draws the
 * tile-background brush (sampled palette gradient or seeded fallback), an optional
 * bottom scrim for text legibility on busy gradients, and a 12dp content padding.
 * Active tiles always paint solid `c.purple` to keep the "you are here" signal clear.
 */
@Composable
private fun TileSurface(
    modifier: Modifier,
    radius: Dp,
    active: Boolean,
    visuals: com.kofikodr.kofipod.ui.palette.TileVisuals,
    clickable: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(radius))
            .then(if (active) Modifier.background(c.purple) else Modifier.background(visuals.brush))
            .then(clickable)
            .padding(12.dp),
    ) {
        content()
    }
}

/**
 * Variable-shape mosaic of the first up to 4 podcast artworks for a list.
 * 0 → keeps the existing decorative gradient (no real artwork);
 * 1 → single full-bleed cell; 2 → side-by-side; 3 → 2-on-top + 1 wide; 4 → 2×2.
 *
 * Each cell falls back to per-podcast gradient art when [Podcast.artworkUrl] is blank,
 * matching the `RecentRow` convention.
 */
@Composable
private fun ListMosaic(
    members: List<Podcast>,
    size: Dp,
    seed: Int,
) {
    val r = LocalKofipodRadii.current
    val outerRadius = r.sm
    val gap = 2.dp
    val cellRadius = 4.dp

    if (members.isEmpty()) {
        KofipodArtwork(
            size = size,
            seed = seed * 7 + 3,
            label = null,
            radius = outerRadius,
        )
        return
    }

    val take = members.take(4)
    Box(Modifier.size(size).clip(RoundedCornerShape(outerRadius))) {
        when (take.size) {
            1 -> MosaicCell(take[0], Modifier.fillMaxSize(), cellRadius)
            2 ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MosaicCell(take[0], Modifier.weight(1f).fillMaxSize(), cellRadius)
                    MosaicCell(take[1], Modifier.weight(1f).fillMaxSize(), cellRadius)
                }
            3 ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    MosaicRow(listOf(take[0], take[1]), Modifier.weight(1f).fillMaxWidth(), gap, cellRadius)
                    MosaicCell(take[2], Modifier.weight(1f).fillMaxSize(), cellRadius)
                }
            else ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    MosaicRow(listOf(take[0], take[1]), Modifier.weight(1f).fillMaxWidth(), gap, cellRadius)
                    MosaicRow(listOf(take[2], take[3]), Modifier.weight(1f).fillMaxWidth(), gap, cellRadius)
                }
        }
    }
}

@Composable
private fun MosaicRow(
    cells: List<Podcast>,
    modifier: Modifier,
    gap: Dp,
    cellRadius: Dp,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        cells.forEach { MosaicCell(it, Modifier.weight(1f).fillMaxSize(), cellRadius) }
    }
}

@Composable
private fun MosaicCell(
    podcast: Podcast,
    modifier: Modifier,
    radius: Dp,
) {
    KofipodArtwork(
        seed = podcast.id.hashCode(),
        modifier = modifier,
        label = null,
        radius = radius,
        model = podcast.artworkUrl.ifBlank { null },
        contentDescription = podcast.title,
    )
}

@Composable
private fun UnfiledTile(
    modifier: Modifier,
    members: List<Podcast>,
    hasNew: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val seed = UNFILED_SEED
    val visuals = rememberTileVisuals(members = members, fallbackSeed = seed)

    val onSampledBg = visuals.sampled
    val textColor = if (onSampledBg) Color.White else c.text
    val subTextColor = if (onSampledBg) Color.White.copy(alpha = 0.85f) else c.textMute
    val folderColor = if (onSampledBg) Color.White.copy(alpha = 0.9f) else c.textSoft

    TileSurface(
        modifier = modifier,
        radius = r.md,
        active = false,
        visuals = visuals,
        clickable = Modifier.clickable { onClick() },
    ) {
        KPIcon(
            name = KPIconName.Folder,
            color = folderColor,
            size = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Box(Modifier.align(Alignment.TopEnd)) {
            ListMosaic(
                members = members,
                size = 96.dp,
                seed = seed,
            )
            if (hasNew) {
                NewDot(
                    ringColor = c.surface,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                "Unfiled",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${members.size} PODCASTS",
                color = subTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

private val UNFILED_SEED = "unfiled".hashCode()

@Composable
private fun NewListTile(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(r.md))
            .dashedBorder(color = c.borderStrong, cornerRadius = r.md)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KPIcon(
                name = KPIconName.Plus,
                color = c.textSoft,
                size = 26.dp,
                strokeWidth = 2.2f,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "New list",
                color = c.textSoft,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }
}

/**
 * Small pink "new episode" dot. Rendered with a 2dp ring in [ringColor] (the tile's
 * background) so the dot reads clearly when it overlaps artwork or folder chrome.
 */
@Composable
private fun NewDot(
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Box(
        modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(ringColor),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(c.pink),
        )
    }
}

private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.5.dp,
    dashLength: Dp = 6.dp,
    gapLength: Dp = 5.dp,
): Modifier =
    this.drawBehind {
        val strokePx = strokeWidth.toPx()
        val radiusPx = cornerRadius.toPx()
        val stroke =
            Stroke(
                width = strokePx,
                pathEffect =
                    PathEffect.dashPathEffect(
                        floatArrayOf(dashLength.toPx(), gapLength.toPx()),
                        0f,
                    ),
            )
        val inset = strokePx / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = stroke,
        )
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(
    podcast: Podcast,
    episodeCount: Int,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KofipodArtwork(
                size = 44.dp,
                seed = podcast.id.toIntOrNull() ?: podcast.id.hashCode(),
                label = podcast.title,
                radius = 10.dp,
                model = podcast.artworkUrl.ifBlank { null },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (podcast.author.isNotBlank()) {
                    Text(
                        podcast.author,
                        color = c.textMute,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "$episodeCount EPS",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.border),
            )
        }
    }
}

private fun placeholderEpisodeCount(p: Podcast): Int {
    val h = p.id.hashCode()
    val positive = if (h == Int.MIN_VALUE) 0 else kotlin.math.abs(h)
    return (positive % 300) + 20
}

@Composable
private fun LibraryEmptyState(
    onFindPodcast: () -> Unit,
    onCreateList: () -> Unit,
    onOpenStarterPack: () -> Unit,
    onImportOpml: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        EmptyHeroCard(onFindPodcast = onFindPodcast, onCreateList = onCreateList)
        SectionLabel(title = "Three ways to start", topSpacing = 22.dp)
        StartActionRow(
            icon = KPIconName.Search,
            iconBg = EmptyStateIconBg.Pink,
            title = "Search the Podcast Index",
            subtitle = "Over 4M shows. Search by title or by person.",
            onClick = onFindPodcast,
        )
        Spacer(Modifier.height(10.dp))
        StartActionRow(
            icon = KPIconName.Radar,
            iconBg = EmptyStateIconBg.Purple,
            title = "Try a starter pack",
            subtitle = "A curated dozen across tech, history, science, and culture.",
            onClick = onOpenStarterPack,
        )
        Spacer(Modifier.height(10.dp))
        StartActionRow(
            icon = KPIconName.Download,
            iconBg = EmptyStateIconBg.Purple,
            title = "Import from OPML",
            subtitle = "Coming from another app? Drop an .opml export in.",
            onClick = onImportOpml,
        )
    }
}

@Composable
private fun EmptyHeroCard(
    onFindPodcast: () -> Unit,
    onCreateList: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    val heroGradient =
        Brush.linearGradient(
            colors =
                listOf(
                    c.purpleSoft,
                    c.purple,
                    c.purpleDeep,
                ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(r.lg))
                .background(heroGradient)
                .drawBehind { drawHeroAmbience() },
    ) {
        HeroDecoration(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 22.dp, top = 22.dp),
        )
        HeroSparkAndHalo(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 22.dp, top = 22.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 88.dp, bottom = 22.dp),
        ) {
            val heroHeadline =
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White)) {
                        append("A clean shelf.\nLet's fill it with ")
                    }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = c.pink)) {
                        append("good stuff.")
                    }
                }
            Text(
                heroHeadline,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                lineHeight = 30.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Organize podcasts into folders — one \"Walks,\" one \"Work,\" one for that weird documentary rabbit hole.",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(r.pill))
                            .background(Color.White)
                            .clickable { onFindPodcast() }
                            .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KPIcon(name = KPIconName.Search, color = c.purpleDeep, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Find your first podcast",
                            color = c.purpleDeep,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier =
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .clickable { onCreateList() },
                    contentAlignment = Alignment.Center,
                ) {
                    KPIcon(name = KPIconName.Plus, color = Color.White, size = 20.dp, strokeWidth = 2.4f)
                }
            }
        }
    }
}

/**
 * Two overlapping folder tiles at the top-left of the hero — a translucent-white
 * "back" tile offset up-and-left, and a pink front tile with the folder glyph.
 */
@Composable
private fun HeroDecoration(modifier: Modifier = Modifier) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        modifier = modifier.size(width = 96.dp, height = 72.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(54.dp)
                    .align(Alignment.TopStart)
                    .rotate(-10f)
                    .clip(RoundedCornerShape(r.md))
                    .background(Color.White.copy(alpha = 0.24f)),
        )
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .align(Alignment.TopStart)
                    .offset(x = 30.dp, y = 14.dp)
                    .clip(RoundedCornerShape(r.md))
                    .background(c.pink),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Folder, color = Color.White, size = 28.dp, strokeWidth = 2.2f)
        }
    }
}

/**
 * Top-right ornament: a radial halo glow behind a sparkle + a tiny "+" dot.
 * The halo uses the hero's own purpleSoft so it reads as lift, not contrast.
 */
@Composable
private fun HeroSparkAndHalo(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(64.dp)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width * 0.62f
            val cy = size.height * 0.42f
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                Color.White.copy(alpha = 0.45f),
                                Color.White.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                        center = Offset(cx, cy),
                        radius = size.minDimension * 0.55f,
                    ),
                radius = size.minDimension * 0.55f,
                center = Offset(cx, cy),
            )
        }
        Text(
            "✦",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 2.dp),
        )
        Text(
            "+",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).offset(x = 6.dp, y = (-6).dp),
        )
    }
}

/**
 * Bottom-right concentric ring decoration drawn into the card gradient.
 * Uses the card's own bottom-right as the center, so only a quarter of each
 * ring is visible — matches the "sound-wave / radar" motif in the mock.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeroAmbience() {
    val cx = size.width * 0.95f
    val cy = size.height * 0.92f
    val base = size.minDimension * 0.18f
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f.dp.toPx())
    listOf(1.0f, 1.7f, 2.4f, 3.1f).forEachIndexed { i, scale ->
        drawCircle(
            color = Color.White.copy(alpha = 0.22f - i * 0.04f),
            radius = base * scale,
            center = Offset(cx, cy),
            style = stroke,
        )
    }
}

private enum class EmptyStateIconBg { Pink, Purple }

@Composable
private fun StartActionRow(
    icon: KPIconName,
    iconBg: EmptyStateIconBg,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val (bg, fg) =
        when (iconBg) {
            EmptyStateIconBg.Pink -> c.pink to Color.White
            EmptyStateIconBg.Purple -> c.purpleTint to c.purple
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(r.md))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(r.md))
                .clickable { onClick() }
                .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = icon, color = fg, size = 22.dp, strokeWidth = 2.0f)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = c.textMute,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
    }
}

@Composable
private fun NewListDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, smart: Boolean) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    var name by remember { mutableStateOf("") }
    var smart by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.surface)
                .padding(20.dp),
        ) {
            Text("New list", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.sm))
                    .background(c.bgSubtle)
                    .padding(12.dp),
            ) {
                if (name.isEmpty()) Text("List name", color = c.textMute)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(r.sm))
                        .clickable { smart = !smart }
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = smart,
                    onCheckedChange = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Smart Playlist",
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cancel",
                    color = c.textSoft,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(12.dp),
                )
                Spacer(Modifier.weight(1f))
                KPButton(
                    label = "Create",
                    enabled = name.isNotBlank(),
                    onClick = { onCreate(name, smart) },
                )
            }
        }
    }
}
