// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.bookmarks.Bookmark
import com.kofikodr.kofipod.bookmarks.formatBookmarkTimestamp
import com.kofikodr.kofipod.snippets.Snippet
import com.kofikodr.kofipod.snippets.SnippetSizeEstimator
import com.kofikodr.kofipod.snippets.SnippetWindow
import com.kofikodr.kofipod.ui.primitives.KPBadge
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Per-episode "Saved" section. Bookmarks (Slice 1) and snippets (Slice 3) coexist in
 * a single grouped card with a header, count badge, and per-row affordances:
 *
 *  - Tap a row: seek/play (bookmark) or open the editor (snippet).
 *  - Tap the trailing share icon: open the Pro-gated markdown export sheet.
 *  - Long-press a row: delete (caller is responsible for confirmation).
 *
 * Visual matches `docs/kofipod-pro-ui-design.html` — icon tile + colored mono
 * timestamp + title (+ optional subtitle for snippets) + trailing share.
 */
@Composable
internal fun SavedSection(
    items: List<SavedItem>,
    onBookmarkTap: (Long) -> Unit,
    onSnippetTap: (String) -> Unit,
    onBookmarkExport: (Bookmark) -> Unit,
    onSnippetExport: (Snippet) -> Unit,
    onBookmarkDelete: (Bookmark) -> Unit,
    onSnippetDelete: (Snippet) -> Unit,
) {
    if (items.isEmpty()) return
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(
            title = "Saved on this episode",
            trailing = { KPBadge("${items.size} ${if (items.size == 1) "item" else "items"}") },
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(16.dp)),
        ) {
            items.forEachIndexed { idx, item ->
                if (idx > 0) {
                    HorizontalDivider(color = c.border, thickness = 1.dp)
                }
                when (item) {
                    is SavedItem.BookmarkItem ->
                        BookmarkRow(
                            bookmark = item.bookmark,
                            onTap = onBookmarkTap,
                            onExport = onBookmarkExport,
                            onLongPress = onBookmarkDelete,
                        )
                    is SavedItem.SnippetItem ->
                        SnippetRow(
                            snippet = item.snippet,
                            sizeBytes = item.sizeBytes,
                            onTap = onSnippetTap,
                            onExport = onSnippetExport,
                            onLongPress = onSnippetDelete,
                        )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onTap: (Long) -> Unit,
    onExport: (Bookmark) -> Unit,
    onLongPress: (Bookmark) -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onTap(bookmark.timestampMs) },
                    onLongClick = { onLongPress(bookmark) },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            name = KPIconName.Bookmark,
            tint = c.purple,
            background = c.purpleTint,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatBookmarkTimestamp(bookmark.timestampMs),
            color = c.purple,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            val note = bookmark.note?.takeIf { it.isNotBlank() }
            if (note != null) {
                Text(
                    text = note,
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "Bookmark · no note yet",
                    color = c.textMute,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ShareButton(onClick = { onExport(bookmark) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnippetRow(
    snippet: Snippet,
    sizeBytes: Long,
    onTap: (String) -> Unit,
    onExport: (Snippet) -> Unit,
    onLongPress: (Snippet) -> Unit,
) {
    val c = LocalKofipodColors.current
    val window =
        SnippetWindow.formatTimestampDeci(snippet.startMs) +
            "–" +
            SnippetWindow.formatTimestampDeci(snippet.endMs)
    val displayTitle =
        snippet.title?.takeIf { it.isNotBlank() } ?: "Snippet · $window"
    val format = snippet.lastExportFormat
    val subtitle =
        when {
            format != null && sizeBytes > 0L ->
                "${format.name} · ${SnippetSizeEstimator.formatBytes(sizeBytes)}"
            format != null -> format.name
            else -> window
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onTap(snippet.id) },
                    onLongClick = { onLongPress(snippet) },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            name = KPIconName.Scissors,
            tint = c.pink,
            background = c.pinkSoft,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatBookmarkTimestamp(snippet.startMs),
            color = c.pink,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                color = c.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ShareButton(onClick = { onExport(snippet) })
    }
}

@Composable
private fun IconTile(
    name: KPIconName,
    tint: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = name, color = tint, size = 18.dp)
    }
}

@Composable
private fun ShareButton(onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = KPIconName.Share, color = c.textMute, size = 18.dp)
    }
}
