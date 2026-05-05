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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.bookmarks.Bookmark
import app.kofipod.bookmarks.formatBookmarkTimestamp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors

/**
 * Per-episode Saved section. Sibling to the tab content area, not a fifth tab —
 * the tab strip stays four max per project convention. Tap a row to seek/play
 * at the bookmark's timestamp; long-press to delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SavedSection(
    bookmarks: List<Bookmark>,
    onTap: (Long) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (bookmarks.isEmpty()) return
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Saved")
        Spacer(Modifier.height(8.dp))
        bookmarks.forEachIndexed { idx, b ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { onTap(b.timestampMs) },
                            onLongClick = { onDelete(b.id) },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KPIcon(name = KPIconName.Bookmark, color = c.purple, size = 18.dp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier) {
                    Text(
                        formatBookmarkTimestamp(b.timestampMs),
                        color = c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    if (!b.note.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            b.note,
                            color = c.textMute,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
