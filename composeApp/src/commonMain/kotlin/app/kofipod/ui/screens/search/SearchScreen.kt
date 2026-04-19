// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.kofipod.domain.PodcastSummary
import app.kofipod.ui.primitives.KPChip
import app.kofipod.ui.primitives.KPChipTone
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.primitives.LoadMoreRow
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchScreen(
    onOpenPodcast: (String) -> Unit,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(
            "Search",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Powered by the Podcast Index",
            color = c.textMute,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        SearchBar(
            value = state.query,
            onValueChange = viewModel::setQuery,
            onClear = { viewModel.setQuery("") },
        )
        Spacer(Modifier.height(14.dp))
        TabRow(current = state.tab, onSelect = viewModel::setTab)
        Spacer(Modifier.height(16.dp))

        when {
            state.loading ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = c.pink)
                }
            state.error != null ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(state.error!!, color = c.danger)
                }
            state.results.isEmpty() && state.query.isNotBlank() ->
                Box(
                    Modifier.fillMaxSize(),
                    Alignment.Center,
                ) {
                    Text("No results", color = c.textMute)
                }
            state.results.isEmpty() -> SearchEmptyState(onPickTopic = viewModel::setQuery)
            else -> {
                ResultsCaption(count = state.results.size)
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(state.results, key = { _, p -> p.id }) { index, p ->
                        ResultCard(
                            p = p,
                            isTopMatch = index == 0,
                            onClick = { onOpenPodcast(p.id) },
                        )
                    }
                    if (state.hasMore) {
                        item(key = "load-more") {
                            LoadMoreRow(loading = state.loadingMore, onClick = viewModel::loadMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsCaption(count: Int) {
    val c = LocalKofipodColors.current
    val label = if (count == 1) "1 PODCAST" else "$count PODCASTS"
    Text(
        label,
        color = c.textMute,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.surfaceAlt)
            .border(1.dp, c.border, RoundedCornerShape(r.pill))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Search, color = c.textMute, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Search podcasts or people",
                    color = c.textMute,
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle =
                    TextStyle(
                        color = c.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.pink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(r.pill))
                    .background(c.purpleTint)
                    .clickable { onClear() },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Close, color = c.textSoft, size = 14.dp)
            }
        }
    }
}

@Composable
private fun TabRow(
    current: SearchTab,
    onSelect: (SearchTab) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchTab.entries.forEach { tab ->
            val selected = tab == current
            KPChip(
                label =
                    when (tab) {
                        SearchTab.All -> "All"
                        SearchTab.Title -> "By title"
                        SearchTab.Person -> "By person"
                    },
                selected = selected,
                tone = if (selected) KPChipTone.Neutral else KPChipTone.Outline,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun ResultCard(
    p: PodcastSummary,
    isTopMatch: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        KofipodArtwork(
            size = 68.dp,
            seed = p.feedId.toInt(),
            label = p.title,
            radius = 12.dp,
            model = p.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.title,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isTopMatch) {
                    Spacer(Modifier.width(8.dp))
                    TopMatchBadge()
                }
            }
            if (p.author.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    p.author,
                    color = c.textSoft,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    p.description,
                    color = c.textMute,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.category.isNotBlank() || p.episodeCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.category.isNotBlank()) {
                        CategoryTag(p.category)
                    }
                    Spacer(Modifier.weight(1f))
                    if (p.episodeCount > 0) {
                        Text(
                            "${p.episodeCount} eps",
                            color = c.textMute,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopMatchBadge() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .clip(RoundedCornerShape(r.pill))
            .background(c.pink)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "TOP MATCH",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
private fun CategoryTag(label: String) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .clip(RoundedCornerShape(r.pill))
            .border(1.dp, c.border, RoundedCornerShape(r.pill))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = c.textSoft,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchEmptyState(onPickTopic: (String) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val topics = listOf("Tech", "Comedy", "News", "Design")
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(r.pill))
                    .background(c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(
                name = KPIconName.Search,
                color = c.purple,
                size = 44.dp,
                strokeWidth = 2.0f,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Find your next listen",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Search by title or person, or pick a topic below.",
            color = c.textSoft,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "SUGGESTED TOPICS",
            color = c.textMute,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                topics.forEach { label ->
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(r.pill))
                                .background(c.surface)
                                .border(1.dp, c.border, RoundedCornerShape(r.pill))
                                .clickable { onPickTopic(label) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = c.text,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
