// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.search.LibrarySearchKind
import app.kofipod.search.LibrarySearchResult
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LibrarySearchScreen(
    onBack: () -> Unit,
    onOpenEpisode: (episodeId: String) -> Unit,
    onSeekBookmark: (episodeId: String, timestampMs: Long) -> Unit,
    viewModel: LibrarySearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(onBack = onBack)
        SearchField(query = state.query, onChange = viewModel::onQueryChanged)
        Spacer(Modifier.height(8.dp))
        KindChips(active = state.activeKind, onTap = viewModel::onKindChipTapped)
        Spacer(Modifier.height(8.dp))

        when {
            state.query.isBlank() -> EmptyHint("Search bookmarks, summaries, transcripts")
            state.results.isEmpty() -> EmptyHint("No matches for \"${state.query}\"")
            else ->
                ResultsList(
                    results = state.results,
                    onTap = { result ->
                        when (result) {
                            is LibrarySearchResult.BookmarkMatch ->
                                onSeekBookmark(result.episodeId, result.timestampMs)
                            is LibrarySearchResult.SummaryMatch,
                            is LibrarySearchResult.TranscriptMatch,
                            -> onOpenEpisode(result.episodeId)
                        }
                    },
                )
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text("Search library", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
    }
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
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Search, color = c.textMute, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.fillMaxWidth()) {
            if (query.isEmpty()) {
                Text("Search…", color = c.textMute, fontSize = 14.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun KindChips(
    active: LibrarySearchKind?,
    onTap: (LibrarySearchKind?) -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("All", isActive = active == null, onTap = { onTap(null) })
        Chip(
            "Bookmarks",
            isActive = active == LibrarySearchKind.Bookmark,
            onTap = { onTap(LibrarySearchKind.Bookmark) },
        )
        Chip(
            "Summaries",
            isActive = active == LibrarySearchKind.Summary,
            onTap = { onTap(LibrarySearchKind.Summary) },
        )
        Chip(
            "Transcripts",
            isActive = active == LibrarySearchKind.Transcript,
            onTap = { onTap(LibrarySearchKind.Transcript) },
        )
    }
}

@Composable
private fun Chip(
    label: String,
    isActive: Boolean,
    onTap: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val bg = if (isActive) c.text else c.surface
    val fg = if (isActive) c.bg else c.text
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, c.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyHint(text: String) {
    val c = LocalKofipodColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = c.textMute, fontSize = 14.sp)
    }
}

@Composable
private fun ResultsList(
    results: List<LibrarySearchResult>,
    onTap: (LibrarySearchResult) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(results, key = { resultKey(it) }) { result ->
            LibrarySearchRow(result = result, onTap = { onTap(result) })
        }
    }
}

private fun resultKey(result: LibrarySearchResult): String =
    when (result) {
        is LibrarySearchResult.BookmarkMatch -> "bookmark:${result.bookmarkId}"
        is LibrarySearchResult.SummaryMatch -> "summary:${result.episodeId}"
        is LibrarySearchResult.TranscriptMatch -> "transcript:${result.episodeId}"
    }
