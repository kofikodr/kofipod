// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.bookmarks.Bookmark
import app.kofipod.bookmarks.formatBookmarkTimestamp
import app.kofipod.snippets.Snippet
import app.kofipod.snippets.SnippetSizeEstimator
import app.kofipod.snippets.SnippetWindow
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors

/**
 * Per-episode Saved section. Sibling to the tab content area, not a fifth tab —
 * the tab strip stays four max per project convention. Tap a bookmark row to
 * seek/play at the bookmark's timestamp; long-press a bookmark or snippet row
 * to open the Pro-gated markdown export sheet. Tap a snippet row to open the
 * snippet editor. Bookmark deletion lives on the global Bookmarks screen.
 */
@Composable
internal fun SavedSection(
    items: List<SavedItem>,
    onBookmarkTap: (Long) -> Unit,
    onSnippetTap: (String) -> Unit,
    onBookmarkLongPress: (Bookmark) -> Unit,
    onSnippetLongPress: (Snippet) -> Unit,
) {
    if (items.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Saved")
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { idx, item ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            when (item) {
                is SavedItem.BookmarkItem ->
                    BookmarkRow(
                        bookmark = item.bookmark,
                        onTap = onBookmarkTap,
                        onLongPress = onBookmarkLongPress,
                    )
                is SavedItem.SnippetItem ->
                    SnippetRow(
                        snippet = item.snippet,
                        sizeBytes = item.sizeBytes,
                        onTap = onSnippetTap,
                        onLongPress = onSnippetLongPress,
                    )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onTap: (Long) -> Unit,
    onLongPress: (Bookmark) -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { onTap(bookmark.timestampMs) },
                    onLongClick = { onLongPress(bookmark) },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Bookmark, color = c.purple, size = 18.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier) {
            Text(
                formatBookmarkTimestamp(bookmark.timestampMs),
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            if (!bookmark.note.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    bookmark.note,
                    color = c.textMute,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnippetRow(
    snippet: Snippet,
    sizeBytes: Long,
    onTap: (String) -> Unit,
    onLongPress: (Snippet) -> Unit,
) {
    val c = LocalKofipodColors.current
    val window =
        SnippetWindow.formatTimestampDeci(snippet.startMs) +
            "–" +
            SnippetWindow.formatTimestampDeci(snippet.endMs)
    val displayTitle =
        snippet.title?.takeIf { it.isNotBlank() } ?: "Snippet · $window"

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { onTap(snippet.id) },
                    onLongClick = { onLongPress(snippet) },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Scissors, color = c.pink, size = 18.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayTitle,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                window,
                color = c.textMute,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        val format = snippet.lastExportFormat
        if (format != null && sizeBytes > 0L) {
            Spacer(Modifier.size(8.dp))
            FormatSizeChip(formatName = format.name, sizeBytes = sizeBytes)
        }
    }
}

@Composable
private fun FormatSizeChip(
    formatName: String,
    sizeBytes: Long,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, c.pink, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$formatName · ${SnippetSizeEstimator.formatBytes(sizeBytes)}",
            color = c.pink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
