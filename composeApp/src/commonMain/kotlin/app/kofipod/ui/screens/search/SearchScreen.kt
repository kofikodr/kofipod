// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.domain.PodcastSummary
import app.kofipod.ui.primitives.KPChip
import app.kofipod.ui.primitives.KPChipTone
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.primitives.LoadMoreRow
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import com.mr3y.podcastindex.model.Category
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
            state.results.isEmpty() ->
                SearchEmptyState(
                    categories = state.popularCategories,
                    onPickTopic = viewModel::setQuery,
                )
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
                    "Try \"design critique\" or \"Ada Palmer\"",
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
private fun SearchEmptyState(
    categories: List<Category>,
    onPickTopic: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
    ) {
        SearchHeroCard()
        SectionLabel(title = "Popular categories", topSpacing = 22.dp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { category ->
                CategoryAvatarPill(
                    label = category.label,
                    onClick = { onPickTopic(category.label) },
                )
            }
        }
    }
}

@Composable
private fun SearchHeroCard() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    val heroGradient =
        Brush.linearGradient(
            colors = listOf(c.purpleSoft, c.purple, c.purpleDeep),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(r.lg))
                .background(heroGradient),
    ) {
        HeroDecorTiles(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 160.dp, bottom = 22.dp),
        ) {
            val headline =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Over ") }
                    withStyle(SpanStyle(color = c.pink)) { append("4 million") }
                    withStyle(SpanStyle(color = Color.White)) {
                        append(" shows\nare waiting on the other side.")
                    }
                }
            Text(
                headline,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Search by a show title — or the name of someone you'd love to hear.",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/**
 * Three decorative tilted tiles on the hero card. Pure canvas — no data.
 * Left: purple, medium, rotated -16°. Center: pink, largest, slight rotation.
 * Right: orange, medium, rotated +14°. Each carries a tiny 2-letter glyph
 * in white mono as a hint of "podcast cover" without representing a real show.
 */
@Composable
private fun HeroDecorTiles(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(width = 240.dp, height = 140.dp)) {
        DecorTile(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 16.dp, y = 6.dp)
                    .rotate(-16f),
            size = 92.dp,
            brush =
                Brush.linearGradient(
                    colors = listOf(Color(0xFFB084F5), Color(0xFF7E4DE0)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            glyph = "SN",
        )
        DecorTile(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .rotate(-2f),
            size = 112.dp,
            brush =
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFF5FA3), Color(0xFFE11D75)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            glyph = "SS",
        )
        DecorTile(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-16).dp, y = 10.dp)
                    .rotate(14f),
            size = 92.dp,
            brush =
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFFA24B), Color(0xFFE07315)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            glyph = "TR",
        )
    }
}

@Composable
private fun DecorTile(
    modifier: Modifier,
    size: androidx.compose.ui.unit.Dp,
    brush: Brush,
    glyph: String,
) {
    val r = LocalKofipodRadii.current
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(r.lg))
                .background(brush),
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Diagonal stripe overlay for a subtle cover-art texture.
            val stroke = 1.2f.dp.toPx()
            val step = 8.dp.toPx()
            val count = ((this.size.width + this.size.height) / step).toInt() + 2
            val strokeColor = Color.White.copy(alpha = 0.16f)
            for (i in 0..count) {
                val x = i * step - this.size.height
                drawLine(
                    color = strokeColor,
                    start = Offset(x, 0f),
                    end = Offset(x + this.size.height, this.size.height),
                    strokeWidth = stroke,
                )
            }
        }
        Text(
            glyph,
            color = Color.White.copy(alpha = 0.92f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 8.dp),
        )
    }
}

@Composable
private fun CategoryAvatarPill(
    label: String,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(r.pill))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(r.pill))
                .clickable { onClick() }
                .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(c.purple),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                categoryInitials(label),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = c.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

private fun categoryInitials(label: String): String {
    // "True Crime" → "TC"; "Arts" → "AR"; single-word fallback uses first two letters.
    val parts = label.split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
    return if (parts.size >= 2) {
        "${parts[0].first().uppercase()}${parts[1].first().uppercase()}"
    } else {
        label.take(2).uppercase()
    }
}
