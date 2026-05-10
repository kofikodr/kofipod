// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.bookmarks.formatBookmarkTimestamp
import app.kofipod.search.LibrarySearchResult
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
internal fun LibrarySearchRow(
    result: LibrarySearchResult,
    onTap: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                result.podcastTitle,
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                kindLabel(result),
                color = kindAccent(result, c.purple),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            result.episodeTitle,
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (result is LibrarySearchResult.BookmarkMatch) {
            Spacer(Modifier.height(6.dp))
            Text(
                formatBookmarkTimestamp(result.timestampMs),
                color = c.purple,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            renderExcerpt(result.excerpt),
            color = c.text,
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun kindLabel(result: LibrarySearchResult): String =
    when (result) {
        is LibrarySearchResult.BookmarkMatch -> "BOOKMARK"
        is LibrarySearchResult.SummaryMatch -> "SUMMARY"
        is LibrarySearchResult.TranscriptMatch -> "TRANSCRIPT"
    }

// All kinds currently use the same accent color. A future slice may differentiate
// (e.g. amber for Bookmarks, teal for Transcripts). The `result` parameter is
// intentionally unused until then.
@Suppress("UnusedParameter")
private fun kindAccent(
    result: LibrarySearchResult,
    fallback: Color,
): Color = fallback

private fun renderExcerpt(excerpt: String) =
    buildAnnotatedString {
        var i = 0
        while (i < excerpt.length) {
            val open = excerpt.indexOf("<<", startIndex = i)
            if (open < 0) {
                append(excerpt.substring(i))
                break
            }
            append(excerpt.substring(i, open))
            val close = excerpt.indexOf(">>", startIndex = open + 2)
            if (close < 0) {
                // Unmatched marker — append the rest verbatim.
                append(excerpt.substring(open))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(excerpt.substring(open + 2, close))
            }
            i = close + 2
        }
    }
