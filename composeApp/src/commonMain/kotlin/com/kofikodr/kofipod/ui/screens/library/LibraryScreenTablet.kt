// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.ui.layout.MasterDetailPane
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.palette.rememberTileVisuals
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import com.kofikodr.kofipod.playlists.SmartPlaylist as SmartPlaylistDomain

private const val RECENT_LIMIT = 6

/**
 * Tablet portrait (8"P / 10"P) single-column body. Mirrors phone IA: Header,
 * library search entry, Folders horizontal strip, then "Recently opened". The
 * Subscriptions flat grid that previously lived here was dropped — discover-by-
 * folder + recency is the only IA across all form factors now.
 *
 * Re-used as the master pane for landscape master-detail with [showRecentlyOpened]
 * = false, since landscape moves Recently opened into the detail pane.
 */
@Composable
internal fun LibraryContentTabletSingle(
    state: LibraryUiState,
    onOpenPodcast: (String) -> Unit,
    onOpenList: (String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStarterPack: () -> Unit,
    onOpenLibrarySearch: () -> Unit,
    onOpenSmartPlaylistDetail: (String) -> Unit,
    onNewList: () -> Unit,
    onLongPressPodcast: (Podcast) -> Unit,
    onLongPressList: (PodcastList) -> Unit,
    onLongPressSmartPlaylist: (SmartPlaylistDomain) -> Unit,
    onImportOpml: () -> Unit,
    size: TabletSize,
    showRecentlyOpened: Boolean = true,
) {
    val c = LocalKofipodColors.current

    val lists: List<PodcastList> = state.groups.mapNotNull { it.list }
    val podcasts: List<Podcast> = state.groups.flatMap { it.podcasts }
    val unfiledPodcasts = podcasts.filter { it.listId == null }
    val isEmpty = lists.isEmpty() && podcasts.isEmpty()

    val cardWidth = if (size == TabletSize.Tablet10Port) 320.dp else 260.dp
    val gridCellMin = if (size == TabletSize.Tablet10Port) 300.dp else 260.dp

    // Only sort/take when the Recently opened section is actually about to render;
    // master-detail callers pass `showRecentlyOpened = false` and would otherwise
    // pay for the derivation on every recomposition while not using the result.
    val recent: List<Podcast> =
        if (showRecentlyOpened) {
            podcasts.sortedByDescending { it.addedAt }.take(RECENT_LIMIT)
        } else {
            emptyList()
        }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = gridCellMin),
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // Tablet always shows the header "+" so the create-list affordance is
            // reachable even when no lists exist yet (phone compensates with an
            // inline NewListTile in its 2-up grid; tablet's horizontal folder
            // strip is mirrored below via TabletNewListCard).
            LibraryHeader(
                showAddButton = true,
                onNewList = onNewList,
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
                            members = members,
                            seed = list.id.hashCode(),
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
                                members = unfiledPodcasts,
                                seed = UNFILED_SEED,
                                hasNew = null in state.groupsWithNew,
                                onClick = { onOpenList(null) },
                                onLongClick = null,
                            )
                        }
                    }
                    // Inline "+ New list" card — mirrors phone's NewListTile while
                    // no user-defined lists exist. Once any list is created, the
                    // affordance is the header "+" alone.
                    if (lists.isEmpty()) {
                        item {
                            TabletNewListCard(
                                width = cardWidth,
                                onClick = onNewList,
                            )
                        }
                    }
                    items(state.smartPlaylists.size) { idx ->
                        val tile = state.smartPlaylists[idx]
                        SmartPlaylistFolderCard(
                            width = cardWidth,
                            playlist = tile.playlist,
                            matchedCount = tile.matchedCount,
                            onClick = { onOpenSmartPlaylistDetail(tile.playlist.id) },
                            onLongClick = { onLongPressSmartPlaylist(tile.playlist) },
                        )
                    }
                }
            }
        }

        if (showRecentlyOpened) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(title = "Recently opened", topSpacing = 22.dp)
            }
            if (recent.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Nothing here yet.",
                        color = c.textMute,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(
                    count = recent.size,
                    span = { GridItemSpan(maxLineSpan) },
                ) { idx ->
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
 * Tablet landscape (8"L / 10"L) master-detail body. Master pane mirrors the tablet
 * portrait body minus its "Recently opened" section; the detail pane renders the
 * Recently opened list instead. When the library has no recently-opened podcasts
 * (i.e. the library itself is empty), the detail pane is hidden and the master
 * takes the full width.
 *
 * Tile taps in the master always navigate (no preview-first selection on this
 * screen anymore); tile taps in the detail's Recently opened list also navigate
 * to the podcast detail route.
 */
@Composable
internal fun LibraryContentTabletMasterDetail(
    state: LibraryUiState,
    onOpenPodcast: (String) -> Unit,
    onOpenList: (String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStarterPack: () -> Unit,
    onOpenLibrarySearch: () -> Unit,
    onOpenSmartPlaylistDetail: (String) -> Unit,
    onNewList: () -> Unit,
    onLongPressPodcast: (Podcast) -> Unit,
    onLongPressList: (PodcastList) -> Unit,
    onLongPressSmartPlaylist: (SmartPlaylistDomain) -> Unit,
    onImportOpml: () -> Unit,
    masterSize: TabletSize,
) {
    val recent: List<Podcast> =
        state.groups
            .flatMap { it.podcasts }
            .sortedByDescending { it.addedAt }
            .take(RECENT_LIMIT)

    if (recent.isEmpty()) {
        // No detail content → drop the split pane so the master gets full width
        // (matters most when the library is empty and the empty-state card needs
        // breathing room).
        LibraryContentTabletSingle(
            state = state,
            onOpenPodcast = onOpenPodcast,
            onOpenList = onOpenList,
            onOpenSearch = onOpenSearch,
            onOpenStarterPack = onOpenStarterPack,
            onOpenLibrarySearch = onOpenLibrarySearch,
            onOpenSmartPlaylistDetail = onOpenSmartPlaylistDetail,
            onNewList = onNewList,
            onLongPressPodcast = onLongPressPodcast,
            onLongPressList = onLongPressList,
            onLongPressSmartPlaylist = onLongPressSmartPlaylist,
            onImportOpml = onImportOpml,
            size = masterSize,
            showRecentlyOpened = false,
        )
        return
    }

    MasterDetailPane(
        master = {
            LibraryContentTabletSingle(
                state = state,
                onOpenPodcast = onOpenPodcast,
                onOpenList = onOpenList,
                onOpenSearch = onOpenSearch,
                onOpenStarterPack = onOpenStarterPack,
                onOpenLibrarySearch = onOpenLibrarySearch,
                onOpenSmartPlaylistDetail = onOpenSmartPlaylistDetail,
                onNewList = onNewList,
                onLongPressPodcast = onLongPressPodcast,
                onLongPressList = onLongPressList,
                onLongPressSmartPlaylist = onLongPressSmartPlaylist,
                onImportOpml = onImportOpml,
                size = masterSize,
                showRecentlyOpened = false,
            )
        },
        detail = {
            TabletRecentlyOpenedPane(
                recent = recent,
                onOpenPodcast = onOpenPodcast,
                onLongPressPodcast = onLongPressPodcast,
            )
        },
        // We've already filtered out the empty-recent case above, so the detail
        // pane is always non-empty by the time we reach this branch.
        hasSelection = true,
    )
}

/**
 * Right-pane "Recently opened" list for tablet landscape. Reuses [RecentRow] from
 * the phone body so a row tap navigates straight to the podcast detail route.
 */
@Composable
internal fun TabletRecentlyOpenedPane(
    recent: List<Podcast>,
    onOpenPodcast: (String) -> Unit,
    onLongPressPodcast: (Podcast) -> Unit,
) {
    val c = LocalKofipodColors.current
    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    ) {
        item {
            SectionLabel(title = "Recently opened", topSpacing = 0.dp)
        }
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

/**
 * Folder card used in the tablet Folders horizontal-scroll row. Fixed [width]
 * (260 dp on 8"P / 320 dp on 10"P), 120 dp tall. Brings the phone tile look-and-feel
 * into the strip: sampled-palette background derived from [members]' artwork, a
 * mosaic of up to 4 thumbnails on the right, folder glyph + title + "N SHOWS" on
 * the left, and an optional NEW dot.
 *
 * Falls back to a seeded gradient + muted icon when no member has artwork yet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabletFolderCard(
    width: Dp,
    title: String,
    members: List<Podcast>,
    seed: Int,
    hasNew: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val visuals = rememberTileVisuals(members = members, fallbackSeed = seed)

    val onSampledBg = visuals.sampled
    val textColor = if (onSampledBg) Color.White else c.text
    val subTextColor = if (onSampledBg) Color.White.copy(alpha = 0.85f) else c.textMute
    val folderColor = if (onSampledBg) Color.White.copy(alpha = 0.9f) else c.purple

    val clickModifier =
        if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
    Row(
        Modifier
            .width(width)
            .height(120.dp)
            .clip(RoundedCornerShape(r.md))
            .background(visuals.brush)
            .then(clickModifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            KPIcon(
                name = KPIconName.Folder,
                color = folderColor,
                size = 22.dp,
            )
            Column {
                Text(
                    title,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${members.size} SHOWS",
                    color = subTextColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.fillMaxHeight()) {
            ListMosaic(
                members = members,
                size = 92.dp,
                seed = seed,
            )
            if (hasNew) {
                NewDot(
                    ringColor = if (onSampledBg) Color.White else c.surface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * "+ New list" card rendered inline in the tablet Folders horizontal strip while
 * no user-defined lists exist. Matches [TabletFolderCard]'s width/height so the
 * strip's rhythm is preserved.
 */
@Composable
internal fun TabletNewListCard(
    width: Dp,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .width(width)
            .height(120.dp)
            .clip(RoundedCornerShape(r.md))
            .dashedBorder(color = c.borderStrong, cornerRadius = r.md)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KPIcon(
                name = KPIconName.Plus,
                color = c.textSoft,
                size = 24.dp,
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
