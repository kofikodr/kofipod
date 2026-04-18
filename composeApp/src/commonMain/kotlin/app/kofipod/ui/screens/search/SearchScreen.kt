// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.domain.PodcastSummary
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchScreen(
    onOpenPodcast: (String) -> Unit,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Search", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
        Spacer(Modifier.height(16.dp))
        SearchBar(value = state.query, onValueChange = viewModel::setQuery)
        Spacer(Modifier.height(12.dp))
        TabRow(current = state.tab, onSelect = viewModel::setTab)
        Spacer(Modifier.height(16.dp))
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = c.pink)
            }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(state.error!!, color = c.danger)
            }
            state.results.isEmpty() && state.query.isNotBlank() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No results", color = c.textMute)
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.results, key = { it.id }) { p -> ResultCard(p, onClick = { onOpenPodcast(p.id) }) }
            }
        }
    }
}

@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text("Search podcasts or people…", color = c.textMute)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TabRow(current: SearchTab, onSelect: (SearchTab) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier.clip(RoundedCornerShape(r.pill)).background(c.purpleTint).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchTab.entries.forEach { tab ->
            val selected = tab == current
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (selected) c.purple else c.purpleTint)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (tab) { SearchTab.Title -> "By title"; SearchTab.Person -> "By person" },
                    color = if (selected) c.surface else c.text,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(p: PodcastSummary, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = p.artworkUrl.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(r.sm)).background(c.purpleTint),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, color = c.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.author.isNotBlank()) {
                Text(p.author, color = c.textSoft, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(p.description, color = c.textMute, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
