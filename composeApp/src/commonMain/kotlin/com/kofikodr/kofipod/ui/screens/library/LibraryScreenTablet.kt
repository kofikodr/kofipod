// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.ui.layout.EmptyDetailHint
import com.kofikodr.kofipod.ui.layout.MasterDetailPane
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.primitives.KPButton
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import com.kofikodr.kofipod.playlists.SmartPlaylist as SmartPlaylistDomain

/**
 * Tablet portrait (8"P / 10"P) layout for the Library: a single LazyVerticalGrid whose
 * top rows span the full width (header, in-library search, optional Folders horizontal
 * scroll) and whose trailing rows render subscriptions as adaptive grid tiles.
 *
 * One outer grid (with `GridItemSpan(maxLineSpan)` spanning items for non-grid sections)
 * avoids nested LazyColumn/LazyVerticalGrid scroll containers.
 */
@Composable
internal fun LibraryContentTabletSingle(
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
    // Grid cell min is decoupled from folder-card width: spec mandates the
    // folder cards stay at 260/320 dp, but the subscriptions grid wants 3
    // columns on 10"P (the 320 dp value would resolve to 2 once 14 dp
    // horizontal spacing is applied to a 960 dp content area).
    val gridCellMin = if (size == TabletSize.Tablet10Port) 300.dp else 260.dp

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
        columns = GridCells.Adaptive(minSize = gridCellMin),
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // Tablet always shows the header "+" so the create-list affordance is
            // reachable even when no lists exist yet (mobile compensates for that
            // state with an inline NewListTile in its 2-up grid; tablet's
            // horizontal folder strip is mirrored below via TabletNewListCard).
            LibraryHeader(
                showAddButton = true,
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
                    // Inline "+ New list" card — mirrors mobile's NewListTile contextually
                    // in the Folders strip while no user-defined lists exist. Once any
                    // list is created, the affordance is the header "+" alone (matching
                    // mobile's once-list-exists behavior).
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
 * Tablet landscape (8"L / 10"L) master-detail layout. Master reuses the tablet-portrait
 * single-column body verbatim (so the rail-only chrome and grid sizing already work);
 * tile taps come in pre-routed via [onPodcastTap] (which Task 2.4's
 * `routeLibraryPodcastTap` already mapped to selection for this size). The detail
 * pane's "Open" CTA is a separate gesture and goes through [onOpenPodcastDetail].
 *
 * The detail pane renders [SubscriptionPreviewPane] when a subscription is selected,
 * or [EmptyDetailHint] otherwise. Per plan §2.3 the preview pulls the last
 * [LibraryViewModel.PREVIEW_EPISODE_LIMIT] episodes via the existing
 * `EpisodeSource.episodesFlow` — no new repo methods.
 */
@Composable
internal fun LibraryContentTabletMasterDetail(
    state: LibraryUiState,
    selectedPodcast: Podcast?,
    selectedEpisodes: List<Episode>,
    onPodcastTap: (String) -> Unit,
    onOpenPodcastDetail: (String) -> Unit,
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
    masterSize: TabletSize,
) {
    MasterDetailPane(
        master = {
            LibraryContentTabletSingle(
                state = state,
                onOpenPodcast = onPodcastTap,
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
                size = masterSize,
            )
        },
        detail = {
            // selectedPodcast == null is filtered out by hasSelection upstream, but
            // we still guard the cast so the composable stays total.
            val pc = selectedPodcast
            if (pc != null) {
                SubscriptionPreviewPane(
                    podcast = pc,
                    episodes = selectedEpisodes,
                    onOpen = { onOpenPodcastDetail(pc.id) },
                )
            }
        },
        hasSelection = selectedPodcast != null,
        emptyDetail = { EmptyDetailHint(text = "Pick a subscription to preview") },
    )
}

/**
 * Right-pane preview for a selected subscription. Renders a small podcast header,
 * a "Latest" subhead, the last N episodes as compact rows, and an "Open" CTA at the
 * bottom that navigates to the full podcast detail screen.
 */
@Composable
internal fun SubscriptionPreviewPane(
    podcast: Podcast,
    episodes: List<Episode>,
    onOpen: () -> Unit,
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
                size = 64.dp,
                seed = podcast.id.hashCode(),
                label = podcast.title,
                radius = 12.dp,
                model = podcast.artworkUrl.ifBlank { null },
                contentDescription = podcast.title,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (podcast.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        podcast.author,
                        color = c.textMute,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SectionLabel(title = "Latest", topSpacing = 22.dp)
        if (episodes.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No episodes yet.",
                color = c.textMute,
                fontSize = 13.sp,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            ) {
                items(episodes.size) { idx ->
                    val ep = episodes[idx]
                    PreviewEpisodeRow(
                        episode = ep,
                        showDivider = idx < episodes.lastIndex,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        KPButton(
            label = "Open",
            onClick = onOpen,
        )
    }
}

@Composable
internal fun PreviewEpisodeRow(
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
                Text(
                    formatDurationMinutes(episode.durationSec),
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

internal fun formatDurationMinutes(durationSec: Long): String {
    val minutes = (durationSec / 60).coerceAtLeast(0)
    return "$minutes MIN"
}

/**
 * Folder card used in the tablet portrait Folders horizontal-scroll row. Fixed
 * `width` (260 dp on 8"P / 320 dp on 10"P), 120 dp tall, rounded surface with the
 * folder glyph, name, "N SHOWS" subtitle, and an optional NEW dot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabletFolderCard(
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
 * "+ New list" card rendered inline in the tablet Folders horizontal strip while no
 * user-defined lists exist yet. Matches [TabletFolderCard]'s width/height so the
 * strip's rhythm is preserved; uses a dashed border + Plus glyph styled like mobile's
 * `NewListTile`.
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

/**
 * Subscription grid tile rendered inside the tablet `LazyVerticalGrid`. Square-ish
 * surface with artwork, title (up to 2 lines), author (1 line muted), and an
 * optional NEW badge in the top-right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubscriptionGridTile(
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
