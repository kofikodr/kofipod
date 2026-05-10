// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.bookmarks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.bookmarks.BookmarkWithContext
import app.kofipod.bookmarks.formatBookmarkTimestamp
import app.kofipod.ui.primitives.KPBadge
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BookmarksScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: BookmarksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        BookmarksTopBar(
            onBack = onBack,
            totalSaved = state.totalSaved,
            episodeCount = state.episodeCount,
        )
        Spacer(Modifier.height(8.dp))
        SearchField(query = state.query, onChange = viewModel::setQuery)
        Spacer(Modifier.height(12.dp))
        FilterRow(
            filters = state.podcastFilters,
            selectedPodcastId = state.selectedPodcastId,
            sort = state.sort,
            onSelectPodcast = viewModel::selectPodcast,
            onToggleSort = viewModel::toggleSort,
        )
        Spacer(Modifier.height(8.dp))

        if (state.rows.isEmpty()) {
            EmptyState(filtered = state.query.isNotBlank() || state.selectedPodcastId != null)
            return@Column
        }

        if (state.query.isNotBlank()) {
            Text(
                text = "${state.rows.size} ${if (state.rows.size == 1) "MATCH" else "MATCHES"}",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val grouped = groupForDisplay(state.rows)
            items(grouped, key = { it.key }) { item ->
                when (item) {
                    is BookmarkListItem.PodcastHeader -> PodcastGroupHeader(item)
                    is BookmarkListItem.EpisodeGroup ->
                        EpisodeBookmarkCard(
                            header = item.header,
                            rows = item.rows,
                            query = state.query,
                            onRowTap = { row ->
                                viewModel.openAt(row)
                                onOpenPlayer()
                            },
                            onRowLongPress = { row -> pendingDeleteId = row.bookmark.id },
                        )
                }
            }
        }
    }
    pendingDeleteId?.let { id ->
        DeleteBookmarkDialog(
            onConfirm = {
                viewModel.delete(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

@Composable
private fun DeleteBookmarkDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalKofipodColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Delete bookmark?", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "This timestamp and any note will be removed from this device.",
                color = c.textMute,
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            Text(
                "Delete",
                color = c.pink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onConfirm() }.padding(8.dp),
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                color = c.textMute,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp),
            )
        },
    )
}

@Composable
private fun BookmarksTopBar(
    onBack: () -> Unit,
    totalSaved: Int,
    episodeCount: Int,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Bookmarks",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
            )
            Text(
                buildSubtitle(totalSaved, episodeCount),
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        KPBadge("PRO")
        Spacer(Modifier.width(8.dp))
    }
}

private fun buildSubtitle(
    totalSaved: Int,
    episodeCount: Int,
): String =
    if (episodeCount == 0) {
        "$totalSaved SAVED"
    } else {
        "$totalSaved SAVED · $episodeCount ${if (episodeCount == 1) "EPISODE" else "EPISODES"}"
    }

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(CircleShape)
            .background(c.surfaceAlt)
            .border(1.dp, c.border, CircleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Search, color = c.purple, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search bookmarks & notes", color = c.textMute, fontSize = 14.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        // Three-bar filter glyph; tappable in a future slice when filter
        // sheet ships. Intentionally non-interactive for now to avoid a dead
        // affordance (the chip row below is the live filter surface).
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            KPIcon(name = KPIconName.Settings, color = c.textMute, size = 18.dp)
        }
    }
}

@Composable
private fun FilterRow(
    filters: List<BookmarkPodcastFilter>,
    selectedPodcastId: String?,
    sort: BookmarkSort,
    onSelectPodcast: (String?) -> Unit,
    onToggleSort: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                label = "All",
                active = selectedPodcastId == null,
                onTap = { onSelectPodcast(null) },
            )
            for (f in filters) {
                PodcastFilterChip(
                    filter = f,
                    active = selectedPodcastId == f.podcastId,
                    onTap = { onSelectPodcast(f.podcastId) },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.clickable(onClick = onToggleSort),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (sort == BookmarkSort.Newest) "Newest" else "Oldest",
                color = c.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            KPIcon(name = KPIconName.ChevronDown, color = c.text, size = 14.dp)
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    onTap: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (active) c.purple else Color.Transparent)
            .border(1.dp, if (active) c.purple else c.border, CircleShape)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (active) Color.White else c.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PodcastFilterChip(
    filter: BookmarkPodcastFilter,
    active: Boolean,
    onTap: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (active) c.purple else Color.Transparent)
            .border(1.dp, if (active) c.purple else c.border, CircleShape)
            .clickable(onClick = onTap)
            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 22.dp,
            seed = filter.seed,
            label = filter.title,
            radius = 6.dp,
            model = filter.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            filter.title.ifBlank { "Untitled" },
            color = if (active) Color.White else c.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PodcastGroupHeader(header: BookmarkListItem.PodcastHeader) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 28.dp,
            seed = header.seed,
            label = header.title,
            radius = 8.dp,
            model = header.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            header.title.ifBlank { "Untitled" },
            color = c.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(c.border),
        )
    }
}

@Composable
private fun EpisodeBookmarkCard(
    header: BookmarkListItem.EpisodeHeader,
    rows: List<BookmarkWithContext>,
    query: String,
    onRowTap: (BookmarkWithContext) -> Unit,
    onRowLongPress: (BookmarkWithContext) -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                header.title.ifBlank { "Untitled episode" },
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                header.dateLabel,
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (row in rows) {
                BookmarkRow(
                    row = row,
                    query = query,
                    onTap = { onRowTap(row) },
                    onLongPress = { onRowLongPress(row) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    row: BookmarkWithContext,
    query: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.purpleTint)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                formatBookmarkTimestamp(row.bookmark.timestampMs),
                color = c.purple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val noteText = row.bookmark.note?.takeIf { it.isNotBlank() }
            if (noteText != null) {
                HighlightedText(
                    text = noteText,
                    needle = query,
                    baseColor = c.text,
                    highlightBg = c.pinkSoft,
                    highlightFg = c.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    italic = false,
                )
            } else {
                Text(
                    "No note · tap to play from here",
                    color = c.textMute,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(c.purple)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Play, color = Color.White, size = 16.dp)
        }
    }
}

@Composable
private fun HighlightedText(
    text: String,
    needle: String,
    baseColor: Color,
    highlightBg: Color,
    highlightFg: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    italic: Boolean,
) {
    val needleTrim = needle.trim()
    if (needleTrim.isEmpty()) {
        Text(
            text,
            color = baseColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val annotated =
        androidx.compose.ui.text.buildAnnotatedString {
            val lower = text.lowercase()
            val n = needleTrim.lowercase()
            var i = 0
            while (i < text.length) {
                val match = lower.indexOf(n, startIndex = i)
                if (match < 0) {
                    append(text.substring(i))
                    break
                }
                if (match > i) append(text.substring(i, match))
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        background = highlightBg,
                        color = highlightFg,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                append(text.substring(match, match + needleTrim.length))
                pop()
                i = match + needleTrim.length
            }
        }
    Text(
        annotated,
        color = baseColor,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptyState(filtered: Boolean) {
    val c = LocalKofipodColors.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(c.purpleTint),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Bookmark, color = c.purple, size = 56.dp, strokeWidth = 1.6f)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(c.pink),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Plus, color = Color.White, size = 18.dp, strokeWidth = 2.4f)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (filtered) "No matches" else "Mark moments worth keeping",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (filtered) {
                "Try a different search or clear the filter."
            } else {
                "Tap the bookmark in the player to save a timestamp — add a note now or later."
            },
            color = c.textMute,
            fontSize = 14.sp,
            modifier = Modifier.heightIn(min = 0.dp),
        )
    }
}

// --- Grouping ---------------------------------------------------------------

private sealed interface BookmarkListItem {
    val key: String

    data class PodcastHeader(
        val podcastId: String,
        val title: String,
        val artworkUrl: String,
        val seed: Int,
    ) : BookmarkListItem {
        override val key = "podcast::$podcastId"
    }

    data class EpisodeHeader(
        val episodeId: String,
        val title: String,
        val dateLabel: String,
    )

    data class EpisodeGroup(
        val header: EpisodeHeader,
        val rows: List<BookmarkWithContext>,
    ) : BookmarkListItem {
        override val key = "episode::${header.episodeId}"
    }
}

private fun groupForDisplay(rows: List<BookmarkWithContext>): List<BookmarkListItem> {
    if (rows.isEmpty()) return emptyList()
    // Pre-group by podcast then by episode using insertion-ordered maps so that
    // every podcast/episode appears exactly once even when the upstream sort
    // (e.g. Newest by createdAtMs) interleaves podcasts. Without this, repeated
    // podcasts produce duplicate `LazyColumn` keys and crash with
    // `IllegalArgumentException: Key … was already used`.
    val byPodcast = LinkedHashMap<String, MutableList<BookmarkWithContext>>()
    for (row in rows) {
        byPodcast.getOrPut(row.bookmark.podcastId) { mutableListOf() }.add(row)
    }
    val out = ArrayList<BookmarkListItem>(rows.size + rows.size / 2)
    for ((pid, podcastRows) in byPodcast) {
        val first = podcastRows.first()
        out.add(
            BookmarkListItem.PodcastHeader(
                podcastId = pid,
                title = first.podcastTitle,
                artworkUrl = first.artworkUrl,
                seed = pid.hashCode(),
            ),
        )
        val byEpisode = LinkedHashMap<String, MutableList<BookmarkWithContext>>()
        for (row in podcastRows) {
            byEpisode.getOrPut(row.bookmark.episodeId) { mutableListOf() }.add(row)
        }
        for ((eid, episodeRows) in byEpisode) {
            val firstEp = episodeRows.first()
            out.add(
                BookmarkListItem.EpisodeGroup(
                    header =
                        BookmarkListItem.EpisodeHeader(
                            episodeId = eid,
                            title = firstEp.episodeTitle,
                            // Bookmark-creation date, not episode publish date —
                            // BookmarkWithContext doesn't carry pubDate yet.
                            dateLabel = formatShortDate(firstEp.bookmark.createdAtMs),
                        ),
                    rows = episodeRows.toList(),
                ),
            )
        }
    }
    return out
}

private fun formatShortDate(ms: Long): String {
    if (ms <= 0L) return ""
    val ldt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = MONTH_LABELS[(ldt.monthNumber - 1).coerceIn(0, 11)]
    val day = ldt.dayOfMonth.toString().padStart(2, '0')
    return "$month $day"
}

private val MONTH_LABELS =
    arrayOf(
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
    )
